"""Fetch public issue #89/#93 fixtures, preserving upstream bytes and verifying SHA-256."""
import hashlib
import json
from pathlib import Path
import sys
import urllib.request

root = Path(sys.argv[1] if len(sys.argv) > 1 else "build/contributed-fixtures")
for item in json.loads(Path(__file__).with_name("contributed.json").read_text()):
    target = root / item["path"]
    if target.exists() and hashlib.sha256(target.read_bytes()).hexdigest() == item["sha256"]:
        continue
    with urllib.request.urlopen(item["url"], timeout=90) as response:
        content = response.read()
    if hashlib.sha256(content).hexdigest() != item["sha256"]:
        raise ValueError(f"Fixture hash mismatch: {item['path']}")
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(content)
    print(f"Verified {item['path']}")
