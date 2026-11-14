#!/usr/bin/env python3
"""Runs the real-time benchmark (src/benchmark) in the test environments and prints what it measured.

    tools/benchmark.py vanilla-opengl performance-bliss
    tools/benchmark.py --device CPU --size medium vanilla-opengl
    tools/benchmark.py --out build/benchmarks/before --table

Each run renders off screen on the real GPU, so nothing appears on the desktop, and leaves its numbers, screenshots and
Java Flight Recording in build/benchmarks. See docs/PERFORMANCE.md.
"""

import argparse
import json
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GRADLE_RUNS = ROOT / "build" / "run" / "benchmark"

# name: (test environment, graphics backend, shader pack, environment variables)
OFF_SCREEN = {"SDL_VIDEO_DRIVER": "offscreen"}
# NVIDIA's Vulkan driver cannot run off screen, so Vulkan is measured on Mesa's, on an Intel integrated GPU.
MESA_VULKAN = {"SDL_VIDEO_DRIVER": "offscreen", "VK_LOADER_DRIVERS_SELECT": "*intel_icd*"}
ENVIRONMENTS = {
    "vanilla-opengl": ("vanilla", "opengl", None, OFF_SCREEN),
    "vanilla-vulkan": ("vanilla", "vulkan", None, MESA_VULKAN),
    "performance": ("performance", "opengl", None, OFF_SCREEN),
    "performance-bliss": ("performance", "opengl", "bliss-shader.zip", OFF_SCREEN),
    "performance-complementary": ("performance", "opengl", "complementary-unbound.zip", OFF_SCREEN),
    "modded": ("modded", "opengl", None, OFF_SCREEN),
    "modded-bliss": ("modded", "opengl", "bliss-shader.zip", OFF_SCREEN),
}


def run(name, args, out):
    env_name, backend, pack, variables = ENVIRONMENTS[name]
    command = [
        "./gradlew", "runBenchmark", "--console=plain", f"-PtestEnv={env_name}",
        f"-Pdiffusicraft.graphicsBackend={backend}", f"-Pdiffusicraft.device={args.device}",
        f"-Pdiffusicraft.benchmark.label={name}", f"-Pdiffusicraft.jfr={out / (name + '.jfr')}",
    ]
    if pack:
        command.append(f"-Pdiffusicraft.shaderPack={pack}")
    for option in ("size", "seed"):
        if getattr(args, option):
            prefix = "diffusicraft.benchmark." if option == "size" else "diffusicraft."
            command.append(f"-P{prefix}{option}={getattr(args, option)}")
    if args.jvmArgs:
        command.append(f"-Pdiffusicraft.jvmArgs={args.jvmArgs}")
    for option in ("flightSeconds", "spawnSeconds", "teleportSeconds", "renderDistance"):
        value = getattr(args, option)
        if value:
            command.append(f"-Pdiffusicraft.benchmark.{option}={value}")
    environment = dict(os.environ, **variables)
    environment.pop("WAYLAND_DISPLAY", None)
    environment.pop("DISPLAY", None)

    run_dir = GRADLE_RUNS / env_name
    log = out / f"{name}.log"
    print(f"{name}: {' '.join(command)}", flush=True)
    started = time.time()
    with open(log, "w") as file:
        code = subprocess.run(command, cwd=ROOT, env=environment, stdout=file, stderr=subprocess.STDOUT).returncode
    print(f"{name}: finished with {code} after {time.time() - started:.0f} s; log in {log}", flush=True)

    for found in run_dir.glob("hs_err_pid*.log"):
        if found.stat().st_mtime >= started:
            print(f"{name}: THE JVM CRASHED: {found}", flush=True)
            shutil.copy(found, out)
            code = code or 1
    report = run_dir / "benchmark" / f"{name}.json"
    if report.exists() and report.stat().st_mtime >= started:
        shutil.copy(report, out)
    else:
        print(f"{name}: wrote no report", flush=True)
        code = code or 1
    for picture in (run_dir / "screenshots").glob(f"{name}-*.png"):
        if picture.stat().st_mtime >= started:
            shutil.copy(picture, out)
    return code


def table(folder):
    rows = []
    for file in sorted(Path(folder).glob("*.json")):
        report = json.loads(file.read_text())
        for phase in report["phases"]:
            frames = phase["frames"]
            ticks = phase["ticks"]
            memory = phase["memory"]
            rows.append([
                report["label"], phase["phase"], f"{frames['fps']:.0f}", f"{frames['p50Ms']:.1f}",
                f"{frames['p99Ms']:.1f}", f"{frames['p999Ms']:.0f}", f"{frames['maxMs']:.0f}",
                str(frames["over50ms"]), str(frames["over100ms"]), f"{ticks['tps']:.1f}", f"{ticks['p50Ms']:.1f}",
                f"{ticks['p99Ms']:.0f}", f"{ticks['maxMs']:.0f}", f"{ticks['gaps']['maxMs']:.0f}",
                f"{memory['collections']}/{memory['collectionMillis']}",
            ])
        print(f"{report['label']}: {report['backend']}, {report['mods']} mods, in the world after "
              f"{report['worldLoadSeconds']:.0f} s, {report['flightBlocks']} + {report['landingBlocks']} blocks flown "
              f"({report['flightBlocksPerSecond']} a second), ground after the teleport in "
              f"{report.get('groundSeconds', -1):.1f} s, sank {report.get('fallBlocks', 0):.1f} blocks meanwhile, "
              f"far terrain caught up in {report.get('farTerrainSeconds', -1):.1f} s, "
              f"left in {report['leaveSeconds']:.1f} s")
    header = ["run", "phase", "fps", "p50", "p99", "p99.9", "max", ">50", ">100", "tps", "mspt50", "mspt99", "msptMax",
              "gapMax", "gc n/ms"]
    widths = [max(len(row[i]) for row in rows + [header]) for i in range(len(header))]
    for row in [header] + rows:
        print("  ".join(cell.ljust(width) for cell, width in zip(row, widths)))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("environments", nargs="*", help=f"from {', '.join(ENVIRONMENTS)}")
    parser.add_argument("--device", default="CUDA", help="what runs the terrain: CUDA, ROCM or CPU")
    parser.add_argument("--size", help="a world size, such as medium; the default size of Create World otherwise")
    parser.add_argument("--seed")
    parser.add_argument("--out", type=Path, default=ROOT / "build" / "benchmarks")
    parser.add_argument("--flightSeconds")
    parser.add_argument("--spawnSeconds")
    parser.add_argument("--teleportSeconds")
    parser.add_argument("--renderDistance")
    parser.add_argument("--jvmArgs", help="more arguments for the game's JVM, such as -XX:+UseZGC")
    parser.add_argument("--table", action="store_true", help="only print what is in --out")
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    args.out = args.out.resolve()

    failed = 0
    if not args.table:
        for name in args.environments:
            if name not in ENVIRONMENTS:
                parser.error(f"unknown environment {name}")
            failed += run(name, args, args.out) != 0
    table(args.out)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
