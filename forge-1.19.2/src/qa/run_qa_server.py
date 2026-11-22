#!/usr/bin/env python3
"""Two-phase (write + cold-restart read) live Forge 1.19.2 dedicated server QA runner for LC2."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time


MIXIN_OR_LC2_ERROR_PATTERNS = [
    re.compile(r"MixinApplyError|InvalidMixinException|MixinTransformerError|MixinPrepareError", re.IGNORECASE),
    re.compile(r"Critical injection failure.*lc2", re.IGNORECASE),
    re.compile(r"Failed to load mod.*lc2", re.IGNORECASE),
    re.compile(r"\[lc2[^\]]*\]/\w*ERROR", re.IGNORECASE),
    re.compile(r"ERROR.*com\.logisticscraft\.lc2", re.IGNORECASE),
    re.compile(r"ERROR.*CableBlockMixin", re.IGNORECASE),
    re.compile(r"ERROR.*lc2\.mixins\.json", re.IGNORECASE),
    re.compile(r"\[LC2-QA\] FAIL", re.IGNORECASE),
    re.compile(r"Encountered an unexpected exception", re.IGNORECASE),
    re.compile(r"Crash report saved to", re.IGNORECASE),
    re.compile(r"java\.lang\.(NoSuchMethodError|NoSuchFieldError|NoClassDefFoundError|ClassNotFoundException|VerifyError|IncompatibleClassChangeError|AbstractMethodError)", re.IGNORECASE),
]


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def parse_properties(path: Path) -> dict:
    props = {}
    if not path.is_file():
        return props
    for raw in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or line.startswith("!"):
            continue
        if "=" in line:
            k, v = line.split("=", 1)
            props[k.strip()] = v.strip().replace("\\:", ":").replace("\\\\", "\\")
    return props


def scan_log_for_errors(log_path: Path) -> list:
    if not log_path.is_file():
        return [f"Missing expected log file: {log_path}"]
    errors = []
    text = log_path.read_text(encoding="utf-8", errors="replace")
    for lineno, line in enumerate(text.splitlines(), 1):
        if 'does not specify "minVersion" property' in line:
            continue
        for pat in MIXIN_OR_LC2_ERROR_PATTERNS:
            if pat.search(line):
                errors.append(f"{log_path.name}:{lineno}: {line.strip()}")
                break
    return errors


def build_command(manifest: dict, phase: str) -> list:
    jvm_args = [arg for arg in manifest["jvm"] if not arg.startswith("-Dlc2.qa.phase=")]
    jvm_args.append(f"-Dlc2.qa.phase={phase}")
    # Ensure sufficient heap for Forge + IC2 + LC2
    if not any(arg.startswith("-Xmx") for arg in jvm_args):
        jvm_args.append("-Xmx2048M")
    return [
        manifest["java"],
        *jvm_args,
        "-cp",
        manifest["classpath"],
        manifest["main"],
        *manifest["args"],
    ]


def run_phase(manifest: dict, run_dir: Path, phase: str, timeout_sec: int) -> tuple:
    cmd = build_command(manifest, phase)
    env = os.environ.copy()
    for k, v in manifest["env"].items():
        env[str(k)] = str(v)

    console_log = run_dir / f"qa-{phase}-console.log"
    print(f"\n[LC2-QA] === Starting Forge 1.19.2 Dedicated Server (phase={phase}) ===", flush=True)
    print(f"[LC2-QA] Run dir: {run_dir}", flush=True)
    print(f"[LC2-QA] MOD_CLASSES={env.get('MOD_CLASSES', '')}", flush=True)

    start = time.monotonic()
    proc = subprocess.Popen(
        cmd,
        cwd=str(run_dir),
        env=env,
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
        bufsize=1,
    )

    try:
        with console_log.open("w", encoding="utf-8") as out:
            assert proc.stdout is not None
            for line in proc.stdout:
                out.write(line)
                out.flush()
                stripped = line.rstrip("\r\n")
                if (
                    "[LC2-QA]" in stripped
                    or "ERROR]" in stripped
                    or "FATAL]" in stripped
                    or "Mixin" in stripped
                    or "LogisticsCraft" in stripped
                    or "Exception" in stripped
                    or "Caused by:" in stripped
                    or "Done (" in stripped
                ):
                    print(stripped, flush=True)
                if time.monotonic() - start > timeout_sec:
                    proc.kill()
                    raise TimeoutError(f"Phase '{phase}' timed out after {timeout_sec}s; see {console_log}")
        rc = proc.wait(timeout=30)
    finally:
        if proc.poll() is None:
            proc.kill()
            proc.wait()

    elapsed = time.monotonic() - start
    print(f"[LC2-QA] === Phase '{phase}' exited rc={rc} in {elapsed:.1f}s ===", flush=True)

    latest_log = run_dir / "logs" / "latest.log"
    archived_log = run_dir / "logs" / f"{phase}-latest.log"
    if latest_log.is_file():
        shutil.copyfile(latest_log, archived_log)

    log_errors = []
    if archived_log.is_file():
        log_errors.extend(scan_log_for_errors(archived_log))
    else:
        log_errors.append(f"Missing {latest_log} after phase '{phase}'")
    log_errors.extend(scan_log_for_errors(console_log))

    # Deduplicate while preserving order
    seen = set()
    unique_errors = []
    for err in log_errors:
        if err not in seen:
            seen.add(err)
            unique_errors.append(err)

    report_path = run_dir / f"qa-{phase}.properties"
    props = parse_properties(report_path)
    return rc, props, unique_errors, archived_log


def prepare_run_directory(manifest: dict, run_dir: Path) -> None:
    if run_dir.exists():
        shutil.rmtree(run_dir)
    mods_dir = run_dir / "mods"
    mods_dir.mkdir(parents=True, exist_ok=True)

    shutil.copyfile(manifest["ic2ModJar"], mods_dir / "ic2-forge-1.19.2-1.0.5.jar")
    shutil.copyfile(manifest["lc2ModJar"], mods_dir / "lc2-forge-1.19.2-1.0.0.jar")

    (run_dir / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (run_dir / "server.properties").write_text(
        "\n".join(
            [
                "server-ip=127.0.0.1",
                "server-port=0",
                "online-mode=false",
                "level-name=world",
                "level-seed=42",
                "level-type=minecraft\\:flat",
                "generator-settings={\"biome\"\\:\"minecraft\\:plains\",\"layers\"\\:[{\"block\"\\:\"minecraft\\:bedrock\",\"height\"\\:1},{\"block\"\\:\"minecraft\\:dirt\",\"height\"\\:2},{\"block\"\\:\"minecraft\\:grass_block\",\"height\"\\:1}]}",
                "generate-structures=false",
                "view-distance=2",
                "simulation-distance=2",
                "spawn-protection=0",
                "spawn-monsters=false",
                "spawn-animals=false",
                "spawn-npcs=true",
                "enable-command-block=false",
                "allow-nether=false",
                "difficulty=peaceful",
                "max-tick-time=120000",
                "sync-chunk-writes=true",
            ]
        )
        + "\n",
        encoding="utf-8",
    )


def write_combined_report(
    report_path: Path,
    manifest: dict,
    write_rc: int,
    write_props: dict,
    write_errors: list,
    read_rc: int,
    read_props: dict,
    read_errors: list,
    overall_passed: bool,
) -> None:
    lines = [
        "# LogisticsCraft 2 Forge 1.19.2 Live-Server QA Report",
        f"status={'PASSED' if overall_passed else 'FAILED'}",
        f"minecraft=1.19.2",
        f"forge=43.5.0",
        f"packaged.ic2.jar={manifest['packagedIc2Jar']}",
        f"packaged.ic2.sha256={sha256(Path(manifest['packagedIc2Jar']))}",
        f"packaged.lc2.jar={manifest['packagedLc2Jar']}",
        f"packaged.lc2.sha256={sha256(Path(manifest['packagedLc2Jar']))}",
        f"phase.write.exitCode={write_rc}",
        f"phase.write.status={write_props.get('status', 'MISSING')}",
        f"phase.write.passedChecks={write_props.get('passedChecks', '0')}",
        f"phase.write.failedChecks={write_props.get('failedChecks', '1')}",
        f"phase.write.logErrors={len(write_errors)}",
        f"phase.read.exitCode={read_rc}",
        f"phase.read.status={read_props.get('status', 'MISSING')}",
        f"phase.read.passedChecks={read_props.get('passedChecks', '0')}",
        f"phase.read.failedChecks={read_props.get('failedChecks', '1')}",
        f"phase.read.logErrors={len(read_errors)}",
    ]
    for k in sorted(write_props):
        if k.startswith("check.") or k.startswith("scenario.") or k.startswith("metric."):
            lines.append(f"write.{k}={write_props[k]}")
    for k in sorted(read_props):
        if k.startswith("check.") or k.startswith("scenario.") or k.startswith("metric."):
            lines.append(f"read.{k}={read_props[k]}")
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--timeout", type=int, default=240)
    args = parser.parse_args()

    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    run_dir = Path(manifest["runDir"])
    report_dir = Path(manifest["reportDir"])

    prepare_run_directory(manifest, run_dir)

    write_rc, write_props, write_errors, _ = run_phase(manifest, run_dir, "write", args.timeout)
    write_ok = (
        write_rc == 0
        and write_props.get("status") == "PASSED"
        and write_props.get("failedChecks") == "0"
        and len(write_errors) == 0
    )

    if write_ok:
        read_rc, read_props, read_errors, _ = run_phase(manifest, run_dir, "read", args.timeout)
    else:
        read_rc, read_props, read_errors = -1, {"status": "SKIPPED_AFTER_WRITE_FAILURE"}, []

    read_ok = (
        write_ok
        and read_rc == 0
        and read_props.get("status") == "PASSED"
        and read_props.get("failedChecks") == "0"
        and len(read_errors) == 0
    )

    overall_passed = write_ok and read_ok
    report_path = report_dir / "qa-report.properties"
    write_combined_report(
        report_path,
        manifest,
        write_rc,
        write_props,
        write_errors,
        read_rc,
        read_props,
        read_errors,
        overall_passed,
    )
    shutil.copyfile(report_path, run_dir / "qa-report.properties")

    print("\n[LC2-QA] ==============================================", flush=True)
    print(f"[LC2-QA] Final Status: {'PASSED' if overall_passed else 'FAILED'}", flush=True)
    print(
        f"[LC2-QA] Write Phase: rc={write_rc}, status={write_props.get('status', 'MISSING')}, "
        f"passed={write_props.get('passedChecks', '0')}, failed={write_props.get('failedChecks', '?')}, "
        f"logErrors={len(write_errors)}",
        flush=True,
    )
    print(
        f"[LC2-QA] Read  Phase: rc={read_rc}, status={read_props.get('status', 'MISSING')}, "
        f"passed={read_props.get('passedChecks', '0')}, failed={read_props.get('failedChecks', '?')}, "
        f"logErrors={len(read_errors)}",
        flush=True,
    )
    print(f"[LC2-QA] Report: {report_path}", flush=True)

    if write_errors:
        print("[LC2-QA] Write phase log errors:", flush=True)
        for err in write_errors[:30]:
            print(f"  - {err}", flush=True)
    if read_errors:
        print("[LC2-QA] Read phase log errors:", flush=True)
        for err in read_errors[:30]:
            print(f"  - {err}", flush=True)

    if not overall_passed:
        for name in ("qa-write-failure.txt", "qa-read-failure.txt"):
            fp = run_dir / name
            if fp.is_file():
                print(f"\n[LC2-QA] Contents of {fp}:", flush=True)
                print(fp.read_text(encoding="utf-8", errors="replace"), flush=True)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
