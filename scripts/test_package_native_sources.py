#!/usr/bin/env python3
"""Deterministic source-package boundary tests; no Android tools required."""

import hashlib
import json
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest

from package_native_sources import SourceError, build_bundle


def git(root, *arguments):
    return subprocess.check_output(["git", "-C", str(root), *arguments], stderr=subprocess.PIPE)


def commit(root):
    git(root, "add", ".")
    git(root, "-c", "user.name=Source test", "-c", "user.email=source-test@example.invalid",
        "commit", "-qm", "fixture")
    return git(root, "rev-parse", "HEAD").decode().strip()


class SourceBundleTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.repository, self.core, self.dependencies = [self.root / name for name in ("app", "core", "dependencies")]
        for repo in (self.repository, self.core):
            repo.mkdir()
            git(repo, "init", "-q")
        (self.core / "core.c").write_text("original core\n")
        self.pin = commit(self.core)
        (self.repository / "frontend").mkdir()
        (self.repository / "frontend/CMakeLists.txt").write_text(f'set(RAVEN_CORE_REVISION "{self.pin}")\n')
        (self.repository / "app.c").write_text("original app\n")
        (self.repository / "LICENSE").write_text("fixture licence\n")
        self.revision = commit(self.repository)
        self.dependencies.mkdir()
        (self.dependencies / "DEPENDENCIES.tsv").write_text(
            "coordinate\tsources\tpom\truntime\nexample:library:1.0\tlib-sources.jar\tlib.pom\tlib.aar\n")
        for name in ("lib-sources.jar", "lib.pom", "lib.aar"):
            (self.dependencies / name).write_bytes(name.encode())
        self.output = self.root / "sources.tar.gz"

    def build(self, **overrides):
        arguments = dict(repository=self.repository, core_root=self.core,
                         dependencies_dir=self.dependencies, output=self.output)
        arguments.update(overrides)
        return build_bundle(**arguments)

    def entries(self):
        with tarfile.open(self.output) as archive:
            return {entry.name: archive.extractfile(entry).read() for entry in archive}

    def test_packages_actual_modified_sources_but_never_untracked_signing_material(self):
        (self.repository / "app.c").write_text("modified app\n")
        (self.core / "core.c").write_text("modified core\n")
        (self.repository / "release.p12").write_text("private fixture, must not be read\n")
        (self.core / "untracked.txt").write_text("not a source input\n")
        manifest = self.build()
        entries = self.entries()
        self.assertEqual(b"modified app\n", entries["RavenEmu/app.c"])
        self.assertEqual(b"modified core\n", entries["RavenCore/core.c"])
        self.assertFalse(any("release.p12" in path or "untracked.txt" in path for path in entries))
        self.assertEqual(self.revision, manifest["repository"]["revision"])
        self.assertTrue(manifest["repository"]["modified"])
        self.assertTrue(manifest["core"]["modified"])
        self.assertEqual(manifest, json.loads(entries["SOURCE-MANIFEST.json"]))
        for record in manifest["files"]:
            self.assertEqual(hashlib.sha256(entries[record["path"]]).hexdigest(), record["sha256"])

    def test_records_apk_and_dependency_publications_and_is_reproducible(self):
        apk = self.root / "test.apk"
        apk.write_bytes(b"apk fixture")
        manifest = self.build(apk=apk)
        first = self.output.read_bytes()
        self.build(apk=apk)
        self.assertEqual(first, self.output.read_bytes())
        self.assertFalse(manifest["repository"]["modified"])
        self.assertEqual(hashlib.sha256(b"apk fixture").hexdigest(), manifest["apk"]["sha256"])
        self.assertEqual("example:library:1.0", manifest["dependencies"][0]["coordinate"])
        self.assertIn("dependencies/lib.aar", self.entries())
        instructions = self.entries()["README-SOURCES.txt"].decode()
        self.assertIn("-PravenSourceRevision=" + self.revision, instructions)
        self.assertIn("-PravenCoreSource=../RavenCore", instructions)
        self.assertNotIn("@SOURCE_", instructions)

    def test_rejects_wrong_pinned_core_before_replacing_an_existing_archive(self):
        self.output.write_bytes(b"previous archive")
        (self.core / "another.c").write_text("new revision\n")
        commit(self.core)
        with self.assertRaisesRegex(SourceError, "does not match"):
            self.build()
        self.assertEqual(b"previous archive", self.output.read_bytes())

    def test_rejects_missing_source_or_runtime_publication(self):
        for name in ("lib-sources.jar", "lib.pom", "lib.aar"):
            original = (self.dependencies / name).read_bytes()
            (self.dependencies / name).unlink()
            with self.subTest(publication=name), self.assertRaises(SourceError):
                self.build()
            (self.dependencies / name).write_bytes(original)
        self.assertFalse(self.output.exists())

    def test_rejects_dependency_path_traversal(self):
        (self.dependencies / "DEPENDENCIES.tsv").write_text(
            "example:library:1.0\t../secret.jar\tlib.pom\tlib.aar\n")
        with self.assertRaisesRegex(SourceError, "Unsafe relative path"):
            self.build()

    def test_rejects_tracked_file_and_parent_directory_symlinks(self):
        external = self.root / "external.c"
        external.write_text("must not be read\n")
        (self.repository / "app.c").unlink()
        (self.repository / "app.c").symlink_to(external)
        with self.assertRaisesRegex(SourceError, "Symlink"):
            self.build()
        (self.repository / "app.c").unlink()
        (self.repository / "app.c").write_text("restored app\n")
        (self.repository / "frontend/CMakeLists.txt").unlink()
        (self.repository / "frontend").rmdir()
        directory = self.root / "external-directory"
        directory.mkdir()
        (directory / "CMakeLists.txt").write_text(f'set(RAVEN_CORE_REVISION "{self.pin}")\n')
        (self.repository / "frontend").symlink_to(directory, target_is_directory=True)
        with self.assertRaisesRegex(SourceError, "Symlink"):
            self.build()

    def test_rejects_dependency_symlink_and_ignores_unlisted_files(self):
        (self.dependencies / "password.txt").write_text("not listed\n")
        self.build()
        self.assertNotIn("dependencies/password.txt", self.entries())
        target = self.root / "external.jar"
        target.write_bytes(b"external")
        (self.dependencies / "lib-sources.jar").unlink()
        (self.dependencies / "lib-sources.jar").symlink_to(target)
        with self.assertRaisesRegex(SourceError, "Symlink"):
            self.build()

    def test_finds_actual_arm64_checkout_and_omits_tracked_build_outputs(self):
        cache = self.repository / "androidNative/.cxx/Debug/test/arm64-v8a/CMakeCache.txt"
        cache.parent.mkdir(parents=True)
        cache.write_text(f"RavenCore_SOURCE_DIR:STATIC={self.core}\n")
        build = self.repository / "build/generated.c"
        build.parent.mkdir()
        build.write_text("generated file\n")
        commit(self.repository)
        manifest = self.build(core_root=None)
        self.assertEqual(self.pin, manifest["core"]["revision"])
        self.assertNotIn("RavenEmu/build/generated.c", self.entries())
        self.assertFalse(any(".cxx" in name for name in self.entries()))


if __name__ == "__main__":
    unittest.main()
