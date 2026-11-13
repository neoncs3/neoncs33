#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlparse

import cloudscraper

ROOT = Path(__file__).resolve().parents[1]
OUT_DIR = ROOT / "health-status"
REPORT_JSON = OUT_DIR / "jetfilmizle-player.json"
REPORT_MD = OUT_DIR / "jetfilmizle-player.md"

WORKER_API = (
    "https://dplayer.erikkalinina1994.workers.dev/api/stream"
    "?pubId=689017f7e019e64194db2cf6"
    "&title=515"
    "&publisherId=688dd580384ba8406f5a4c3a"
)
VIDEO_PARK_ORIGIN = "https://videopark.top"
HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/157.0.0.0 Safari/537.36"
    ),
    "Referer": VIDEO_PARK_ORIGIN + "/",
    "Origin": VIDEO_PARK_ORIGIN,
    "Accept": "application/json,text/plain,*/*",
}


def hls_candidate(payload: dict) -> str | None:
    """Return one fresh HLS URL without persisting it to the health report."""
    hls = payload.get("hlsSource")
    if isinstance(hls, dict):
        file_url = hls.get("file")
        if isinstance(file_url, str) and file_url.startswith("https://"):
            return file_url

    sources = payload.get("sources")
    if isinstance(sources, list):
        for item in sources:
            if not isinstance(item, dict):
                continue
            file_url = item.get("file")
            media_type = str(item.get("type", "")).lower()
            if (
                isinstance(file_url, str)
                and file_url.startswith("https://")
                and ("hls" in media_type or file_url.split("?", 1)[0].lower().endswith(".m3u8"))
            ):
                return file_url
    return None


def subtitle_count(payload: dict) -> int:
    tracks = payload.get("tracks")
    if not isinstance(tracks, list):
        return 0
    return sum(
        1
        for item in tracks
        if isinstance(item, dict)
        and any(item.get(key) for key in ("file", "src", "url"))
    )


def self_test() -> None:
    fixture = {
        "hlsSource": {
            "file": "https://media.example.test/m/temporary-token",
            "type": "hls",
            "label": "Auto",
        },
        "sources": [
            {
                "file": "https://media.example.test/m/temporary-token",
                "type": "hls",
                "label": "Auto",
            }
        ],
        "tracks": [{"file": "https://media.example.test/tr.vtt", "lang": "tr"}],
    }
    assert hls_candidate(fixture) == fixture["hlsSource"]["file"]
    assert subtitle_count(fixture) == 1

    sources_only = {
        "sources": [
            {"file": "https://media.example.test/master.m3u8", "type": "application/x-mpegURL"}
        ]
    }
    assert hls_candidate(sources_only) == sources_only["sources"][0]["file"]
    assert hls_candidate({"mp4Sources": []}) is None
    print("JetFilmizle player health self-test: PASSED")


def safe_host(raw_url: str | None) -> str | None:
    if not raw_url:
        return None
    return urlparse(raw_url).hostname


def run_probe() -> dict:
    checked_at = datetime.now(timezone.utc).isoformat(timespec="seconds")
    result = {
        "provider": "JetFilmizle",
        "player": "DPlayer Worker / VideoPark",
        "status": "yellow",
        "checked_at_utc": checked_at,
        "api_status": None,
        "hls_status": None,
        "hls_type": None,
        "source_count": 0,
        "subtitle_count": 0,
        "media_host": None,
        "message": "",
    }

    scraper = cloudscraper.create_scraper(
        browser={"browser": "chrome", "platform": "windows", "mobile": False}
    )

    try:
        response = scraper.get(WORKER_API, headers=HEADERS, timeout=(8, 15))
        result["api_status"] = response.status_code
        if response.status_code != 200:
            result["message"] = f"Worker API HTTP {response.status_code}"
            return result

        payload = response.json()
        if not isinstance(payload, dict):
            result["message"] = "Worker API JSON nesne döndürmedi"
            return result

        api_error = str(payload.get("error") or "").strip()
        if api_error:
            result["message"] = f"Worker API hata yanıtı: {api_error[:160]}"
            return result

        sources = payload.get("sources")
        result["source_count"] = len(sources) if isinstance(sources, list) else 0
        result["subtitle_count"] = subtitle_count(payload)

        hls = payload.get("hlsSource")
        if isinstance(hls, dict):
            result["hls_type"] = str(hls.get("type") or "").lower() or None

        media_url = hls_candidate(payload)
        if not media_url:
            result["message"] = "API 200 döndürdü ancak beklenen HLS dosyası bulunamadı"
            return result

        result["media_host"] = safe_host(media_url)
        probe_headers = dict(HEADERS)
        probe_headers["Accept"] = (
            "application/vnd.apple.mpegurl,application/x-mpegURL,"
            "application/octet-stream,*/*"
        )
        probe_headers["Range"] = "bytes=0-2047"

        try:
            media_response = scraper.get(
                media_url,
                headers=probe_headers,
                timeout=(8, 15),
                stream=True,
            )
            result["hls_status"] = media_response.status_code
            prefix = next(media_response.iter_content(chunk_size=2048), b"")
            content_type = media_response.headers.get("content-type", "").lower()
            playlist_marker = b"#EXTM3U" in prefix[:2048]
            playlist_type = "mpegurl" in content_type or "m3u8" in content_type
            media_response.close()

            if media_response.status_code in (200, 206) and (playlist_marker or playlist_type):
                result["status"] = "green"
                result["message"] = "Worker API ve HLS playlist yanıtı doğrulandı"
            elif media_response.status_code in (200, 206) and prefix:
                result["status"] = "yellow"
                result["message"] = (
                    "Worker API HLS URL'si üretti; yanıt geldi ancak playlist biçimi "
                    "kesin doğrulanamadı"
                )
            else:
                result["message"] = f"HLS URL kontrolü HTTP {media_response.status_code}"
        except Exception as exc:
            result["message"] = (
                "HLS adresi alındı ancak medya kontrolü başarısız: "
                f"{type(exc).__name__}: {str(exc)[:140]}"
            )

    except Exception as exc:
        result["message"] = f"Worker API kontrolü başarısız: {type(exc).__name__}: {str(exc)[:180]}"

    return result


def main() -> int:
    parser = argparse.ArgumentParser(description="JetFilmizle DPlayer Worker API health probe")
    parser.add_argument("--self-test", action="store_true", help="Run local parser tests only")
    args = parser.parse_args()

    if args.self_test:
        self_test()
        return 0

    result = run_probe()
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    REPORT_JSON.write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )

    icon = "🟢" if result["status"] == "green" else "🟡"
    markdown = "\n".join(
        [
            f"# {icon} JetFilmizle oynatıcı kontrolü",
            "",
            f"- Kontrol zamanı (UTC): {result['checked_at_utc']}",
            f"- Oynatıcı: {result['player']}",
            f"- Worker API HTTP: {result['api_status'] or '-'}",
            f"- HLS kontrolü HTTP: {result['hls_status'] or '-'}",
            f"- Kaynak sayısı: {result['source_count']}",
            f"- Altyazı izi: {result['subtitle_count']}",
            f"- Medya sunucusu: {result['media_host'] or '-'}",
            f"- Sonuç: **{result['status'].upper()}** — {result['message']}",
            "",
            "Bu kontrol, bilinen örnek VideoPark/DPlayer uç noktasını ve dönen HLS playlist'ini sınar; tüm film ve dizi kaynaklarının çalıştığını tek başına garanti etmez.",
            "",
        ]
    )
    REPORT_MD.write_text(markdown, encoding="utf-8")

    print(markdown)
    if result["status"] != "green":
        print(f"::warning::JetFilmizle oynatıcı kontrolü: {result['message']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
