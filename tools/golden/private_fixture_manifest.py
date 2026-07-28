#!/usr/bin/env python3
"""Create the hash-pinned manifest for the private MED17 fixture corpus."""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
from pathlib import Path


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    root = args.root.resolve()
    revision = subprocess.check_output(
        ["git", "-C", str(root), "rev-parse", "HEAD"],
        text=True,
    ).strip()

    xdfs = []
    for path in sorted((root / "med17" / "Normal XDF").glob("*_normal.xdf")):
        xdfs.append({
            "variant": path.name.split("_", 1)[0],
            "path": path.relative_to(root).as_posix(),
            "sha256": sha256(path),
        })

    binaries = []
    for path in sorted((root / "med17" / "OTS tunes").glob("*/*.bin")):
        if "STOCK" in path.name.upper():
            continue
        xdf_variant = path.parent.name
        # This file is byte-identical to the 404E Stage 1 calibration and was
        # archived under 404J. Pin the compatible definition explicitly rather
        # than trusting the directory name.
        if path.parent.name == "404J" and "Stage-1-" in path.name:
            xdf_variant = "404E"
        binaries.append({
            "variant": path.parent.name,
            "xdfVariant": xdf_variant,
            "path": path.relative_to(root).as_posix(),
            "sha256": sha256(path),
        })

    if len(xdfs) != 7:
        raise SystemExit(f"Expected 7 normal MED17 XDFs, found {len(xdfs)}")
    if len(binaries) != 17:
        raise SystemExit(f"Expected 17 tuned MED17 BINs, found {len(binaries)}")

    document = {
        "schemaVersion": 1,
        "sourceRevision": revision,
        "normalXdfs": xdfs,
        "tunedBinaries": binaries,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(document, indent=2, sort_keys=True) + "\n")


if __name__ == "__main__":
    main()
