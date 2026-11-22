#!/usr/bin/env python3
"""RavenEmu's version, for its signed Release (RELEASING.md).

The version is set once, as ravenemuVersion=x.y.z in gradle.properties; the
tag vX.Y.Z that publishes it must equal it, and CHANGELOG.md must hold its
section, "## X.Y.Z - YYYY-MM-DD", whose text becomes the release notes.

  release_version.py                      prints the version
  release_version.py --tag v1.0.0         also checks the tag and the section
  release_version.py --tag v1.0.0 --notes NOTES.md --repository-slug owner/name
                                          also writes the release notes
"""
import argparse
import re
from pathlib import Path

# As android/build.gradle.kts reads it: minor and patch below 100.
VERSION = re.compile(r"(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]?)\.(0|[1-9][0-9]?)")
SLUG = re.compile(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
# GitHub refuses a release body over 125 000 characters.
MAX_NOTES = 120_000
# A Markdown link to a file of the repository, as CHANGELOG.md writes them.
RELATIVE_LINK = re.compile(r"\]\((?!(?:https?|mailto):|#)([^)\s]+)\)")

FOOTER = """
---

**Installation** : `RavenEmu-{version}.apk`, pour Android 8.0 ou plus sur un téléphone ARM64 (`arm64-v8a`). Identifiant `com.ravenemu`, signé par la clé de RavenEmu. Il s'installe à côté de la bêta `com.ravenemu.preview` et de l'APK de développement `com.ravenemu.dev`, sans lire leurs données : exportez vos sauvegardes pour les reprendre.

**Sources correspondantes** (AGPLv3) : `RavenEmu-{version}-sources.tar.gz` contient les sources de RavenEmu, de RavenCore, de rcheevos et des bibliothèques de cet APK.

**Empreintes** : `SHA256SUMS.txt`, à vérifier avec `sha256sum -c SHA256SUMS.txt`.
"""


class ReleaseError(Exception):
    pass


def read_version(repository):
    lines = (Path(repository) / "gradle.properties").read_text(encoding="utf-8").splitlines()
    values = [line.split("=", 1)[1].strip() for line in lines if line.startswith("ravenemuVersion=")]
    if len(values) != 1:
        raise ReleaseError("gradle.properties must set ravenemuVersion exactly once")
    if not VERSION.fullmatch(values[0]):
        raise ReleaseError(f"ravenemuVersion must be x.y.z, minor and patch below 100: {values[0]!r}")
    return values[0]


def check_tag(version, tag):
    if tag != "v" + version:
        raise ReleaseError(f"The tag {tag!r} does not match ravenemuVersion {version}: expected v{version}")


def changelog_section(repository, version):
    lines = (Path(repository) / "CHANGELOG.md").read_text(encoding="utf-8").splitlines()
    heading = re.compile(r"## " + re.escape(version) + r" - \d{4}-\d{2}-\d{2}")
    starts = [index for index, line in enumerate(lines) if heading.fullmatch(line.rstrip())]
    if len(starts) != 1:
        raise ReleaseError(f"CHANGELOG.md needs exactly one section '## {version} - YYYY-MM-DD'")
    body = []
    for line in lines[starts[0] + 1:]:
        if line.startswith("## "):
            break
        body.append(line)
    text = "\n".join(body).strip()
    if not text:
        raise ReleaseError(f"The CHANGELOG.md section of {version} is empty")
    return text


def release_notes(repository, version, tag, slug):
    """The section, its links to the repository's files fixed at the tag."""
    if not SLUG.fullmatch(slug or ""):
        raise ReleaseError(f"Invalid repository slug: {slug!r}")
    base = f"https://github.com/{slug}/blob/{tag}/"
    text = RELATIVE_LINK.sub(lambda link: "](" + base + link.group(1).removeprefix("./") + ")",
                             changelog_section(repository, version))
    notes = text + "\n" + FOOTER.format(version=version)
    if len(notes) > MAX_NOTES:
        raise ReleaseError(f"The release notes of {version} exceed {MAX_NOTES} characters: shorten its section")
    return notes


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--repository", type=Path, default=Path.cwd())
    parser.add_argument("--tag")
    parser.add_argument("--notes", type=Path)
    parser.add_argument("--repository-slug")
    arguments = parser.parse_args()
    if arguments.notes and not arguments.tag:
        parser.error("--notes needs --tag")
    try:
        version = read_version(arguments.repository)
        if arguments.tag is not None:
            check_tag(version, arguments.tag)
            changelog_section(arguments.repository, version)
        if arguments.notes:
            notes = release_notes(arguments.repository, version, arguments.tag, arguments.repository_slug)
            arguments.notes.write_text(notes, encoding="utf-8")
    except (ReleaseError, OSError, UnicodeError) as error:
        parser.exit(1, f"Release version check failed: {error}\n")
    print(version)


if __name__ == "__main__":
    main()
