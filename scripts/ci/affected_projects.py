#!/usr/bin/env python3
"""Select checks and images from changed paths and Gradle's project dependency graph."""

import argparse
import json
import os
from pathlib import Path
import re
import subprocess


SAFE_PROJECT = re.compile(r":[a-z][a-z0-9-]*\Z")
SAFE_APP_DIR = re.compile(r"apps/([a-z][a-z0-9-]*)\Z")


def select_projects(graph, changed_paths, force_all=False):
    projects = {project["id"]: project for project in graph}
    if any(not SAFE_PROJECT.fullmatch(name) for name in projects):
        raise ValueError("CI supports flat, lowercase Gradle project names only")
    app_ids = {
        name for name, project in projects.items()
        if SAFE_APP_DIR.fullmatch(project["directory"])
    }
    affected = set()
    for path in changed_paths:
        owners = {
            name for name, project in projects.items()
            if path == project["directory"] or path.startswith(project["directory"] + "/")
        }
        if owners:
            affected.update(owners)
        elif path.startswith("docs/") or path in {"README.md", "AGENTS.md"}:
            continue
        else:
            # Root builds, workflow changes, deleted/renamed modules and unknown paths
            # cannot safely be assigned to one application.
            force_all = True
    if force_all:
        affected = set(projects)
    else:
        while True:
            dependents = {
                name for name, project in projects.items()
                if set(project["dependencies"]) & affected
            }
            expanded = affected | dependents
            if expanded == affected:
                break
            affected = expanded
    apps = sorted(projects[name]["directory"].split("/")[1] for name in affected & app_ids)
    return {"apps": apps, "checks": sorted(name + ":check" for name in affected)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--graph", type=Path, required=True)
    parser.add_argument("--base", default="")
    parser.add_argument("--output", type=Path, default=Path("build/ci/affected-projects.json"))
    args = parser.parse_args()
    force_all = not args.base or set(args.base) == {"0"}
    paths = []
    if not force_all:
        base_exists = subprocess.run(
            ["git", "cat-file", "-e", args.base + "^{commit}"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        ).returncode == 0
        if base_exists:
            raw = subprocess.check_output(
                ["git", "diff", "--name-only", "--no-renames", "-z", args.base, "HEAD", "--"]
            )
            paths = [path.decode("utf-8") for path in raw.split(b"\0") if path]
        else:
            force_all = True
    result = select_projects(json.loads(args.graph.read_text()), paths, force_all)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a") as output:
            for key, value in result.items():
                output.write(f"{key}={json.dumps(value, separators=(',', ':'))}\n")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
