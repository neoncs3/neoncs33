#!/usr/bin/env python3
from __future__ import annotations

import json
import re
import shutil
import subprocess
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
    "tools/NeonPlaybackSmokeTest.kt",
    "tools/neon_playback_runner.sh",
    "tools/format_playback_report.py",
    "tools/auto_plugin_generator.py",
    ".github/workflows/OtomatikEklentiOlustur.yml",
    ".github/workflows/Saglik.yml",
    ".github/workflows/Oynatma.yml",
    ".github/workflows/HataRaporu.yml",
]
for item in required:
    if not (ROOT / item).exists():
        errors.append(f"Eksik dosya: {item}")

# Reject shell syntax errors before emulator playback CI is ever launched.
playback_runner = ROOT / "tools/neon_playback_runner.sh"
if playback_runner.exists() and shutil.which("bash"):
    check = subprocess.run(
        ["bash", "-n", str(playback_runner)],
        capture_output=True,
        text=True,
        check=False,
    )
    if check.returncode != 0:
        details = (check.stderr or check.stdout or "Bash syntax check failed").strip()
        errors.append(f"tools/neon_playback_runner.sh sözdizimi hatası: {details}")

# Guard the instrumentation-test tuple access that broke the last emulator run.
playback_test = ROOT / "tools/NeonPlaybackSmokeTest.kt"
if playback_test.exists():
    playback_text = playback_test.read_text(encoding="utf-8", errors="replace")
    if 'candidates.joinToString(" | ") { it.first.name }' not in playback_text:
        errors.append("Oynatma smoke testindeki SearchResponse/sorgu ikilisi güvenli okunmuyor.")
    if "generatedSampleQueries(provider)" not in playback_text or "neonSmokeTestQueries" not in playback_text:
        errors.append("Otomatik eklentiler için siteden bulunan gerçek başlıklar oynatma testine aktarılmıyor.")
    for token in ("safeUrlForLog(", "safeDiagnostic(", "JSONArray()", "JSONObject()", "attemptDiagnostics"):
        if token not in playback_text:
            errors.append(f"Oynatma tanılama güvenlik/ayrıntı özelliği eksik: {token}")

generator = ROOT / "tools/auto_plugin_generator.py"
if generator.exists():
    generator_text = generator.read_text(encoding="utf-8", errors="replace")
    for token in (
        "def analyse_site(", "def render_kotlin(", "neonResolveLinkCandidates",
        "extract_media_candidates(", "probe_media_urls(", "media_endpoint_reachable",
        "HTML analiz kalite puanı", "--self-test",
        "def is_flag_or_icon_asset(", "private fun isFlagOrIconAsset(value: String): Boolean",
        "extract_catalogue_samples(", "neonSmokeTestQueries", "build_site_snapshot(", "quality_assessment(",
        "private fun matchesQuery(result: SearchResponse, query: String): Boolean",
    ):
        if token not in generator_text:
            errors.append(f"Otomatik eklenti oluşturucu özelliği eksik: {token}")

error_report = ROOT / ".github/workflows/HataRaporu.yml"
if error_report.exists():
    error_report_text = error_report.read_text(encoding="utf-8", errors="replace")
    for token in (
        "workflow_run:",
        "issues: write",
        "gh run view",
        "gh issue create",
        "gh issue comment",
    ):
        if token not in error_report_text:
            errors.append(f"Otomatik hata raporlama özelliği eksik: {token}")

auto_workflow = ROOT / ".github/workflows/OtomatikEklentiOlustur.yml"
if auto_workflow.exists():
    workflow_text = auto_workflow.read_text(encoding="utf-8", errors="replace")
    for token in ("workflow_dispatch:", "site_url:", "tools/auto_plugin_generator.py", "Derleyici.yml"):
        if token not in workflow_text:
            errors.append(f"Otomatik eklenti iş akışı ayarı eksik: {token}")

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
    source_dirs = [source_root]

    # Some aggregate extensions compile provider sources from their original
    # modules. Include those Gradle-declared source roots in repository checks.
    build_file = module / "build.gradle.kts"
    if build_file.exists():
        build_text = build_file.read_text(encoding="utf-8", errors="replace")
        for source_hint in re.findall(
            r'java\.srcDir\(rootProject\.file\("([^"]+)"\)\)',
            build_text,
        ):
            source_dir = ROOT / source_hint
            if source_dir not in source_dirs:
                source_dirs.append(source_dir)

    kotlin_paths = []
    for source_dir in source_dirs:
        if source_dir.exists():
            kotlin_paths.extend(source_dir.rglob("*.kt"))
    kotlin_paths = list(dict.fromkeys(kotlin_paths))
    kotlin_texts = [
        path.read_text(encoding="utf-8", errors="replace")
        for path in kotlin_paths
    ]
    all_kotlin = "\n".join(kotlin_texts)

    # Keep support for older, valid CloudStream modules that still extend MainAPI.
    # New Neon-integrated providers must satisfy the additional NeonCore pipeline checks.
    neon_texts = [
        text for text in kotlin_texts
        if re.search(r"class\s+\w+\s*:\s*NeonMainAPI\s*\(\s*\)", text)
    ]
    legacy_texts = [
        text for text in kotlin_texts
        if re.search(r"class\s+\w+\s*:\s*MainAPI\s*\(\s*\)", text)
    ]

    if neon_texts:
        provider_texts = neon_texts
        neon_integrated = True
    elif legacy_texts:
        provider_texts = legacy_texts
        neon_integrated = False
    else:
        errors.append(f"{module.name}: NeonMainAPI veya klasik MainAPI provider bulunamadı.")
        continue

    if "@CloudstreamPlugin" not in all_kotlin or "registerMainAPI(" not in all_kotlin:
        errors.append(f"{module.name}: CloudStream @CloudstreamPlugin kayıt sınıfı veya registerMainAPI eksik.")

    source = "\n".join(provider_texts)
    checks = {
        "load": r"override\s+suspend\s+fun\s+load\s*\(",
        "search": r"override\s+suspend\s+fun\s+search\s*\(",
        "loadLinks": r"override\s+suspend\s+fun\s+loadLinks\s*\(",
    }
    if neon_integrated:
        checks.update({
            "metadata": r"neonEnrichResponse\s*\(",
            "video": r"neonResolve(?:LinkCandidates|Links)\s*\(",
        })

    for label, pattern in checks.items():
        if not re.search(pattern, source):
            errors.append(f"{module.name}: {label} pipeline eksik.")

    low = source.lower()
    start_links = low.find("loadlinks(")
    if start_links >= 0 and "tmdb" in low[start_links:start_links + 16000]:
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
