#!/usr/bin/env python3
"""Run a real graphical Minecraft Client connected to a Dedicated Server for LC2 Phase 3 Field QA."""

import argparse
import json
import math
import os
import re
import shutil
import socket
import subprocess
import sys
import time
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
HOST = "127.0.0.1"
SERVER_READY = re.compile(r"\bDone \(\d+(?:\.\d+)?s\)!")
ENV_WHITELIST = {
    "PATH", "HOME", "USER", "LOGNAME", "TMPDIR", "TEMP", "TMP", "LANG",
    "DISPLAY", "WAYLAND_DISPLAY", "XDG_RUNTIME_DIR", "XAUTHORITY", "SystemRoot",
}


def pick_free_port() -> int:
    with socket.socket() as sock:
        sock.bind((HOST, 0))
        return sock.getsockname()[1]


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace") if path.is_file() else ""


def write_json_atomic(path: Path, data: dict) -> None:
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
    tmp.replace(path)


class LiveSession:
    def __init__(self, mc_version: str, manifest: dict, run_dir: Path, port: int):
        self.mc_version = mc_version
        self.manifest = manifest
        self.run_dir = run_dir
        self.server_dir = run_dir / "server"
        self.client_dir = run_dir / "client"
        self.port = port
        self.server = None
        self.client = None
        self.seq = 0
        self.checks = {}

    def prepare_dirs(self) -> None:
        if self.run_dir.exists():
            shutil.rmtree(self.run_dir)
        (self.server_dir / "mods").mkdir(parents=True)
        (self.client_dir / "mods").mkdir(parents=True)

        lc2_jar = Path(self.manifest["lc2Jar"])
        ic2_jar = Path(self.manifest["ic2Jar"])
        for side_dir in (self.server_dir, self.client_dir):
            shutil.copy2(lc2_jar, side_dir / "mods" / lc2_jar.name)
            shutil.copy2(ic2_jar, side_dir / "mods" / ic2_jar.name)

        (self.server_dir / "eula.txt").write_text("eula=true\n", encoding="utf-8")
        (self.server_dir / "server.properties").write_text(
            "\n".join([
                f"server-ip={HOST}",
                f"server-port={self.port}",
                "online-mode=false",
                "level-name=world",
                f"level-seed={self.manifest['seed']}",
                "level-type=minecraft:normal",
                "generate-structures=true",
                "spawn-protection=0",
                "difficulty=peaceful",
                "view-distance=8",
                "simulation-distance=6",
                "",
            ]),
            encoding="utf-8",
        )
        (self.client_dir / "config").mkdir(parents=True, exist_ok=True)
        (self.client_dir / "config" / "fml.toml").write_text("earlyWindowControl = false\n", encoding="utf-8")
        (self.client_dir / "options.txt").write_text(
            "\n".join([
                "onboardAccessibility:false",
                "lang:en_us",
                "fullscreen:false",
                "tutorialStep:none",
                "pauseOnLostFocus:false",
                "overrideWidth:1280",
                "overrideHeight:720",
                "renderDistance:8",
                "simulationDistance:6",
                "guiScale:3",
                "fov:0.0",
                "gamma:1.0",
                "narrator:0",
                "soundCategory_master:0.0",
                "",
            ]),
            encoding="utf-8",
        )

    def _spawn(self, role: str) -> subprocess.Popen:
        launch = self.manifest[role]
        env = {k: v for k, v in os.environ.items() if k in ENV_WHITELIST or k.startswith("LC_")}
        env.update(launch.get("environment", {}))
        jvm = list(launch["jvmArgs"])
        args = list(launch["args"])
        extra_props = [f"-Dlc2.qa.clientHost={HOST}", f"-Dlc2.qa.clientPort={self.port}"]
        macos_first_thread = ["-XstartOnFirstThread"] if (role == "client" and sys.platform == "darwin") else []
        cmd = [
            launch["java"],
            *macos_first_thread,
            *jvm,
            *extra_props,
            "-cp",
            launch["classpath"],
            launch["mainClass"],
            *args,
        ]
        log_path = self.run_dir / f"{role}.log"
        log_file = log_path.open("wb")
        proc = subprocess.Popen(
            cmd,
            cwd=self.server_dir if role == "server" else self.client_dir,
            env=env,
            stdin=subprocess.PIPE if role == "server" else subprocess.DEVNULL,
            stdout=log_file,
            stderr=subprocess.STDOUT,
            encoding="utf-8",
            start_new_session=True,
        )
        log_file.close()
        return proc

    def start(self) -> dict:
        self.prepare_dirs()
        print(f"[{self.mc_version}] Starting dedicated server on {HOST}:{self.port}...", flush=True)
        self.server = self._spawn("server")
        self._wait_for_server(180)
        site_path = self.server_dir / "field-site.json"
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline and not site_path.is_file():
            time.sleep(0.2)
        if not site_path.is_file():
            raise RuntimeError("Dedicated server started but did not produce field-site.json")
        site = json.loads(site_path.read_text(encoding="utf-8"))
        print(f"[{self.mc_version}] Dedicated server ready! Site: portA={site['portA']} -> portB={site['portB']} (dist={site['waterwayDistance']}m)", flush=True)

        print(f"[{self.mc_version}] Starting real graphical Minecraft client (1280x720)...", flush=True)
        self.client = self._spawn("client")
        return site

    def _wait_for_server(self, timeout: int) -> None:
        deadline = time.monotonic() + timeout
        log_path = self.run_dir / "server.log"
        while time.monotonic() < deadline:
            if self.server.poll() is not None:
                raise RuntimeError(f"Dedicated server exited early with code {self.server.returncode}\n"
                                   + read_text(log_path)[-2500:])
            if SERVER_READY.search(read_text(log_path)):
                return
            time.sleep(0.25)
        raise TimeoutError(f"Server did not finish starting within {timeout}s")

    def server_cmd(self, cmd: str) -> None:
        assert self.server is not None and self.server.stdin is not None
        self.server.stdin.write(cmd + "\n")
        self.server.stdin.flush()

    def server_state(self) -> dict:
        state_file = self.server_dir / "server-state.json"
        before_mtime = state_file.stat().st_mtime_ns if state_file.is_file() else 0
        self.server_cmd("lc2field dump_state")
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            if state_file.is_file() and state_file.stat().st_mtime_ns > before_mtime:
                return json.loads(state_file.read_text(encoding="utf-8"))
            time.sleep(0.1)
        raise TimeoutError("Timed out waiting for server-state.json")

    def act(self, action: str, **kwargs) -> dict:
        self.seq += 1
        payload = {"seq": self.seq, "action": action, **kwargs}
        write_json_atomic(self.client_dir / "qa-input.txt", payload)
        out_path = self.client_dir / "qa-output.json"
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            if self.client.poll() is not None:
                raise RuntimeError(
                    f"Client exited early ({self.client.returncode}) during {action}\n"
                    + read_text(self.run_dir / "client.log")[-2500:]
                )
            if out_path.is_file():
                try:
                    data = json.loads(out_path.read_text(encoding="utf-8"))
                except json.JSONDecodeError:
                    time.sleep(0.05)
                    continue
                if data.get("seq") == self.seq:
                    if "error" in data:
                        raise RuntimeError(f"Client error on {action}: {data['error']}")
                    return data
            time.sleep(0.08)
        raise TimeoutError(f"Client did not respond to {action} within 120s")

    def wait_until(self, label: str, pred, timeout: float = 90.0, interval: float = 0.2) -> dict:
        deadline = time.monotonic() + timeout
        last = {}
        while time.monotonic() < deadline:
            last = self.act("state")
            if pred(last):
                self.checks[label] = last
                return last
            time.sleep(interval)
        raise AssertionError(f"Timed out waiting for [{label}]; last state={json.dumps(last)}")

    def wait_screenshot(self, filename: str, timeout: float = 20.0) -> Path:
        shot_path = self.client_dir / "screenshots" / filename
        if shot_path.exists():
            shot_path.unlink()
        self.act("screenshot", name=filename)
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if shot_path.is_file() and shot_path.stat().st_size > 4096:
                time.sleep(0.25)
                return shot_path
            time.sleep(0.15)
        raise TimeoutError(f"Screenshot {filename} was not written to {shot_path}")

    def stop(self) -> tuple[int, int]:
        client_exit = 0
        server_exit = 0
        if self.client is not None:
            if self.client.poll() is None:
                try:
                    write_json_atomic(self.client_dir / "qa-input.txt", {"seq": self.seq + 999, "action": "quit"})
                except Exception:
                    pass
                try:
                    client_exit = self.client.wait(25)
                except subprocess.TimeoutExpired:
                    self.client.terminate()
                    try:
                        client_exit = self.client.wait(10)
                    except subprocess.TimeoutExpired:
                        self.client.kill()
                        client_exit = self.client.wait(10)
            else:
                client_exit = self.client.returncode

        if self.server is not None:
            if self.server.poll() is None:
                try:
                    self.server_cmd("stop")
                except Exception:
                    pass
                try:
                    server_exit = self.server.wait(30)
                except subprocess.TimeoutExpired:
                    self.server.terminate()
                    try:
                        server_exit = self.server.wait(10)
                    except subprocess.TimeoutExpired:
                        self.server.kill()
                        server_exit = self.server.wait(10)
            else:
                server_exit = self.server.returncode
        return client_exit, server_exit


def scan_log_for_errors(log_path: Path, role: str) -> None:
    text = read_text(log_path)
    bad = []
    for idx, line in enumerate(text.splitlines(), 1):
        lower = line.lower()
        is_mixin = (
            "MixinApplyError" in line
            or "InvalidMixinException" in line
            or "MixinTransformerError" in line
            or "Critical injection failure" in line
            or ("mixin" in lower and ("trera" in lower or "lc2" in lower) and "error" in lower)
        )
        is_lc2_err = (
            ("/ERROR]" in line or "Exception" in line or "AssertionError" in line)
            and (
                "com.logisticscraft.lc2" in line
                or "trera:" in line
                or "trera.mixins.json" in line
                or ("missing texture" in lower and "trera" in lower)
                or ("unable to load model" in lower and "trera" in lower)
            )
        )
        if is_mixin or is_lc2_err:
            bad.append(f"L{idx}: {line}")
    if bad:
        raise AssertionError(f"LC2/Mixin errors in {role} log ({log_path}):\n" + "\n".join(bad[:20]))


def run_live_suite(mc_version: str, screenshot_out: Path) -> None:
    manifest_path = ROOT / f"src/neoforge/{mc_version}/build/field-client-live/launch.json"
    if not manifest_path.is_file():
        raise FileNotFoundError(f"Missing {manifest_path}; run :neoforge:{mc_version}:prepareFieldClientPlay first")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    run_dir = ROOT / f"src/neoforge/{mc_version}/build/run/field-client-live/session"
    port = pick_free_port()
    screenshot_out.mkdir(parents=True, exist_ok=True)

    session = LiveSession(mc_version, manifest, run_dir, port)
    report = {"mcVersion": mc_version, "seed": manifest["seed"], "checks": {}, "screenshots": []}

    try:
        site = session.start()
        report["site"] = site

        # 1. Wait for TitleScreen and connect to Dedicated Server
        session.wait_until("client_title_ready", lambda s: s.get("screen") == "TitleScreen" and not s.get("overlay"), timeout=120)
        session.act("connect")
        session.wait_until("client_joined_server", lambda s: s.get("world") and not s.get("screen") and not s.get("overlay"), timeout=120)
        print(f"[{mc_version}] Client connected to Dedicated Server!", flush=True)
        session.server_cmd("op TreraFieldTester")
        time.sleep(0.5)

        # 2. Verify ALL LC2 sprites & textures baked/present (ZERO missing textures!)
        sprite_res = session.act("check_sprites")
        if not sprite_res.get("spritesOk"):
            raise AssertionError(f"Missing LC2 sprites/resources: {sprite_res.get('missingSprites')}")
        report["checks"]["sprites_all_baked"] = {
            "checked": sprite_res["spritesChecked"],
            "missing": sprite_res["missingSprites"],
        }
        print(f"[{mc_version}] Sprites & textures OK ({sprite_res['spritesChecked']}/{sprite_res['spritesChecked']})", flush=True)

        # 3. Scene 1: Outpost Harbor Panorama (Smart Port + Docked Boat + IC2 Cables + BatBox/Generators + Advanced Miner)
        session.server_cmd("lc2field scene_harbor")
        time.sleep(2.2)
        session.act("camera", mode="first")
        session.act("clear_hud")
        session.act("hide_gui", value=True)
        time.sleep(0.5)
        shot1 = session.wait_screenshot(f"lc2_{mc_version}_01_outpost_harbor.png")
        shutil.copy2(shot1, screenshot_out / shot1.name)
        report["screenshots"].append(str(screenshot_out / shot1.name))
        print(f"[{mc_version}] Captured {shot1.name}", flush=True)

        # 4. Real SmartPortScreen GUI Client <-> Server Test
        session.act("hide_gui", value=False)
        session.server_cmd("lc2field prep_smart_port_gui")
        time.sleep(1.0)
        pa = site["portA"]
        session.act("right_click_block", x=pa["x"], y=pa["y"], z=pa["z"], face=site["waterDirA"])
        sp_open = session.wait_until(
            "smart_port_screen_open",
            lambda s: s.get("screen") == "SmartPortScreen" and s.get("syncPresent") is True,
            timeout=25,
        )
        assert sp_open["syncStationName"] == "Outpost Harbor", f"Unexpected initial port name: {sp_open}"
        assert sp_open["syncAutoReturn"] is True, f"Expected initial autoReturn=true: {sp_open}"
        assert sp_open["syncPriority"] == "EU_FIRST", f"Expected initial EU_FIRST: {sp_open}"
        assert sp_open["syncStoredEu"] > 5000, f"Expected synced EU > 5000: {sp_open}"

        # Rename, cycle target, toggle Auto-Return, toggle Energy Priority, and click live Swap button over TCP!
        srv_sp_before_swap = session.server_state()["portA"]
        assert srv_sp_before_swap["outboundSlot0"] == "minecraft:gold_blockx8", f"Unexpected initial port outbound0: {srv_sp_before_swap}"
        assert srv_sp_before_swap["storageSlot0"] == "minecraft:copper_ingotx24", f"Unexpected initial port storage0: {srv_sp_before_swap}"

        session.act("gui_rename", name="Harbor Alpha")
        session.wait_until("smart_port_renamed", lambda s: s.get("syncStationName") == "Harbor Alpha", timeout=15)
        session.act("gui_click", button="toggle_autoreturn")
        session.wait_until("smart_port_autoreturn_toggled", lambda s: s.get("syncAutoReturn") is False, timeout=15)
        session.act("gui_click", button="toggle_priority")
        session.wait_until("smart_port_priority_toggled", lambda s: s.get("syncPriority") == "BATTERY_FIRST", timeout=15)
        session.act("gui_click", button="next_target")
        session.wait_until("smart_port_target_synced", lambda s: s.get("syncHasTarget") is True, timeout=15)
        session.act("gui_click", button="swap")
        sp_after = session.wait_until(
            "smart_port_swapped",
            lambda s: s.get("syncStatusKey") == "status.trera.swapped",
            timeout=15,
        )

        srv_sp = session.server_state()["portA"]
        assert srv_sp["name"] == "Harbor Alpha", f"Server did not persist rename: {srv_sp}"
        assert srv_sp["autoReturn"] is False, f"Server did not persist autoReturn: {srv_sp}"
        assert srv_sp["priority"] == "BATTERY_FIRST", f"Server did not persist priority: {srv_sp}"
        assert srv_sp["outboundSlot0"] == "minecraft:copper_ingotx24", f"Server did not swap port outbound0: {srv_sp}"
        assert srv_sp["storageSlot0"] == "minecraft:gold_blockx8", f"Server did not swap port storage0: {srv_sp}"
        report["checks"]["smart_port_gui_roundtrip"] = {
            "clientSync": {
                "name": sp_after["syncStationName"],
                "autoReturn": sp_after["syncAutoReturn"],
                "priority": sp_after["syncPriority"],
                "storedEu": sp_after["syncStoredEu"],
                "targetPos": sp_after["syncTargetPos"],
                "statusKey": sp_after["syncStatusKey"],
                "swapBtnPresent": sp_after.get("swapBtnPresent"),
            },
            "serverPersisted": srv_sp,
        }
        time.sleep(0.4)
        shot2 = session.wait_screenshot(f"lc2_{mc_version}_02_smart_port_gui.png")
        shutil.copy2(shot2, screenshot_out / shot2.name)
        report["screenshots"].append(str(screenshot_out / shot2.name))
        print(f"[{mc_version}] SmartPortScreen verified (incl. live TCP Swap!) & captured {shot2.name}", flush=True)

        # Toggle back & close SmartPortScreen
        session.act("gui_click", button="toggle_autoreturn")
        session.act("gui_click", button="toggle_priority")
        session.act("close_screen")
        session.wait_until("smart_port_closed", lambda s: s.get("screen") == "", timeout=15)

        # 5. Real CargoStationScreen GUI Client <-> Server Test
        session.server_cmd("lc2field prep_cargo_station_gui")
        time.sleep(1.2)
        hc = site["hillCargo"]
        session.act("right_click_block", x=hc["x"], y=hc["y"], z=hc["z"], face="south")
        cs_open = session.wait_until(
            "cargo_station_screen_open",
            lambda s: s.get("screen") == "CargoStationScreen" and s.get("syncPresent") is True,
            timeout=25,
        )
        assert cs_open["syncRfRange"] >= 256, f"Expected RF range >= 256: {cs_open}"
        assert cs_open["syncStoredEu"] > 5000, f"Expected synced EU > 5000: {cs_open}"
        assert cs_open["syncVehicleReady"] is True, f"Expected vehicleReady=true: {cs_open}"
        assert cs_open["syncReadyVehicleCount"] >= 4, f"Expected >= 4 ready drones: {cs_open}"
        assert cs_open["syncTargetCount"] >= 1, f"Expected >= 1 compatible cargo station target: {cs_open}"

        srv_cs_before_swap = session.server_state()["hillCargo"]
        assert srv_cs_before_swap["outboundSlot0"] == "minecraft:iron_blockx12", f"Unexpected initial cargo outbound0: {srv_cs_before_swap}"
        assert srv_cs_before_swap["storageSlot0"] == "minecraft:diamond_blockx10", f"Unexpected initial cargo storage0: {srv_cs_before_swap}"

        session.act("gui_click", button="toggle_priority")
        session.wait_until("cargo_priority_toggled", lambda s: s.get("syncPriority") == "BATTERY_FIRST", timeout=15)
        srv_cs = session.server_state()["hillCargo"]
        assert srv_cs["priority"] == "BATTERY_FIRST", f"Server did not persist cargo priority: {srv_cs}"
        session.act("gui_click", button="toggle_priority")
        session.wait_until("cargo_priority_restored", lambda s: s.get("syncPriority") == "EU_FIRST", timeout=15)

        # Click live Swap button over TCP on CargoStationScreen and verify server atomically swapped zones!
        session.act("gui_click", button="swap")
        cs_swapped = session.wait_until(
            "cargo_station_swapped",
            lambda s: s.get("syncStatusKey") == "status.trera.swapped",
            timeout=15,
        )
        srv_cs_swapped = session.server_state()["hillCargo"]
        assert srv_cs_swapped["outboundSlot0"] == "minecraft:diamond_blockx10", f"Server did not swap cargo outbound0: {srv_cs_swapped}"
        assert srv_cs_swapped["storageSlot0"] == "minecraft:iron_blockx12", f"Server did not swap cargo storage0: {srv_cs_swapped}"
        report["checks"]["cargo_station_gui_roundtrip"] = {
            "rfRange": cs_swapped["syncRfRange"],
            "storedEu": cs_swapped["syncStoredEu"],
            "readyVehicleCount": cs_swapped["syncReadyVehicleCount"],
            "targetCount": cs_swapped["syncTargetCount"],
            "statusKey": cs_swapped["syncStatusKey"],
            "serverReadyDrones": srv_cs_swapped["readyDrones"],
            "serverSwappedOutbound0": srv_cs_swapped["outboundSlot0"],
            "serverSwappedStorage0": srv_cs_swapped["storageSlot0"],
        }
        time.sleep(0.4)
        shot3 = session.wait_screenshot(f"lc2_{mc_version}_03_cargo_station_gui.png")
        shutil.copy2(shot3, screenshot_out / shot3.name)
        report["screenshots"].append(str(screenshot_out / shot3.name))
        print(f"[{mc_version}] CargoStationScreen verified (incl. live TCP Swap!) & captured {shot3.name}", flush=True)

        session.act("close_screen")
        session.wait_until("cargo_station_closed", lambda s: s.get("screen") == "", timeout=15)

        # 6a. Real Player Boat Riding Test — Blocked while moving autonomously!
        session.server_cmd("lc2field launch_boat_unmanned")
        moving_state = session.wait_until(
            "unmanned_boat_moving",
            lambda s: s.get("boatNearby") is True and s.get("boatAutonomous") is True,
            timeout=25,
        )
        before_overlay_count = moving_state["overlayCount"]
        session.act("interact_boat")
        blocked_state = session.wait_until(
            "active_transit_boarding_blocked_actionbar",
            lambda s: s.get("overlayCount", 0) > before_overlay_count,
            timeout=15,
        )
        assert blocked_state.get("vehicle", "") == "", f"Player should NOT mount moving boat! {blocked_state}"
        report["checks"]["boat_boarding_blocked_in_transit"] = {
            "actionbar": blocked_state["lastOverlay"],
            "vehicle": blocked_state["vehicle"],
        }
        print(f"[{mc_version}] Active-transit boarding blocked OK: '{blocked_state['lastOverlay']}'", flush=True)

        # 6b. Board docked boat BEFORE launch & physically ride across the natural river/bay!
        session.server_cmd("lc2field prep_boat_ride")
        session.wait_until(
            "docked_boat_ready_for_boarding",
            lambda s: s.get("boatNearby") is True and s.get("boatAutonomous") is False and s.get("vehicle", "") == "",
            timeout=25,
        )
        time.sleep(0.4)
        session.act("interact_boat")
        mounted = session.wait_until(
            "player_mounted_docked_boat",
            lambda s: s.get("vehicle") == "trera:electric_cargo_boat",
            timeout=20,
        )
        start_x, start_z = mounted["playerX"], mounted["playerZ"]
        session.act("camera", mode="third_back")
        session.act("clear_hud")
        session.act("hide_gui", value=True)
        time.sleep(0.3)

        # Trigger real redstone launch!
        session.server_cmd("lc2field trigger_boat_redstone")
        mid_ride = session.wait_until(
            "player_riding_boat_mid_river",
            lambda s: s.get("vehicle") == "trera:electric_cargo_boat"
            and math.hypot(s["playerX"] - start_x, s["playerZ"] - start_z) >= 24.0,
            timeout=45,
            interval=0.15,
        )
        # Level the 3rd-person camera angle along the river toward Base B for a scenic riding shot
        berth_b = site["berthB"]
        dx_b = (berth_b["x"] + 0.5) - mid_ride["playerX"]
        dz_b = (berth_b["z"] + 0.5) - mid_ride["playerZ"]
        river_yaw = math.degrees(math.atan2(-dx_b, dz_b))
        session.act("look", yaw=river_yaw, pitch=16.0)
        time.sleep(0.15)
        shot4 = session.wait_screenshot(f"lc2_{mc_version}_04_boat_riding_river.png")
        shutil.copy2(shot4, screenshot_out / shot4.name)
        report["screenshots"].append(str(screenshot_out / shot4.name))
        print(f"[{mc_version}] Captured mid-river ride shot {shot4.name} (dist={math.hypot(mid_ride['playerX'] - start_x, mid_ride['playerZ'] - start_z):.1f}m)", flush=True)

        # Wait for arrival at Refining Base B!
        arrived = session.wait_until(
            "player_arrived_base_b_in_boat",
            lambda s: s.get("vehicle") == "trera:electric_cargo_boat"
            and math.hypot(s["playerX"] - (berth_b["x"] + 0.5), s["playerZ"] - (berth_b["z"] + 0.5)) <= 5.5
            and s.get("boatAutonomous") is False,
            timeout=60,
            interval=0.2,
        )
        traveled = math.hypot(arrived["playerX"] - start_x, arrived["playerZ"] - start_z)
        report["checks"]["boat_prelaunch_live_ride"] = {
            "start": [round(start_x, 2), round(start_z, 2)],
            "end": [round(arrived["playerX"], 2), round(arrived["playerZ"], 2)],
            "euclideanDistance": round(traveled, 2),
            "stillMounted": arrived["vehicle"] == "trera:electric_cargo_boat",
        }
        print(f"[{mc_version}] Real player rode boat {traveled:.1f}m across natural river to Base B!", flush=True)

        # 7. Scene 5: Cargo Station roof with parked drones + flying Standard & Heavy Drones over natural terrain
        session.act("camera", mode="first")
        session.act("clear_hud")
        session.act("hide_gui", value=True)
        # Pre-load hillside chunks cleanly first, then launch drones & snap right as they climb into frame!
        session.server_cmd("lc2field prep_cargo_station_gui")
        time.sleep(1.0)
        session.server_cmd("lc2field scene_drones")
        drones_state = session.wait_until(
            "flying_drones_visible",
            lambda s: s.get("nearbyFlyingDrones", 0) >= 2,
            timeout=20,
            interval=0.08,
        )
        time.sleep(0.18)
        shot5 = session.wait_screenshot(f"lc2_{mc_version}_05_drones_airfield.png")
        shutil.copy2(shot5, screenshot_out / shot5.name)
        report["screenshots"].append(str(screenshot_out / shot5.name))
        report["checks"]["drones_roof_and_flight"] = {"nearbyFlyingDrones": drones_state["nearbyFlyingDrones"]}
        print(f"[{mc_version}] Captured {shot5.name} (flyingDrones={drones_state['nearbyFlyingDrones']})", flush=True)

        # 8. Scene 6: Main Refining Base B (Smart Port + Cargo Station + Macerator + Electric Furnace + Steel Box)
        session.server_cmd("lc2field scene_refinery")
        time.sleep(2.2)
        session.act("clear_hud")
        session.act("hide_gui", value=True)
        shot6 = session.wait_screenshot(f"lc2_{mc_version}_06_refining_base.png")
        shutil.copy2(shot6, screenshot_out / shot6.name)
        report["screenshots"].append(str(screenshot_out / shot6.name))
        srv_final = session.server_state()
        report["checks"]["refining_base_state"] = {"steelBox": srv_final.get("steelBox", {})}
        print(f"[{mc_version}] Captured {shot6.name}", flush=True)

        report["status"] = "passed"
    finally:
        client_exit, server_exit = session.stop()
        report["clientExit"] = client_exit
        report["serverExit"] = server_exit
        write_json_atomic(run_dir / "client-server-report.json", report)
        print(f"[{mc_version}] Shutdown complete (clientExit={client_exit}, serverExit={server_exit})", flush=True)

    scan_log_for_errors(run_dir / "server.log", "server")
    scan_log_for_errors(run_dir / "client.log", "client")
    if client_exit != 0 or server_exit != 0:
        raise RuntimeError(f"Non-zero exit status: client={client_exit}, server={server_exit}")
    print(f"[{mc_version}] ALL LIVE CLIENT-SERVER CHECKS PASSED!", flush=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mc", required=True, choices=["1.21.1", "26.1.2", "26.2"])
    parser.add_argument("--screenshots-dir", required=True, type=Path)
    args = parser.parse_args()
    run_live_suite(args.mc, args.screenshots_dir.resolve())


if __name__ == "__main__":
    main()
