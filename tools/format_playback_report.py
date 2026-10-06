#!/usr/bin/env python3
from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
report = ROOT / "neon-playback-report.json"
logcat = ROOT / "playback-status" / "logcat.txt"
out_dir = ROOT / "playback-status"
out_dir.mkdir(parents=True, exist_ok=True)

def parse_logcat() -> dict | None:
    if not logcat.exists():
        return None

    pattern = re.compile(
        r"NEON_PLAYBACK provider=(?P<provider>.+?) "
        r"status=(?P<status>PASS|BLOCKED|FAIL) "
        r"links=(?P<links>\d+) "
        r"positionMs=(?P<position>\d+) "
        r"query=(?P<query>.*?) "
        r"item=(?P<item>.*?) "
        r"error=(?P<error>.*)$"
    )

    results: list[dict] = []
    try:
        lines = logcat.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        return None

    for line in lines:
        match = pattern.search(line)
        if not match:
            continue

        results.append(
            {
                "provider": match.group("provider").strip(),
                "status": match.group("status"),
                "query": None if match.group("query") == "null" else match.group("query"),
                "item": None if match.group("item") == "null" else match.group("item"),
                "links": int(match.group("links")),
                "positionMs": int(match.group("position")),
                "error": None if match.group("error") == "null" else match.group("error").strip(),
            }
        )

    if not results:
        return None

    # Keep the final summary entry for each provider.
    latest = {}
    for item in results:
        latest[item["provider"]] = item

    results = list(latest.values())
    return {
        "results": results,
        "pass": sum(item["status"] == "PASS" for item in results),
        "blocked": sum(item["status"] == "BLOCKED" for item in results),
        "fail": sum(item["status"] == "FAIL" for item in results),
    }

data = None

if report.exists():
    try:
        raw = report.read_text(encoding="utf-8").strip()
        parsed = json.loads(raw) if raw else None
        if isinstance(parsed, dict) and isinstance(parsed.get("results"), list):
            data = parsed
    except (OSError, json.JSONDecodeError):
        data = None

# run-as may be unavailable on GitHub's emulator image. The logcat summary is
# authoritative for this smoke test and avoids losing the real diagnostics.
if data is None:
    data = parse_logcat()

if data is None:
    data = {
        "results": [],
        "pass": 0,
        "blocked": 0,
        "fail": 1,
        "error": "Playback raporu alınamadı ve logcat içinde NEON_PLAYBACK sonucu bulunamadı",
    }

rows = [
    "# 🎬 NeonCS Gerçek Oynatma Durumu",
    "",
    f"**PASS:** {data.get('pass', 0)}  **BLOCKED:** {data.get('blocked', 0)}  **FAIL:** {data.get('fail', 0)}",
    "",
    f"**Hata:** {data.get('error')}" if data.get("error") else "",
    "",
    "| Provider | Durum | Link | Position | Sorgu | İçerik | Hata |",
    "|---|---|---:|---:|---|---|---|",
]

for item in data.get("results", []):
    icon = {"PASS": "🟢", "BLOCKED": "🟡", "FAIL": "🔴"}.get(
        item.get("status"), "⚪"
    )
    rows.append(
        f"| **{item.get('provider', '-')}** | {icon} {item.get('status', '-')} | "
        f"{item.get('links', 0)} | {item.get('positionMs', 0)} ms | "
        f"{item.get('query') or '-'} | {item.get('item') or '-'} | "
        f"{item.get('error') or '-'} |"
    )

(out_dir / "latest.md").write_text("\n".join(rows) + "\n", encoding="utf-8")
(out_dir / "latest.json").write_text(
    json.dumps(data, ensure_ascii=False, indent=2) + "\n",
    encoding="utf-8",
)
