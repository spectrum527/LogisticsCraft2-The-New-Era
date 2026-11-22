#!/usr/bin/env python3
"""Real-world Forge 1.19.2 Dedicated Server + Graphical Client (1280x720) field test runner for LC2."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import sys
import threading
import time


ERROR_PATTERNS = [
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
    re.compile(r"Missing texture.*lc2", re.IGNORECASE),
    re.compile(r"Unable to load model.*lc2", re.IGNORECASE),
    re.compile(r"Missing model.*lc2", re.IGNORECASE),
    re.compile(r"FileNotFoundException.*lc2", re.IGNORECASE),
    re.compile(r"Using missing texture, unable to load lc2:", re.IGNORECASE),
    re.compile(r"java\.lang\.(NoSuchMethodError|NoSuchFieldError|NoClassDefFoundError|ClassNotFoundException|VerifyError|IncompatibleClassChangeError|AbstractMethodError)", re.IGNORECASE),
]


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def find_free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return int(s.getsockname()[1])


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


def scan_log(log_path: Path) -> list:
    if not log_path.is_file():
        return [f"Missing expected log file: {log_path}"]
    errors = []
    text = log_path.read_text(encoding="utf-8", errors="replace")
    for lineno, line in enumerate(text.splitlines(), 1):
        if 'ic2.mixins.json does not specify "minVersion" property' in line:
            continue
        for pat in ERROR_PATTERNS:
            if pat.search(line):
                errors.append(f"{log_path.name}:{lineno}: {line.strip()}")
                break
    return errors


def stream_proc(proc: subprocess.Popen, log_file: Path, tag: str) -> None:
    assert proc.stdout is not None
    with log_file.open("w", encoding="utf-8") as out:
        for line in proc.stdout:
            out.write(line)
            out.flush()
            s = line.rstrip("\r\n")
            if (
                "[LC2-FIELD" in s
                or "[LC2-QA]" in s
                or "ERROR]" in s
                or "FATAL]" in s
                or "Missing texture" in s
                or "Unable to load model" in s
                or "Mixin" in s
                or "LogisticsCraft" in s
                or "Exception" in s
                or "Caused by:" in s
                or "Done (" in s
                or "joined the game" in s
                or "left the game" in s
            ):
                print(f"[{tag}] {s}", flush=True)


def build_jvm_cmd(spec: dict, extra_jvm: list, extra_args: list, xmx: str) -> list:
    jvm = [a for a in spec["jvm"] if not a.startswith("-Dlc2.qa.phase=")]
    jvm.extend(extra_jvm)
    if not any(a.startswith("-Xmx") for a in jvm):
        jvm.append(f"-Xmx{xmx}")
    args = list(spec["args"])
    args.extend(extra_args)
    return [
        spec["java"],
        *jvm,
        "-cp",
        spec["classpath"],
        spec["main"],
        *args,
    ]


def prepare_server_dir(manifest: dict, run_dir: Path, port: int, seed: str) -> None:
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
                f"server-port={port}",
                "online-mode=false",
                "level-name=world",
                f"level-seed={seed}",
                "level-type=minecraft\\:normal",
                "generate-structures=true",
                "view-distance=8",
                "simulation-distance=8",
                "spawn-protection=0",
                "spawn-monsters=false",
                "spawn-animals=true",
                "spawn-npcs=true",
                "enable-command-block=false",
                "allow-nether=false",
                "allow-flight=true",
                "difficulty=peaceful",
                "max-tick-time=180000",
                "sync-chunk-writes=true",
            ]
        )
        + "\n",
        encoding="utf-8",
    )


def prepare_client_dir(manifest: dict, run_dir: Path) -> None:
    if run_dir.exists():
        shutil.rmtree(run_dir)
    mods_dir = run_dir / "mods"
    mods_dir.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(manifest["ic2ModJar"], mods_dir / "ic2-forge-1.19.2-1.0.5.jar")
    shutil.copyfile(manifest["lc2ModJar"], mods_dir / "lc2-forge-1.19.2-1.0.0.jar")
    (run_dir / "options.txt").write_text(
        "\n".join(
            [
                "overrideWidth:1280",
                "overrideHeight:720",
                "guiScale:3",
                "renderDistance:10",
                "simulationDistance:8",
                "particles:1",
                "graphicsMode:1",
                "ao:2",
                "gamma:0.8",
                "fov:0.0",
                "pauseOnLostFocus:false",
                "tutorialStep:none",
                "skipMultiplayerWarning:true",
                "joinedFirstServer:true",
                "narrator:0",
                "soundCategory_master:0.0",
                "onboardAccessibility:false",
            ]
        )
        + "\n",
        encoding="utf-8",
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--seed", default="20261121")
    parser.add_argument("--screenshots-dir", type=Path, default=None)
    parser.add_argument("--server-only", action="store_true")
    parser.add_argument("--timeout", type=int, default=420)
    args = parser.parse_args()

    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    server_dir = Path(manifest["serverRunDir"])
    client_dir = Path(manifest["clientRunDir"])
    report_dir = Path(manifest["reportDir"])
    screenshots_dir = args.screenshots_dir or (report_dir / "field-screenshots")
    if screenshots_dir.exists():
        shutil.rmtree(screenshots_dir)
    screenshots_dir.mkdir(parents=True, exist_ok=True)

    port = find_free_port()
    prepare_server_dir(manifest, server_dir, port, str(args.seed))
    if not args.server_only:
        prepare_client_dir(manifest, client_dir)

    server_spec = manifest["server"]
    server_cmd = build_jvm_cmd(
        server_spec,
        [
            "-Dlc2.qa.phase=field_server",
            f"-Dlc2.field.waitForClient={'false' if args.server_only else 'true'}",
            f"-Dlc2.field.seed={args.seed}",
        ],
        [],
        "3072M",
    )
    server_env = os.environ.copy()
    for k, v in server_spec["env"].items():
        server_env[str(k)] = str(v)

    print(
        f"\n[LC2-FIELD] === Starting Forge 1.19.2 Natural-World Dedicated Server (seed={args.seed}, port={port}) ===",
        flush=True,
    )
    print(f"[LC2-FIELD] Server MOD_CLASSES={server_env.get('MOD_CLASSES', '')}", flush=True)

    start_time = time.monotonic()
    server_proc = subprocess.Popen(
        server_cmd,
        cwd=str(server_dir),
        env=server_env,
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
        bufsize=1,
    )
    server_console = server_dir / "field-server-console.log"
    server_thread = threading.Thread(
        target=stream_proc, args=(server_proc, server_console, "SERVER"), daemon=True
    )
    server_thread.start()

    client_proc = None
    client_thread = None
    client_console = client_dir / "field-client-console.log"
    ready_marker = server_dir / "field-server-ready.properties"

    try:
        if not args.server_only:
            # Wait for Phase 2 server readiness marker before spawning graphical client
            while server_proc.poll() is None:
                if ready_marker.is_file():
                    break
                if time.monotonic() - start_time > args.timeout:
                    raise TimeoutError(f"Server timed out waiting for Phase 2 completion after {args.timeout}s")
                time.sleep(0.5)

            if server_proc.poll() is not None and not ready_marker.is_file():
                print(f"[LC2-FIELD] Server exited early (rc={server_proc.returncode}) before Phase 3!", flush=True)
            else:
                ready_props = parse_properties(ready_marker)
                live_port = int(ready_props.get("port", str(port)))
                client_spec = manifest["client"]
                client_extra_args = [
                    "--username",
                    "Lc2FieldTester",
                    "--width",
                    "1280",
                    "--height",
                    "720",
                ]
                # Remove any conflicting --username/--width/--height already in client args
                filtered_client_spec = dict(client_spec)
                cleaned_args = []
                raw_args = list(client_spec["args"])
                i = 0
                while i < len(raw_args):
                    if raw_args[i] in ("--username", "--width", "--height"):
                        i += 2
                        continue
                    cleaned_args.append(raw_args[i])
                    i += 1
                filtered_client_spec["args"] = cleaned_args

                client_cmd = build_jvm_cmd(
                    filtered_client_spec,
                    [
                        "-Dlc2.qa.phase=field_client",
                        f"-Dlc2.field.port={live_port}",
                        f"-Dlc2.field.screenshotsDir={screenshots_dir.resolve()}",
                    ],
                    client_extra_args,
                    "3072M",
                )
                client_env = os.environ.copy()
                for k, v in client_spec["env"].items():
                    client_env[str(k)] = str(v)

                print(
                    f"\n[LC2-FIELD] === Starting Real Forge 1.19.2 Graphical Client (1280x720 -> 127.0.0.1:{live_port}) ===",
                    flush=True,
                )
                print(f"[LC2-FIELD] Client MOD_CLASSES={client_env.get('MOD_CLASSES', '')}", flush=True)

                client_proc = subprocess.Popen(
                    client_cmd,
                    cwd=str(client_dir),
                    env=client_env,
                    stdin=subprocess.DEVNULL,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.STDOUT,
                    text=True,
                    encoding="utf-8",
                    errors="replace",
                    bufsize=1,
                )
                client_thread = threading.Thread(
                    target=stream_proc, args=(client_proc, client_console, "CLIENT"), daemon=True
                )
                client_thread.start()

        # Wait for both processes to complete cleanly
        while True:
            srv_done = server_proc.poll() is not None
            cli_done = client_proc is None or client_proc.poll() is not None
            if srv_done and cli_done:
                break
            if srv_done and client_proc is not None and client_proc.poll() is None:
                # Server exited first; give client up to 20s to finish cleanly before stopping it
                time.sleep(0.5)
                if time.monotonic() - start_time > args.timeout:
                    client_proc.terminate()
                    break
            if time.monotonic() - start_time > args.timeout:
                raise TimeoutError(f"Field test timed out after {args.timeout}s")
            time.sleep(0.5)
    finally:
        if client_proc is not None and client_proc.poll() is None:
            client_proc.kill()
            client_proc.wait()
        if server_proc.poll() is None:
            server_proc.kill()
            server_proc.wait()
        server_thread.join(timeout=5)
        if client_thread is not None:
            client_thread.join(timeout=5)

    srv_rc = server_proc.returncode
    cli_rc = 0 if args.server_only else (client_proc.returncode if client_proc is not None else -1)

    srv_props = parse_properties(server_dir / "field-server-report.properties")
    cli_props = {} if args.server_only else parse_properties(client_dir / "field-client-report.properties")

    srv_errors = []
    srv_errors.extend(scan_log(server_dir / "logs" / "latest.log"))
    srv_errors.extend(scan_log(server_console))
    srv_errors = list(dict.fromkeys(srv_errors))

    cli_errors = []
    if not args.server_only:
        cli_errors.extend(scan_log(client_dir / "logs" / "latest.log"))
        cli_errors.extend(scan_log(client_console))
        cli_errors = list(dict.fromkeys(cli_errors))

    srv_ok = (
        srv_rc == 0
        and srv_props.get("status") == "PASSED"
        and srv_props.get("failedChecks") == "0"
        and len(srv_errors) == 0
    )
    cli_ok = args.server_only or (
        cli_rc == 0
        and cli_props.get("status") == "PASSED"
        and cli_props.get("failedChecks") == "0"
        and len(cli_errors) == 0
    )
    overall_ok = srv_ok and cli_ok

    # Write combined report
    report_path = report_dir / "field-report.properties"
    lines = [
        "# LogisticsCraft 2 Forge 1.19.2 Natural-World + Client-Server Field Test Report",
        f"status={'PASSED' if overall_ok else 'FAILED'}",
        f"seed={args.seed}",
        f"packaged.ic2.jar={manifest['packagedIc2Jar']}",
        f"packaged.ic2.sha256={sha256(Path(manifest['packagedIc2Jar']))}",
        f"packaged.lc2.jar={manifest['packagedLc2Jar']}",
        f"packaged.lc2.sha256={sha256(Path(manifest['packagedLc2Jar']))}",
        f"server.exitCode={srv_rc}",
        f"server.status={srv_props.get('status', 'MISSING')}",
        f"server.passedChecks={srv_props.get('passedChecks', '0')}",
        f"server.failedChecks={srv_props.get('failedChecks', '1')}",
        f"server.logErrors={len(srv_errors)}",
        f"client.exitCode={cli_rc}",
        f"client.status={'SKIPPED' if args.server_only else cli_props.get('status', 'MISSING')}",
        f"client.passedChecks={cli_props.get('passedChecks', '0')}",
        f"client.failedChecks={cli_props.get('failedChecks', '0' if args.server_only else '1')}",
        f"client.logErrors={len(cli_errors)}",
        f"screenshotsDir={screenshots_dir.resolve()}",
    ]
    for k in sorted(srv_props):
        if k.startswith(("check.", "metric.", "seed", "shore")):
            lines.append(f"server.{k}={srv_props[k]}")
    for k in sorted(cli_props):
        if k.startswith(("check.", "metric.", "screenshot.")):
            lines.append(f"client.{k}={cli_props[k]}")
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text("\n".join(lines) + "\n", encoding="utf-8")

    elapsed = time.monotonic() - start_time
    print("\n[LC2-FIELD] ==============================================", flush=True)
    print(f"[LC2-FIELD] Final Status: {'PASSED' if overall_ok else 'FAILED'} (elapsed {elapsed:.1f}s)", flush=True)
    print(
        f"[LC2-FIELD] Server: rc={srv_rc}, status={srv_props.get('status', 'MISSING')}, "
        f"passed={srv_props.get('passedChecks', '0')}, failed={srv_props.get('failedChecks', '?')}, "
        f"logErrors={len(srv_errors)}",
        flush=True,
    )
    if not args.server_only:
        print(
            f"[LC2-FIELD] Client: rc={cli_rc}, status={cli_props.get('status', 'MISSING')}, "
            f"passed={cli_props.get('passedChecks', '0')}, failed={cli_props.get('failedChecks', '?')}, "
            f"logErrors={len(cli_errors)}",
            flush=True,
        )
    print(f"[LC2-FIELD] Screenshots: {screenshots_dir.resolve()}", flush=True)
    for png in sorted(screenshots_dir.glob("*.png")):
        print(f"  - {png.resolve()} ({png.stat().st_size} bytes)", flush=True)
    print(f"[LC2-FIELD] Report: {report_path}", flush=True)

    if srv_errors:
        print("[LC2-FIELD] Server log errors:", flush=True)
        for e in srv_errors[:30]:
            print(f"  - {e}", flush=True)
    if cli_errors:
        print("[LC2-FIELD] Client log errors:", flush=True)
        for e in cli_errors[:30]:
            print(f"  - {e}", flush=True)

    if not overall_ok:
        for fp in (server_dir / "field-server-failure.txt", client_dir / "field-client-failure.txt"):
            if fp.is_file():
                print(f"\n[LC2-FIELD] Contents of {fp}:", flush=True)
                print(fp.read_text(encoding="utf-8", errors="replace"), flush=True)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
