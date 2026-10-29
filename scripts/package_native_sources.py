#!/usr/bin/env python3
"""Package the source inputs accompanying the independent native preview APK.

Only Git-tracked workspace files and explicitly listed Maven publications are
read. The manifest describes what is packaged; it is not a legal certification.
"""

import argparse
import csv
import gzip
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import stat
import subprocess
import tarfile
import tempfile


class SourceError(ValueError):
    """An input cannot safely or accurately be packaged."""


EXCLUDED_DIRECTORIES = {".git", ".gradle", ".cxx", ".aicode", "build",
                        "__pycache__", "node_modules", ".idea"}
MAX_FILE_BYTES = 256 * 1024 * 1024
MAX_TOTAL_BYTES = 2 * 1024 * 1024 * 1024
README = """RavenEmu Native Preview - source inputs

RavenEmu/ contains the tracked workspace sources and build scripts of the
preview application. RavenCore/ contains the tracked sources of the pinned
core checkout actually used by the Android build. Local tracked modifications
are included and identified in SOURCE-MANIFEST.json; untracked files, Git
metadata, caches and build outputs are excluded.

dependencies/ contains the exact source JARs, POMs and runtime AAR/JAR
publications listed in DEPENDENCIES.tsv. The runtime publications retain their
resources and notices. Consult their POMs and upstream repositories for the
dependencies' own build configurations and additional source documentation.
This collection records published inputs; it does not certify the completeness
of every upstream publication or the legality of a future distribution.

To build the preview, use JDK 21, the Android SDK/NDK required by the project,
and run from RavenEmu/:
  ./gradlew :android:assembleDebug \\
    -PravenSourceRevision=@SOURCE_REVISION@ -PravenSourceModified=@SOURCE_MODIFIED@ \\
    -PravenCoreSource=../RavenCore

Gradle resolves its pinned dependencies from the repositories declared by the
project. The APK, when present at packaging time, is identified by SHA-256 in
the manifest. Each archived input is also identified by SHA-256.

RavenEmu remains AGPLv3. Adapted RetroArch portions retain their GPLv3-or-later
notices. Preserve the individual copyright and licence notices in all included
sources and publications. See RavenEmu/LICENSE and the preview's licence assets.
"""


def git(root, *args):
    try:
        return subprocess.check_output(["git", "-C", str(root), *args],
                                       stderr=subprocess.PIPE)
    except subprocess.CalledProcessError as error:
        raise SourceError(f"Git failed in {root}: {error.stderr.decode(errors='replace').strip()}") from error


def safe_relative(name):
    if not name or "\\" in name or "\x00" in name:
        raise SourceError(f"Invalid relative path: {name!r}")
    relative = PurePosixPath(name)
    if relative.is_absolute() or any(p in (".", "..") for p in relative.parts) or ":" in relative.parts[0]:
        raise SourceError(f"Unsafe relative path: {name!r}")
    return relative


def read_regular(root, name):
    relative = safe_relative(name)
    candidate = root
    for part in relative.parts:
        candidate = candidate / part
        if candidate.is_symlink():
            raise SourceError(f"Symlink is not a source input: {candidate}")
    try:
        details = candidate.stat()
        if not stat.S_ISREG(details.st_mode) or details.st_size > MAX_FILE_BYTES:
            raise SourceError(f"Input is not a bounded regular file: {candidate}")
        data = candidate.read_bytes()
        if len(data) > MAX_FILE_BYTES:
            raise SourceError(f"Input exceeded file limit while reading: {candidate}")
        return data, 0o755 if details.st_mode & 0o111 else 0o644
    except OSError as error:
        raise SourceError(f"Cannot read source input {candidate}: {error}") from error


def repository_inputs(root):
    inputs = []
    for raw in git(root, "ls-files", "--cached", "-z").split(b"\0"):
        if not raw:
            continue
        name = raw.decode("utf-8")
        relative = safe_relative(name)
        if any(p in EXCLUDED_DIRECTORIES for p in relative.parts[:-1]):
            continue
        data, mode = read_regular(root, name)
        inputs.append((relative.as_posix(), data, mode))
    return sorted(inputs, key=lambda item: item[0])


def revision(root):
    value = git(root, "rev-parse", "--verify", "HEAD").decode().strip()
    if not re.fullmatch(r"[a-fA-F0-9]{40}", value):
        raise SourceError(f"Unsupported Git revision: {value!r}")
    return value


def modified(root):
    # Includes staged and unstaged tracked changes, never untracked files.
    return bool(git(root, "diff", "--name-only", "HEAD", "--"))


def core_pin(repository):
    data, _ = read_regular(repository, "frontend/CMakeLists.txt")
    match = re.search(rb'set\(RAVEN_CORE_REVISION\s+"([a-fA-F0-9]{40})"\s*\)', data)
    if not match:
        raise SourceError("Cannot find the immutable RavenCore revision in frontend/CMakeLists.txt")
    return match.group(1).decode().lower()


def discover_core(repository, pin):
    caches = repository.glob("android/.cxx/**/arm64-v8a/CMakeCache.txt")
    for cache in sorted(caches, key=lambda path: path.stat().st_mtime, reverse=True):
        if cache.is_symlink():
            raise SourceError(f"Refusing symlink CMake cache: {cache}")
        for line in cache.read_text().splitlines():
            if line.startswith("RavenCore_SOURCE_DIR:STATIC="):
                root = Path(line.split("=", 1)[1]).resolve()
                if revision(root).lower() == pin:
                    return root
    raise SourceError("No built ARM64 RavenCore checkout matches the pinned revision; pass --core-root")


def dependency_inputs(directory):
    manifest, mode = read_regular(directory, "DEPENDENCIES.tsv")
    if len(manifest) > 1024 * 1024:
        raise SourceError("Dependency manifest is too large")
    files = {"DEPENDENCIES.tsv": (manifest, mode)}
    records = []
    rows = csv.reader(io.StringIO(manifest.decode("utf-8")), delimiter="\t")
    for row in rows:
        if not row or all(not value.strip() for value in row) or row[0].startswith("#"):
            continue
        if row[0] == "coordinate":
            continue
        if len(row) != 4 or not re.fullmatch(r"[A-Za-z0-9_.+-]+:[A-Za-z0-9_.+-]+:[A-Za-z0-9_.+-]+", row[0]):
            raise SourceError("Dependency rows require coordinate, source, POM and runtime filenames")
        if len(records) >= 4096:
            raise SourceError("Too many dependency publications")
        coordinate, sources, pom, binary = row
        if not sources.endswith(".jar") or not pom.endswith(".pom") or not binary.endswith((".jar", ".aar")):
            raise SourceError(f"Unexpected dependency publication type: {coordinate}")
        names = [safe_relative(name).as_posix() for name in (sources, pom, binary)]
        for name in names:
            if name not in files:
                files[name] = read_regular(directory, name)
        records.append(dict(coordinate=coordinate, sources=names[0], pom=names[1], runtime=names[2]))
    if not records:
        raise SourceError("Dependency manifest contains no runtime source publications")
    return sorted((name, *value) for name, value in files.items()), records


def build_bundle(repository, core_root=None, dependencies_dir=None, output=None, apk=None):
    repository = Path(repository).resolve()
    pin = core_pin(repository)
    core_root = Path(core_root).resolve() if core_root else discover_core(repository, pin)
    core_revision = revision(core_root)
    if core_revision.lower() != pin:
        raise SourceError("The built RavenCore checkout does not match RAVEN_CORE_REVISION")
    dependencies_dir = Path(dependencies_dir or repository / "android/build/corresponding-sources").resolve()
    output = Path(output or repository / "android/build/outputs/sources/ravenemu-native-sources.tar.gz")
    if output.is_symlink():
        raise SourceError("Output must not be a symlink")
    publications, dependency_records = dependency_inputs(dependencies_dir)
    inputs = [(f"RavenEmu/{name}", data, mode) for name, data, mode in repository_inputs(repository)]
    inputs += [(f"RavenCore/{name}", data, mode) for name, data, mode in repository_inputs(core_root)]
    inputs += [(f"dependencies/{name}", data, mode) for name, data, mode in publications]
    source_revision = revision(repository)
    source_modified = modified(repository)
    instructions = README.replace("@SOURCE_REVISION@", source_revision).replace(
        "@SOURCE_MODIFIED@", str(source_modified).lower())
    inputs.append(("README-SOURCES.txt", instructions.encode(), 0o644))
    if sum(len(data) for _, data, _ in inputs) > MAX_TOTAL_BYTES:
        raise SourceError("Source inputs exceed the archive size limit")
    manifest = {
        "format_version": 1,
        "repository": {"revision": source_revision, "modified": source_modified},
        "core": {"revision": core_revision, "modified": modified(core_root)},
        "dependencies": dependency_records,
        "files": [{"path": name, "sha256": hashlib.sha256(data).hexdigest(), "size": len(data)}
                  for name, data, _ in inputs],
    }
    apk_path = Path(apk) if apk else repository / "android/build/outputs/apk/debug/android-debug.apk"
    if apk is not None or apk_path.exists():
        apk_bytes, _ = read_regular(apk_path.parent.resolve(), apk_path.name)
        manifest["apk"] = {"filename": apk_path.name, "sha256": hashlib.sha256(apk_bytes).hexdigest(), "size": len(apk_bytes)}
    inputs.append(("SOURCE-MANIFEST.json", (json.dumps(manifest, indent=2, ensure_ascii=False) + "\n").encode(), 0o644))
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=output.parent, prefix=".sources-", delete=False) as stream:
            temporary = Path(stream.name)
            with gzip.GzipFile(fileobj=stream, mode="wb", filename="", mtime=0) as compressed:
                with tarfile.open(fileobj=compressed, mode="w") as archive:
                    for name, data, mode in sorted(inputs):
                        entry = tarfile.TarInfo(name)
                        entry.size, entry.mode, entry.mtime = len(data), mode, 0
                        archive.addfile(entry, io.BytesIO(data))
        temporary.replace(output)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=Path, default=Path.cwd())
    parser.add_argument("--core-root", type=Path)
    parser.add_argument("--dependencies-dir", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--apk", type=Path)
    arguments = parser.parse_args()
    try:
        manifest = build_bundle(**vars(arguments))
    except (SourceError, OSError, UnicodeError) as error:
        parser.exit(1, f"Source packaging failed: {error}\n")
    print(f"Packaged {len(manifest['files'])} inputs for {manifest['repository']['revision']}")


if __name__ == "__main__":
    main()
