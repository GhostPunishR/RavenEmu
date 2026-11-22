#!/usr/bin/env python3
"""Tests of release_version.py: the version, the tag and the release notes."""
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import release_version  # noqa: E402

SCRIPT = Path(__file__).resolve().parent / "release_version.py"
CHANGELOG = """# Journal

## Non publié

- Rien encore.

## 1.0.0 - 2026-11-30

### Première version

- Voir [l'architecture](ARCHITECTURE.md) et [la migration](./docs/MIGRATION_NATIVE.md#lot).
- Lien externe [RetroArch](https://www.retroarch.com/) et [ancre](#premiere-version).

## 0.9.0 - 2026-10-01

- Ancienne.
"""


class ReleaseVersionTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        self.write("org.gradle.caching=true\nravenemuVersion=1.0.0\n", CHANGELOG)

    def tearDown(self):
        self.directory.cleanup()

    def write(self, properties, changelog):
        (self.root / "gradle.properties").write_text(properties, encoding="utf-8")
        (self.root / "CHANGELOG.md").write_text(changelog, encoding="utf-8")

    def run_script(self, *arguments):
        return subprocess.run([sys.executable, "-I", str(SCRIPT), "--repository", str(self.root), *arguments],
                              capture_output=True, text=True)

    def test_reads_the_version(self):
        self.assertEqual("1.0.0", release_version.read_version(self.root))
        result = self.run_script()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("1.0.0\n", result.stdout)

    def test_refuses_versions_gradle_would_refuse(self):
        for value in ("1.0", "1.0.0-dev", "01.0.0", "1.100.0", "1.0.100", "v1.0.0", ""):
            self.write(f"ravenemuVersion={value}\n", CHANGELOG)
            with self.assertRaises(release_version.ReleaseError, msg=value):
                release_version.read_version(self.root)
        self.write("ravenemuVersion=1.0.0\nravenemuVersion=1.0.1\n", CHANGELOG)
        with self.assertRaises(release_version.ReleaseError):
            release_version.read_version(self.root)

    def test_the_tag_must_equal_the_version(self):
        self.assertEqual(0, self.run_script("--tag", "v1.0.0").returncode)
        for tag in ("v1.0.1", "1.0.0", "v1.0.0-rc1", "v1.0"):
            result = self.run_script("--tag", tag)
            self.assertEqual(1, result.returncode, tag)
            self.assertIn("does not match", result.stderr)

    def test_the_changelog_needs_a_dated_section(self):
        for changelog in ("## Non publié\n\n- Rien.\n", "## 1.0.0\n\n- Sans date.\n",
                          "## 1.0.0 - 2026-11-30\n\n## 0.9.0 - 2026-10-01\n- Vide.\n"):
            self.write("ravenemuVersion=1.0.0\n", changelog)
            result = self.run_script("--tag", "v1.0.0")
            self.assertEqual(1, result.returncode, changelog)
            self.assertIn("CHANGELOG.md", result.stderr)

    def test_writes_the_section_with_absolute_links(self):
        notes = self.root / "notes.md"
        result = self.run_script("--tag", "v1.0.0", "--notes", str(notes), "--repository-slug", "Owner/Repo")
        self.assertEqual(0, result.returncode, result.stderr)
        text = notes.read_text(encoding="utf-8")
        self.assertTrue(text.startswith("### Première version\n"), text)
        self.assertNotIn("Non publié", text)
        self.assertNotIn("Ancienne", text)
        self.assertIn("[l'architecture](https://github.com/Owner/Repo/blob/v1.0.0/ARCHITECTURE.md)", text)
        self.assertIn("(https://github.com/Owner/Repo/blob/v1.0.0/docs/MIGRATION_NATIVE.md#lot)", text)
        self.assertIn("[RetroArch](https://www.retroarch.com/)", text)
        self.assertIn("[ancre](#premiere-version)", text)
        self.assertIn("`RavenEmu-1.0.0.apk`", text)
        self.assertIn("`RavenEmu-1.0.0-sources.tar.gz`", text)
        self.assertIn("`com.ravenemu`", text)

    def test_refuses_notes_github_would_refuse(self):
        self.write("ravenemuVersion=1.0.0\n", "## 1.0.0 - 2026-11-30\n\n" + "- Ligne.\n" * 20_000)
        notes = self.root / "notes.md"
        result = self.run_script("--tag", "v1.0.0", "--notes", str(notes), "--repository-slug", "Owner/Repo")
        self.assertEqual(1, result.returncode)
        self.assertIn("exceed", result.stderr)
        self.assertFalse(notes.exists())

    def test_notes_need_a_valid_slug_and_a_tag(self):
        notes = self.root / "notes.md"
        result = self.run_script("--tag", "v1.0.0", "--notes", str(notes), "--repository-slug", "a/b; rm")
        self.assertEqual(1, result.returncode)
        self.assertFalse(notes.exists())
        self.assertEqual(2, self.run_script("--notes", str(notes)).returncode)

    def test_the_repository_version_is_valid(self):
        repository = Path(__file__).resolve().parent.parent
        self.assertRegex(release_version.read_version(repository), r"^\d+\.\d+\.\d+$")


if __name__ == "__main__":
    unittest.main()
