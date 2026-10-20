#!/usr/bin/env python3
from __future__ import annotations

import json
import re
from pathlib import Path

import cloudscraper

ROOT = Path(__file__).resolve().parents[1]
DOMAIN_FILE = ROOT / "NeonCore" / "domains.json"
OUT_DIR = ROOT / "health-status"

EXCLUDED = {
    "gradle", "CanliTV", "OxAx", "__Temel", "SineWix",
    "YouTube", "NetflixMirror", "HQPorner",
}

# Some sites have a very slow front page while their actual catalogue routes respond.
# Probe these known catalogue routes only after the main URL fails.
HEALTH_FALLBACK_PATHS = {
    "AutoDdizimagCom": "/diziler/",
}

MEDIA_RE = re.compile(
    r"""(?i)(?:https?:)?//[^"'<>\s]+?(?:\.m3u8|\.mpd|\.mp4|\.m4v|\.webm|\.mov)"""
)
IFRAME_RE = re.compile(
    r"""(?i)<iframe\b[^>]*(?:src|data-src|data-url|data-vsrc|data-video-src|data-player)=["'][^"']+["']"""
)

def read_json(path: Path, fallback):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return fallback

def find_provider_file(module: Path):
    source = module / "src" / "main" / "kotlin"
    if not source.exists():
        return None
    for path in source.rglob("*.kt"):
        text = path.read_text(encoding="utf-8", errors="replace")
        if re.search(r"class\s+\w+\s*:\s*NeonMainAPI\s*\(\s*\)", text):
            return path
    return None

def extract_main_url(text: str):
    match = re.search(r'override\s+var\s+mainUrl\s*=\s*"([^"]+)"', text)
    return match.group(1).strip().rstrip("/") if match else None

def live_check(scraper, url: str):
    try:
        response = scraper.get(url, timeout=20, allow_redirects=True)
        body = response.text or ""
        title_match = re.search(r"<title[^>]*>(.*?)</title>", body, re.I | re.S)
        return {
            "ok": bool(response.ok),
            "anti_bot_blocked": response.status_code in {403, 429, 503} and any(
                marker in body.lower()
                for marker in (
                    "cloudflare",
                    "just a moment",
                    "attention required",
                    "checking your browser",
                    "verify you are human",
                    "cf-chl-",
                )
            ),
            "status_code": response.status_code,
            "final_url": response.url.rstrip("/"),
            "bytes": len(response.content or b""),
            "title": re.sub(r"\s+", " ", title_match.group(1)).strip()[:120] if title_match else "",
            "media_hints": len(MEDIA_RE.findall(body)),
            "iframe_hints": len(IFRAME_RE.findall(body)),
            "error": None,
        }
    except Exception as exc:
        return {
            "ok": False,
            "status_code": None,
            "final_url": url,
            "bytes": 0,
            "title": "",
            "media_hints": 0,
            "iframe_hints": 0,
            "error": f"{type(exc).__name__}: {exc}",
        }

def analyse(module: Path, domains: dict[str, str], scraper):
    provider_file = find_provider_file(module)
    if provider_file is None:
        return {
            "provider": module.name,
            "status": "yellow",
            "domain": domains.get(module.name, ""),
            "configured_domain": domains.get(module.name, ""),
            "live_domain": domains.get(module.name, ""),
            "live": {"ok": False, "anti_bot_blocked": False, "error": "NeonMainAPI provider source bulunamadı"},
            "pipelines": {},
        }

    source = provider_file.read_text(encoding="utf-8", errors="replace")
    configured = extract_main_url(source) or domains.get(module.name, "")
    if configured:
        live = live_check(scraper, configured)
        # If the homepage times out, check the provider's actual TV catalogue
        # once before marking the whole provider red. A successful fallback is yellow because
        # the homepage itself remains slow, but the provider is not completely unreachable.
        fallback_path = HEALTH_FALLBACK_PATHS.get(module.name)
        if not live.get("ok") and fallback_path:
            fallback_url = configured.rstrip("/") + fallback_path
            fallback_live = live_check(scraper, fallback_url)
            if fallback_live.get("ok") or fallback_live.get("anti_bot_blocked"):
                fallback_live["fallback_used"] = True
                fallback_live["primary_probe_url"] = configured
                fallback_live["primary_probe_error"] = (
                    live.get("error")
                    or (f"HTTP {live.get('status_code')}" if live.get("status_code") else "Ana sayfa yanıt vermedi")
                )
                live = fallback_live
    else:
        live = {
            "ok": False,
            "status_code": None,
            "final_url": "",
            "bytes": 0,
            "title": "",
            "media_hints": 0,
            "iframe_hints": 0,
            "error": "mainUrl bulunamadı",
        }

    low = source.lower()
    pos = low.find("loadlinks(")
    load_links_area = low[pos:pos + 16000] if pos >= 0 else ""

    pipelines = {
        "load": bool(re.search(r"override\s+suspend\s+fun\s+load\s*\(", source)),
        "search": bool(re.search(r"override\s+suspend\s+fun\s+search\s*\(", source)),
        "loadLinks": bool(re.search(r"override\s+suspend\s+fun\s+loadLinks\s*\(", source)),
        "metadata": "neonenrichresponse(" in low,
        "video": "neonresolvelinks(" in low or "neonresolvelinkcandidates(" in low,
        "subtitles": "neonresolvelinks(" in low or "neonresolvelinkcandidates(" in low or "neonemitsubtitle(" in low,
        "cache": "neoncachedocument(" in low or "neoncachedhtml(" in low or "neonresolvelinks(" in low or "neonresolvelinkcandidates(" in low,
        "tmdb_playback_forbidden": "tmdb" not in load_links_area,
    }

    source_ok = all(
        pipelines[key]
        for key in (
            "load", "search", "loadLinks", "metadata",
            "video", "subtitles", "cache", "tmdb_playback_forbidden"
        )
    )

    if live.get("fallback_used") or live.get("anti_bot_blocked"):
        status = "yellow"
    elif not live["ok"]:
        status = "red"
    elif not source_ok:
        status = "yellow"
    else:
        status = "green"

    return {
        "provider": module.name,
        "status": status,
        # "domain" is the URL configured in the source; redirects are reported separately.
        "domain": configured,
        "configured_domain": configured,
        "live_domain": live["final_url"] or configured,
        "live": live,
        "pipelines": pipelines,
        "source": str(provider_file.relative_to(ROOT)),
    }

def write_if_changed(path: Path, content: str):
    old = path.read_text(encoding="utf-8") if path.exists() else ""
    if old == content:
        return False
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    return True

def main():
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    domains = read_json(DOMAIN_FILE, {})
    if not isinstance(domains, dict):
        domains = {}

    scraper = cloudscraper.create_scraper(
        browser={"browser": "chrome", "platform": "windows", "mobile": False}
    )

    results = []
    for module in sorted(ROOT.iterdir(), key=lambda p: p.name.lower()):
        if (
            not module.is_dir()
            or module.name in EXCLUDED
            or not (module / "build.gradle.kts").exists()
        ):
            continue
        results.append(analyse(module, domains, scraper))

    summary = {
        "providers": results,
        "green": sum(x["status"] == "green" for x in results),
        "yellow": sum(x["status"] == "yellow" for x in results),
        "red": sum(x["status"] == "red" for x in results),
    }
    write_if_changed(
        OUT_DIR / "summary.json",
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n",
    )

    rows = [
        "# 🩺 NeonCS Eklenti Sağlık Durumu",
        "",
        "Bu rapor tools/provider_health.py tarafından otomatik güncellenir.",
        "",
        "| Eklenti | Durum | Kodda kayıtlı domain | HTTP yönlendirmesi | HTTP | Medya ipucu | Resolver kodu | Altyazı kodu | Metadata | Cache |",
        "|---|---|---|---|---|---|---|---|",
    ]

    for item in results:
        live = item["live"]
        p = item["pipelines"]
        icon = {"green": "🟢", "yellow": "🟡", "red": "🔴"}[item["status"]]
        rows.append(
            f"| **{item['provider']}** | {icon} | {item.get('configured_domain', item.get('domain', '-')) or '-'} | "
            f"{live.get('final_url', item.get('live_domain', '-')) or '-'} | "
            f"{'✅' if live.get('ok') else '❌'} ({live.get('status_code') or '-'}) | "
            f"{live.get('media_hints', 0)} / {live.get('iframe_hints', 0)} | "
            f"{'✅' if p.get('video') else '❌'} | "
            f"{'✅' if p.get('subtitles') else '❌'} | "
            f"{'✅' if p.get('metadata') else '❌'} | "
            f"{'✅' if p.get('cache') else '❌'} |"
        )

    rows.extend([
        "",
        "🟢 HTTP erişimi + yapısal kaynak kontrolleri geçti · 🟡 Anti-bot, yapısal uyarı veya ana sayfa yavaşken katalog yolu erişilebilir · 🔴 HTTP erişimi doğrulanamadı.",
        "",
        "Önemli: Resolver/altyazı/metadata sütunları kaynak kodunda beklenen işlevlerin bulunduğunu denetler; gerçek video bağlantısının çalıştığını kanıtlamaz. Gerçek link çözümleme ve ExoPlayer testi için playback-status/latest.md raporuna bakın.",
        "",
        "Kodda kayıtlı domain ile son HTTP yönlendirme adresi bilerek ayrı gösterilir; yönlendirme tek başına otomatik mainUrl değişikliği değildir.",
    ])
    write_if_changed(OUT_DIR / "index.md", "\n".join(rows) + "\n")

    for item in results:
        live = item["live"]
        p = item["pipelines"]
        icon = {"green": "🟢", "yellow": "🟡", "red": "🔴"}[item["status"]]
        lines = [
            f"# {icon} {item['provider']}",
            "",
            f"Kodda kayıtlı domain: {item.get('configured_domain', item.get('domain', '-')) or '-'}",
            "",
            f"Son HTTP URL: {live.get('final_url', item.get('live_domain', '-')) or '-'}",
            "",
            f"HTTP: {'✅' if live.get('ok') else '❌'} {live.get('status_code') or ''}",
            "",
            f"Anti-bot engeli: {'evet' if live.get('anti_bot_blocked') else 'hayır'}",
            "",
            f"Sayfa başlığı: {live.get('title') or '-'}",
            "",
            f"Canlı medya ipucu: {live.get('media_hints', 0)} media URL, {live.get('iframe_hints', 0)} iframe",
            "",
            "Not: Bu rapor kaynak kodunun yapısını ve site HTTP erişimini ölçer; gerçek oynatma sonucu değildir. Gerçek oynatma için playback-status/latest.md raporunu inceleyin.",
            "",
            "## Pipeline",
            "",
            f"- Search: {'✅' if p.get('search') else '❌'}",
            f"- Load: {'✅' if p.get('load') else '❌'}",
            f"- LoadLinks: {'✅' if p.get('loadLinks') else '❌'}",
            f"- Metadata: {'✅' if p.get('metadata') else '❌'}",
            f"- Video resolver: {'✅' if p.get('video') else '❌'}",
            f"- Subtitles: {'✅' if p.get('subtitles') else '❌'}",
            f"- Cache/fallback: {'✅' if p.get('cache') else '❌'}",
            f"- TMDB playback yasağı: {'✅' if p.get('tmdb_playback_forbidden') else '❌'}",
            "",
            f"Kaynak: {item.get('source', '-')}",
        ]
        if live.get("primary_probe_error"):
            lines.extend([
                "",
                f"Ana sayfa yavaş/yanıtsız: {live['primary_probe_error']}",
                f"Yedek katalog kontrolü: {live.get('final_url', '-')}",
            ])
        if live.get("error"):
            lines.extend(["", f"Hata: {live['error']}"])
        write_if_changed(OUT_DIR / f"{item['provider']}.md", "\n".join(lines) + "\n")

if __name__ == "__main__":
    main()
