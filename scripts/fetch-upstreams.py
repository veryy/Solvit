#!/usr/bin/env python3
"""Fetch the pinned source references; does not build or execute upstream code."""
import argparse
import json
from pathlib import Path
import subprocess


def git(directory, *args):
    return subprocess.check_output(["git", "-C", str(directory), *args], text=True).strip()


def main():
    project = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, default=project.parent / "Solvit-reference")
    parser.add_argument("--verify-only", action="store_true")
    options = parser.parse_args()
    references = json.loads((project / "docs/upstream-sources.json").read_text())
    options.directory.mkdir(parents=True, exist_ok=True)
    for source in references["repositories"]:
        target = options.directory / source["name"]
        created = not target.exists() and not options.verify_only
        if created:
            subprocess.run(["git", "clone", "--depth", "1", "--no-checkout", source["url"], str(target)], check=True)
        if not (target / ".git").is_dir():
            raise SystemExit(f"Missing reference checkout: {target}")
        if git(target, "remote", "get-url", "origin") != source["url"]:
            raise SystemExit(f"Unexpected origin: {target}")
        if not created and git(target, "status", "--porcelain"):
            raise SystemExit(f"Refusing to change a dirty reference checkout: {target}")
        if created or git(target, "rev-parse", "HEAD") != source["commit"] or not (target / source["licenseFile"]).is_file():
            if options.verify_only:
                raise SystemExit(f"Reference version or license does not match: {target}")
            subprocess.run(["git", "-C", str(target), "fetch", "--depth", "1", "origin", source["commit"]], check=True)
            subprocess.run(["git", "-C", str(target), "checkout", "--detach", source["commit"]], check=True)
        if not (target / source["licenseFile"]).is_file():
            raise SystemExit(f"Missing upstream license: {target}")
        print(f"{source['name']}: {git(target, 'rev-parse', 'HEAD')} {target}")


if __name__ == "__main__":
    main()
