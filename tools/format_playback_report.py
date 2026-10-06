#!/usr/bin/env python3
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
report = ROOT / "neon-playback-report.json"
out_dir = ROOT / "playback-status"
out_dir.mkdir(parents=True, exist_ok=True)

if not report.exists():
    (out_dir / "latest.md").write_text(
        "# 🎬 NeonCS Gerçek Oynatma Durumu\n\nPlayback raporu oluşturulamadı.\n",
        encoding="utf-8",
    )
    raise SystemExit(0)

try:
    raw = report.read_text(encoding="utf-8").strip()
    data = json.loads(raw) if raw else {
        "results": [],
        "pass": 0,
        "blocked": 0,
        "fail": 1,
        "error": "Playback raporu boş veya alınamadı",
    }
except (OSError, json.JSONDecodeError) as exc:
    data = {
        "results": [],
        "pass": 0,
        "blocked": 0,
        "fail": 1,
        "error": f"Playback raporu okunamadı: {exc}",
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
    icon = {"PASS": "🟢", "BLOCKED": "🟡", "FAIL": "🔴"}.get(item.get("status"), "⚪")
    rows.append(
        f"| **{item.get('provider', '-')}** | {icon} {item.get('status', '-') } | "
        f"{item.get('links', 0)} | {item.get('positionMs', 0)} ms | "
        f"{item.get('query') or '-'} | {item.get('item') or '-'} | {item.get('error') or '-'} |"
    )

(out_dir / "latest.md").write_text("\n".join(rows) + "\n", encoding="utf-8")
(out_dir / "latest.json").write_text(
    json.dumps(data, ensure_ascii=False, indent=2) + "\n",
    encoding="utf-8",
)
