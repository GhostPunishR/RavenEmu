#!/usr/bin/env python3
"""Fails when the dev APK's native code is built without optimisation.

The CI builds Gradle's debug variant, whose native code the NDK compiles at
-O0; android/src/main/cpp/CMakeLists.txt adds -O2 so that the cores run at
full speed on a phone. This reads the compile databases Gradle leaves under
android/.cxx and checks that every command's last -O flag optimises, and that
the frontend, rcheevos and every RavenCore core are in it.
"""

from pathlib import Path
import json
import re
import shlex
import sys

OPTIMISED = {"-O2", "-O3"}
REQUIRED_TARGETS = {
    "raven_android", "raven_frontend", "raven_achievements", "raven_rcheevos",
    "gb_raven_core", "gbc_raven_core", "gba_raven_core",
    "ravenemu_gb_libretro", "ravenemu_gbc_libretro", "ravenemu_gba_libretro",
}
TARGET = re.compile(r"CMakeFiles/([^/]+)\.dir/")


def level(command):
    """The optimisation the compiler applies: its last -O flag, None without one."""
    flags = [argument for argument in command if re.fullmatch(r"-O\S*", argument)]
    return flags[-1] if flags else None


def problems(entries):
    """What is wrong with a compile database, as sentences; empty when fine."""
    found, slow = set(), []
    for entry in entries:
        command = entry.get("arguments") or shlex.split(entry.get("command", ""))
        match = TARGET.search(entry.get("output", "") or " ".join(command))
        target = match.group(1) if match else "?"
        found.add(target)
        if level(command) not in OPTIMISED:
            slow.append(f"{target}: {entry.get('file')} ({level(command) or 'no -O'})")
    report = [f"not optimised: {line}" for line in slow]
    missing = REQUIRED_TARGETS - found
    if missing:
        report.append(f"missing from the build: {', '.join(sorted(missing))}")
    return report


def databases(root):
    return sorted(path for path in Path(root).glob("android/.cxx/**/arm64-v8a/compile_commands.json"))


def main(arguments):
    paths = [Path(argument) for argument in arguments] or databases(".")
    if not paths:
        print("No compile_commands.json under android/.cxx: was the APK built?", file=sys.stderr)
        return 1
    failed = False
    for path in paths:
        report = problems(json.loads(path.read_text(encoding="utf-8")))
        for line in report:
            print(f"{path}: {line}", file=sys.stderr)
        failed = failed or bool(report)
        if not report:
            print(f"{path}: native code optimised")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
