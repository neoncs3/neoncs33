#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path
from urllib.parse import urlparse

BUILD = Path(sys.argv[1] if len(sys.argv) > 1 else "build")
manifest_path = BUILD / "plugins.json"

if not manifest_path.exists():
    print(f"ERROR: {manifest_path} bulunamadı.")
    sys.exit(1)

data = json.loads(manifest_path.read_text(encoding="utf-8"))
if not isinstance(data, list):
    print("ERROR: plugins.json liste değil.")
    sys.exit(1)

errors = []
seen = set()

for item in data:
    name = item.get("internalName")
    url = item.get("url")
    declared_size = item.get("fileSize")
    declared_hash = item.get("fileHash")

    if not name:
        errors.append("internalName eksik.")
        continue
    if name in seen:
        errors.append(f"Tekrarlanan internalName: {name}")
    seen.add(name)

    if not url:
        errors.append(f"{name}: url eksik.")
        continue

    filename = Path(urlparse(url).path).name
    package = BUILD / filename
    if not package.exists():
        errors.append(f"{name}: paket eksik: {filename}")
        continue

    raw = package.read_bytes()
    actual_hash = "sha256-" + hashlib.sha256(raw).hexdigest()
    if declared_hash != actual_hash:
        errors.append(
            f"{name}: hash uyuşmazlığı: manifest={declared_hash} actual={actual_hash}"
        )

    if declared_size != len(raw):
        errors.append(
            f"{name}: boyut uyuşmazlığı: manifest={declared_size} actual={len(raw)}"
        )

if errors:
    print("PUBLISHED ARTIFACT VALIDATION: FAILED")
    for item in errors:
        print(f"ERROR: {item}")
    sys.exit(1)

print(f"PUBLISHED ARTIFACT VALIDATION: PASSED ({len(data)} plugins)")
