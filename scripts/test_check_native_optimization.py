#!/usr/bin/env python3
"""Tests of the native optimisation check; no Android tools required."""

import json
from pathlib import Path
import tempfile
import unittest

from check_native_optimization import REQUIRED_TARGETS, level, main, problems


def entry(target, flags, arguments=False):
    command = ["clang", *flags, "-o", f"CMakeFiles/{target}.dir/src/{target}.c.o", "-c", f"src/{target}.c"]
    if arguments:
        return {"directory": "/b", "file": f"src/{target}.c", "arguments": command}
    return {"directory": "/b", "file": f"src/{target}.c", "command": " ".join(command),
            "output": f"CMakeFiles/{target}.dir/src/{target}.c.o"}


class NativeOptimizationTest(unittest.TestCase):
    def test_the_last_level_is_the_one_applied(self):
        self.assertEqual(level(["clang", "-O0", "-g", "-O2"]), "-O2")
        self.assertEqual(level(["clang", "-O2", "-O0"]), "-O0")
        self.assertIsNone(level(["clang", "-g"]))

    def test_the_ndk_debug_flags_alone_are_refused(self):
        entries = [entry(target, ["-g", "-O0", "-fno-limit-debug-info"]) for target in REQUIRED_TARGETS]
        report = problems(entries)
        self.assertEqual(len(report), len(REQUIRED_TARGETS))
        self.assertTrue(all(line.startswith("not optimised: ") and "(-O0)" in line for line in report))

    def test_debug_flags_overridden_by_o2_pass(self):
        entries = [entry(target, ["-g", "-O0", "-O2"], arguments=index % 2 == 0)
                   for index, target in enumerate(sorted(REQUIRED_TARGETS))]
        self.assertEqual(problems(entries), [])

    def test_one_slow_file_or_a_missing_core_fails(self):
        entries = [entry(target, ["-O0", "-O2"]) for target in REQUIRED_TARGETS if target != "gba_raven_core"]
        entries.append(entry("raven_frontend", ["-g"]))
        report = problems(entries)
        self.assertIn("not optimised: raven_frontend: src/raven_frontend.c (no -O)", report)
        self.assertIn("missing from the build: gba_raven_core", report)

    def test_an_absent_or_slow_database_fails_and_a_good_one_passes(self):
        with tempfile.TemporaryDirectory() as folder:
            good, slow = Path(folder) / "good.json", Path(folder) / "slow.json"
            good.write_text(json.dumps([entry(target, ["-O0", "-O2"]) for target in REQUIRED_TARGETS]))
            slow.write_text(json.dumps([entry(target, ["-O0"]) for target in REQUIRED_TARGETS]))
            self.assertEqual(main([str(good)]), 0)
            self.assertEqual(main([str(good), str(slow)]), 1)


if __name__ == "__main__":
    unittest.main()
