#!/usr/bin/env python3
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []

required = [
    "settings.gradle.kts",
    "repo.json",
    "KONTROL.py",
    "NeonCore/domains.json",
    "NeonCore/domain-candidates.json",
    "NeonCore/src/main/kotlin/com/neoncs3/NeonCore.kt",
    "tools/provider_health.py",
    ".github/workflows/Saglik.yml",
]
for item in required:
    if not (ROOT / item).exists():
        errors.append(f"Eksik dosya: {item}")

core = ROOT / "NeonCore/src/main/kotlin/com/neoncs3/NeonCore.kt"
if core.exists():
    text = core.read_text(encoding="utf-8", errors="replace")
    for token in ("neonEnrichResponse", "neonResolveLinks", "NeonMemoryCache", "neonGetHtmlWithFallback"):
        if token not in text:
            errors.append(f"NeonCore özelliği eksik: {token}")

modules = [
    child for child in ROOT.iterdir()
    if child.is_dir()
    and (child / "build.gradle.kts").exists()
    and child.name != "NeonCore"
]

for module in modules:
    source_root = module / "src/main/kotlin"
    texts = []
    if source_root.exists():
        for path in source_root.rglob("*.kt"):
            text = path.read_text(encoding="utf-8", errors="replace")
            if re.search(r"class\s+\w+\s*:\s*NeonMainAPI\s*\(\s*\)", text):
                texts.append(text)

    if not texts:
        errors.append(f"{module.name}: NeonMainAPI provider bulunamadı.")
        continue

    source = "\n".join(texts)
    checks = {
        "load": r"override\s+suspend\s+fun\s+load\s*\(",
        "search": r"override\s+suspend\s+fun\s+search\s*\(",
        "loadLinks": r"override\s+suspend\s+fun\s+loadLinks\s*\(",
        "metadata": r"neonEnrichResponse\s*\(",
        "video": r"neonResolve(?:LinkCandidates|Links)\s*\(",
    }

    for label, pattern in checks.items():
        if not re.search(pattern, source):
            errors.append(f"{module.name}: {label} pipeline eksik.")

    low = source.lower()
    start = low.find("loadlinks(")
    if start >= 0 and "tmdb" in low[start:start + 16000]:
        errors.append(f"{module.name}: loadLinks içinde TMDB kullanımı bulundu.")

try:
    repo_data = json.loads((ROOT / "repo.json").read_text(encoding="utf-8"))
    expected = "https://raw.githubusercontent.com/neoncs3/neoncs33/builds/plugins.json"
    if expected not in repo_data.get("pluginLists", []):
        errors.append("repo.json builds/plugins.json adresini göstermiyor.")
except Exception as exc:
    errors.append(f"repo.json okunamadı: {exc}")

try:
    json.loads((ROOT / "NeonCore/domain-candidates.json").read_text(encoding="utf-8"))
except Exception as exc:
    errors.append(f"domain-candidates.json geçersiz: {exc}")

if errors:
    print("REPO VALIDATION: FAILED")
    for item in errors:
        print(f"ERROR: {item}")
    sys.exit(1)

print("REPO VALIDATION: PASSED")
