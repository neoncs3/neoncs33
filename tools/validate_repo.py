#!/usr/bin/env python3
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []
warnings: list[str] = []

settings = ROOT / "settings.gradle.kts"
if not settings.exists():
    errors.append("settings.gradle.kts bulunamadı.")

modules = []
for child in ROOT.iterdir():
    if child.is_dir() and (child / "build.gradle.kts").exists():
        modules.append(child)

if not modules:
    errors.append("Hiçbir CloudStream modülü bulunamadı.")

core = ROOT / "NeonCore" / "src" / "main" / "kotlin" / "com" / "neoncs3" / "NeonCore.kt"
if not core.exists():
    errors.append("NeonCore.kt eksik.")

for module in modules:
    kt_files = list((module / "src" / "main" / "kotlin").rglob("*.kt")) if (module / "src").exists() else []
    if not kt_files:
        errors.append(f"{module.name}: Kotlin kaynak dosyası yok.")
        continue

    providers = []
    for path in kt_files:
        text = path.read_text(encoding="utf-8", errors="replace")
        if re.search(r"class\s+\w+\s*:\s*(?:NeonMainAPI|MainAPI)\s*\(\s*\)", text):
            providers.append(path)

    if not providers:
        errors.append(f"{module.name}: MainAPI/NeonMainAPI sağlayıcısı bulunamadı.")

    build_text = (module / "build.gradle.kts").read_text(encoding="utf-8", errors="replace")
    if 'status = ' not in build_text and 'status=' not in build_text:
        warnings.append(f"{module.name}: build.gradle.kts içinde status görünmüyor.")

repo_json = ROOT / "repo.json"
if repo_json.exists():
    try:
        repo_data = json.loads(repo_json.read_text(encoding="utf-8"))
        plugin_lists = repo_data.get("pluginLists", [])
        expected = "https://raw.githubusercontent.com/neoncs3/neoncs33/builds/plugins.json"
        if expected not in plugin_lists:
            errors.append("repo.json builds/plugins.json adresini göstermiyor.")
    except json.JSONDecodeError as exc:
        errors.append(f"repo.json geçersiz JSON: {exc}")

if errors:
    print("REPO VALIDATION: FAILED")
    for item in errors:
        print(f"ERROR: {item}")
    for item in warnings:
        print(f"WARNING: {item}")
    sys.exit(1)

print("REPO VALIDATION: PASSED")
for item in warnings:
    print(f"WARNING: {item}")
