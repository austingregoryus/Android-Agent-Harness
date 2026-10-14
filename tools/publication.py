#!/usr/bin/env python3
"""Check or export the selected public source tree. Never copy Git history or ignored runtime data."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
TOP = {"README.md", "STATUS.md", "LICENSE", "CONTRIBUTING.md", "SECURITY.md",
       "THIRD_PARTY_NOTICES.md", ".gitignore", ".gitattributes"}
DIRS = {"android-runtime", "gateway", "protocol", "skills", "tools", "docs", "licenses", ".github"}
BLOCKED = {".git", ".gradle", ".kotlin", ".idea", ".vscode", "build", "bin", "dist", "secrets",
           "logs", "__pycache__", ".venv", ".pytest_cache", "private-notes", "publication", "flashing"}
TEXT_SUFFIXES = {".py", ".kt", ".kts", ".go", ".mod", ".sum", ".xml", ".json", ".yaml", ".yml",
                 ".md", ".txt", ".properties", ".bat", ".pro", ".prop", ".toml"}
BINARY = {"android-runtime/gradle/wrapper/gradle-wrapper.jar",
          "android-runtime/app/src/main/assets/smart-turn-v3.2-cpu.onnx",
          "android-runtime/app/src/main/assets/smartturn_golden.pcm",
          "android-runtime/app/src/test/resources/smartturn_golden.pcm",
          "android-runtime/app/src/test/resources/smartturn_golden.f32"}
RULES = {
    "private key": re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    "provider token": re.compile(r"\b(?:sk-(?:proj-|ant-)?[A-Za-z0-9_-]{25,}|gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,})\b"),
    "private network": re.compile(r"\b(?:192\.168\.\d+\.\d+|10\.\d+\.\d+\.\d+|172\.(?:1[6-9]|2\d|3[01])\.\d+\.\d+)\b"),
    "personal absolute path": re.compile(r"(?i)\b[A-Z]:[\\/](?:Users|Projects)[\\/][\w.-]+"),
}


def selected(root: Path = ROOT) -> list[Path]:
    # Git provides the candidate set, including new non-ignored files. Inspect the working tree,
    # not old index contents, so deleted private documents cannot reappear in the export.
    probe = subprocess.run(["git", "rev-parse", "--show-toplevel"], cwd=root, capture_output=True)
    if probe.returncode == 0 and Path(probe.stdout.decode().strip()).resolve() == root.resolve():
        raw = subprocess.check_output(["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"], cwd=root)
        names = raw.decode().split("\0")
    else:
        # A fresh export has no repository yet, and may sit below an ignored directory in another repo.
        # Do not accidentally inspect its parent's Git index and report an empty tree as clean.
        names = []
        for directory, dirs, files in os.walk(root):
            dirs[:] = [name for name in dirs if name not in BLOCKED]
            names.extend((Path(directory) / name).relative_to(root).as_posix() for name in files)
    result = []
    for name in sorted(set(names)):
        if not name:
            continue
        rel = Path(name)
        if rel.parts[0] not in DIRS and name not in TOP:
            continue
        if any(part in BLOCKED for part in rel.parts):
            continue
        if rel.name.startswith(".env") or rel.name in {"local.properties", "config.local.yaml", "config.yaml"}:
            continue
        if rel.suffix.lower() in {".exe", ".apk", ".aab", ".db", ".sqlite", ".log", ".key", ".pem", ".jks", ".keystore", ".zip"}:
            continue
        path = root / rel
        if path.is_file():
            if path.is_symlink() or not path.resolve().is_relative_to(root.resolve()):
                raise ValueError(f"refusing linked/outside file: {name}")
            result.append(path)
    return result


def check(paths: list[Path], root: Path = ROOT) -> list[str]:
    issues = []
    for path in paths:
        name = path.relative_to(root).as_posix()
        data = path.read_bytes()
        if name in BINARY:
            continue  # independently reviewed assets; exporter records their exact hashes
        if path.suffix not in TEXT_SUFFIXES and path.name not in TOP | {"gradlew", ".gitignore"}:
            issues.append(f"{name}: unreviewed file type")
            continue
        try:
            text = data.decode("utf-8-sig")
        except UnicodeDecodeError:
            issues.append(f"{name}: unexpected binary content")
            continue
        for number, line in enumerate(text.splitlines(), 1):
            for label, pattern in RULES.items():
                if pattern.search(line):
                    issues.append(f"{name}:{number}: {label}")
    return issues


def export(destination: Path, paths: list[Path], root: Path = ROOT) -> None:
    destination = destination.resolve()
    if destination.exists():
        raise ValueError("destination already exists; use a new empty path")
    if destination == root.resolve() or root.resolve().is_relative_to(destination):
        raise ValueError("destination must not contain the source checkout")
    destination.mkdir(parents=True)
    manifest = []
    for source in paths:
        rel = source.relative_to(root)
        target = destination / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
        shutil.copymode(source, target)
        manifest.append({"path": rel.as_posix(), "sha256": hashlib.sha256(target.read_bytes()).hexdigest()})
    (destination / "PUBLICATION_MANIFEST.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"Exported {len(paths)} files; no Git history or credentials were copied.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["check", "export"])
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    paths = selected()
    issues = check(paths)
    for issue in issues:
        print(issue)  # locations/categories only; never print matching secret values
    if issues:
        raise SystemExit(1)
    print(f"Checked {len(paths)} selected public files. No configured pattern matches.")
    if args.command == "export":
        if args.output is None:
            parser.error("export requires --output")
        export(args.output, paths)


if __name__ == "__main__":
    main()
