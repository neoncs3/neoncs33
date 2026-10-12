#!/usr/bin/env python3
"""Generate a generic CloudStream provider from a public website URL.

The generator uses observable HTML/JSON-LD patterns only. It does not bypass
login gates, CAPTCHAs, anti-bot challenges, DRM, or other access controls.
"""
from __future__ import annotations

import argparse
import ipaddress
import json
import re
import socket
import sys
from datetime import datetime, timezone
from collections import defaultdict
from functools import lru_cache
from pathlib import Path
from urllib.parse import parse_qs, urlencode, urljoin, urlsplit, urlunsplit

import requests
from bs4 import BeautifulSoup
from bs4.element import Tag

ROOT = Path(__file__).resolve().parents[1]
REPORT_DIR = ROOT / "auto-generated" / "reports"
USER_AGENT = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
)
MAX_BODY_BYTES = 2 * 1024 * 1024
MAX_PAGES = 14
MAX_MEDIA_PROBES = 3
TIMEOUT = (5, 9)
SESSION = requests.Session()
SESSION.headers.update({
    "User-Agent": USER_AGENT,
    "Accept": "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.7",
    "Accept-Language": "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
})


def normalize_input(raw: str) -> str:
    value = raw.strip()
    if not value:
        raise ValueError("Site URL boş olamaz.")
    if "://" not in value:
        value = "https://" + value
    parts = urlsplit(value)
    if parts.scheme.lower() not in {"http", "https"}:
        raise ValueError("Yalnızca http:// veya https:// URL kabul edilir.")
    if not parts.hostname:
        raise ValueError("URL içinde geçerli bir alan adı bulunamadı.")
    if parts.username or parts.password:
        raise ValueError("Kullanıcı adı/şifre içeren URL kabul edilmiyor.")
    if parts.port and not (1 <= parts.port <= 65535):
        raise ValueError("URL portu geçersiz.")
    return urlunsplit((parts.scheme.lower(), parts.netloc, parts.path or "/", parts.query, ""))


@lru_cache(maxsize=128)
def validate_public_host(host: str, port: int) -> None:
    normalized = host.rstrip(".").lower()
    if normalized in {"localhost", "localhost.localdomain"} or normalized.endswith(".local"):
        raise ValueError("Yerel ağ adresleri güvenlik nedeniyle kabul edilmiyor.")
    try:
        literal = ipaddress.ip_address(normalized)
        if not literal.is_global:
            raise ValueError("Özel/yerel IP adresleri güvenlik nedeniyle kabul edilmiyor.")
        return
    except ValueError as exc:
        if "güvenlik nedeniyle" in str(exc):
            raise
    try:
        addresses = socket.getaddrinfo(normalized, port, type=socket.SOCK_STREAM)
    except OSError as exc:
        raise ValueError(f"Alan adı çözümlenemedi: {normalized} ({exc})") from exc
    if not addresses:
        raise ValueError(f"Alan adı için IP adresi bulunamadı: {normalized}")
    for item in addresses:
        address = ipaddress.ip_address(item[4][0])
        if not address.is_global:
            raise ValueError(
                f"{normalized} özel/yerel IP adresine çözülüyor; işlem durduruldu."
            )


def validate_url(raw: str) -> str:
    value = normalize_input(raw)
    parts = urlsplit(value)
    port = parts.port or (443 if parts.scheme == "https" else 80)
    validate_public_host(parts.hostname or "", port)
    return value


def fetch_html(raw_url: str) -> dict:
    current = validate_url(raw_url)
    for _ in range(6):
        response = SESSION.get(current, timeout=TIMEOUT, allow_redirects=False, stream=True)
        try:
            if response.status_code in {301, 302, 303, 307, 308}:
                location = response.headers.get("Location")
                if not location:
                    break
                current = validate_url(urljoin(current, location))
                continue

            content = bytearray()
            for chunk in response.iter_content(32768):
                if not chunk:
                    continue
                content.extend(chunk)
                if len(content) >= MAX_BODY_BYTES:
                    del content[MAX_BODY_BYTES:]
                    break
            charset = response.encoding or "utf-8"
            text_value = bytes(content).decode(charset, errors="replace")
            return {
                "url": current,
                "status": response.status_code,
                "content_type": response.headers.get("Content-Type", ""),
                "html": text_value,
                "truncated": len(content) >= MAX_BODY_BYTES,
            }
        finally:
            response.close()
    raise RuntimeError(f"Çok fazla yönlendirme veya yönlendirme başlığı eksik: {raw_url}")


def same_site(url: str, origin_url: str) -> bool:
    host_a = (urlsplit(url).hostname or "").lower().removeprefix("www.")
    host_b = (urlsplit(origin_url).hostname or "").lower().removeprefix("www.")
    return bool(host_a and host_a == host_b)


def clean_label(value: str, limit: int = 75) -> str:
    return re.sub(r"\s+", " ", value or "").strip()[:limit]


def soup_text(soup: BeautifulSoup) -> str:
    return re.sub(r"\s+", " ", soup.get_text(" ", strip=True))


def structured_objects(soup: BeautifulSoup) -> list[dict]:
    result: list[dict] = []

    def walk(value: object) -> None:
        if isinstance(value, dict):
            result.append(value)
            for child in value.values():
                if isinstance(child, (dict, list)):
                    walk(child)
        elif isinstance(value, list):
            for child in value:
                if isinstance(child, (dict, list)):
                    walk(child)

    for script in soup.select('script[type*="ld+json"]'):
        raw = script.string or script.get_text()
        if not raw.strip():
            continue
        try:
            walk(json.loads(raw.strip().strip(";")))
        except (json.JSONDecodeError, TypeError):
            continue
    return result


def json_values(objects: list[dict], key: str) -> list[object]:
    values: list[object] = []
    for obj in objects:
        value = obj.get(key)
        if value not in (None, "", [], {}):
            values.append(value)
    return values


def first_json_text(objects: list[dict], *keys: str) -> str:
    for key in keys:
        for value in json_values(objects, key):
            if isinstance(value, str) and value.strip():
                return value.strip()
            if isinstance(value, dict):
                for nested_key in ("url", "name", "@id"):
                    nested = value.get(nested_key)
                    if isinstance(nested, str) and nested.strip():
                        return nested.strip()
            if isinstance(value, list):
                for element in value:
                    if isinstance(element, str) and element.strip():
                        return element.strip()
                    if isinstance(element, dict):
                        nested = element.get("url") or element.get("name")
                        if isinstance(nested, str) and nested.strip():
                            return nested.strip()
    return ""


def page_title(soup: BeautifulSoup, objects: list[dict]) -> str:
    for selector, attr in (
        ('meta[property="og:site_name"]', "content"),
        ('meta[name="application-name"]', "content"),
        ("h1", None),
        ('meta[property="og:title"]', "content"),
        ("title", None),
    ):
        node = soup.select_one(selector)
        value = (node.get(attr, "") if node and attr else node.get_text(" ", strip=True) if node else "")
        if value.strip():
            return clean_label(value, 90)
    return clean_label(first_json_text(objects, "name") or (urlsplit(soup.base.get("href", "")).hostname if soup.base else ""), 90)


def normalized_filter_text(value: str) -> str:
    translations = str.maketrans({
        "ı": "i", "İ": "i", "ş": "s", "Ş": "s", "ğ": "g",
        "Ğ": "g", "ü": "u", "Ü": "u", "ö": "o", "Ö": "o",
        "ç": "c", "Ç": "c",
    })
    return re.sub(r"[^a-z0-9]+", " ", (value or "").lower().translate(translations)).strip()


def is_flag_or_icon_asset(value: str) -> bool:
    """Reject common country/language flag assets so they are not mistaken for posters."""
    raw = str(value or "")
    normalized = normalized_filter_text(raw)
    if any(marker in normalized for marker in (
        "flag icon", "country flag", "language flag", "flag sprite",
    )):
        return True
    if re.search(r"(?i)(?:^|/)(?:flags?|flag-icons|country-flags?)/", raw):
        return True
    file_pattern = re.compile(
        r"(?i)^(?:flags?|flag[-_]icon|icon[-_]flag|country[-_]?flag|"
        r"flag[-_]sprite|language[-_]?flag)(?:[-_][a-z]{2})?\."
        r"(?:svg|png|jpe?g|webp|gif|ico)$"
    )
    filenames = re.findall(
        r"(?i)(?:^|/)([^/\s?#]+)\.(?:svg|png|jpe?g|webp|gif|ico)(?=$|[?#\s])",
        raw,
    )
    return any(file_pattern.fullmatch(filename) for filename in filenames)


UTILITY_LABELS = {
    "yorum", "yorumlar", "comments", "comment", "replies",
    "kesfet", "kesif", "explore", "discover",
    "takvim", "takvimi", "calendar", "schedule",
    "dizi takvimi", "film takvimi", "tv schedule",
    "tartisma", "tartismalar", "discussion", "discussions", "discuss",
    "baslik", "basliklar", "topic", "topics", "forum", "forums",
    "thread", "threads", "konu", "konular", "sohbet", "sohbetler",
    "community", "topluluk", "gundem", "haberler", "duyurular",
    "login", "register", "giris", "kayit", "hesabim", "profil",
    "profilim", "profiller", "profile", "profiles", "user", "users",
    "user profile", "user profiles", "my profile", "member", "members",
    "uye", "uyeler", "uye profili", "kullanici", "kullanicilar",
    "kullanici profili", "author", "authors", "account", "accounts",
    "my account", "ayarlar", "iletisim", "hakkimizda", "yardim",
    "privacy policy", "terms", "about us", "contact us",
}

UTILITY_PATHS = {
    "yorum", "yorumlar", "comment", "comments", "replies",
    "kesfet", "kesif", "explore", "discover",
    "takvim", "takvimi", "calendar", "schedule",
    "dizi takvimi", "film takvimi",
    "tartisma", "tartismalar", "discussion", "discussions", "discuss",
    "baslik", "basliklar", "topic", "topics", "forum", "forums",
    "thread", "threads", "konu", "konular", "sohbet", "sohbetler",
    "community", "topluluk", "gundem", "duyurular",
    "profile", "profiles", "profile php", "profil", "profiller", "profilim",
    "user", "users", "user php", "member", "members", "members php",
    "author", "authors", "author php", "account", "accounts", "my account",
    "u", "uye", "uyeler", "uye profili", "kullanici", "kullanicilar",
    "kullanici profili", "avatar", "avatars", "activity", "notifications",
    "messages", "inbox", "groups", "group", "settings", "dashboard",
}


def is_utility_link(label: str, url: str) -> bool:
    normalized_label = normalized_filter_text(label)
    if normalized_label in UTILITY_LABELS:
        return True
    if any(
        normalized_label.startswith(prefix + " ")
        for prefix in (
            "user profile", "my profile", "uye profili", "kullanici profili",
            "tartisma", "discussion", "forum", "thread", "topic", "konu",
            "yorum", "comment", "reply", "profil", "profile",
        )
    ):
        return True

    parsed = urlsplit(url)
    raw_segments = [part for part in parsed.path.split("/") if part]
    if any(part.startswith("@") for part in raw_segments):
        return True
    segments = [
        normalized_filter_text(part.replace("-", " ").replace("_", " "))
        for part in raw_segments
    ]
    if any(segment in UTILITY_PATHS for segment in segments):
        return True

    query = parse_qs(parsed.query, keep_blank_values=True)
    profile_or_discussion_keys = {
        "user", "userid", "user_id", "profile", "profile_id", "member",
        "member_id", "author", "author_id", "thread", "thread_id", "topic",
        "topic_id", "discussion", "comment", "reply", "post_id",
    }
    if any(normalized_filter_text(key) in {
        normalized_filter_text(item) for item in profile_or_discussion_keys
    } for key in query):
        return True
    return False


def is_non_catalog_anchor(anchor: Tag, href: str, label: str) -> bool:
    if is_utility_link(label, href):
        return True
    if "author" in " ".join(anchor.get("rel", [])).lower():
        return True

    hints: list[str] = []
    node = anchor
    for _ in range(4):
        if not isinstance(node, Tag):
            break
        hints.extend([
            str(node.get("id", "")),
            " ".join(node.get("class", []) if isinstance(node.get("class"), list) else [str(node.get("class", ""))]),
            str(node.get("itemprop", "")),
            str(node.get("role", "")),
            str(node.get("data-role", "")),
        ])
        node = node.parent
    image = anchor.find("img")
    if image:
        hints.extend([
            str(image.get("alt", "")), str(image.get("title", "")),
            str(image.get("class", "")), str(image.get("src", "")),
            str(image.get("data-src", "")), str(image.get("data-lazy-src", "")),
        ])
    if image and is_flag_or_icon_asset(" ".join(
            str(image.get(attribute, "") or "") for attribute in (
                "class", "id", "alt", "title", "src", "data-src", "data-lazy-src",
                "data-original", "data-poster",
            )
        )):
        return True

    hint_text = normalized_filter_text(" ".join(hints))
    profile_discussion_hints = (
        "profile", "user profile", "user card", "member card", "user name",
        "user avatar", "member avatar", "author box", "author link",
        "thread card", "discussion", "forum topic", "topic card",
        "comment link", "comment list", "reply list", "avatar",
        "user photo", "profile photo", "profile picture", "member profile",
    )
    return any(marker in hint_text for marker in profile_discussion_hints)

def extract_media_candidates(pages: list[dict]) -> list[str]:
    """Collect a few direct, public media URLs from observed page HTML."""
    candidates: list[str] = []
    seen: set[str] = set()
    media_extensions = {".m3u8", ".mpd", ".mp4", ".m4v"}

    for page in pages:
        html = str(page.get("html", "")).replace("\\/", "/").replace("&amp;", "&")
        soup = BeautifulSoup(html, "html.parser")
        raw_urls: list[str] = []
        for element in soup.select(
            "video[src], source[src], video[data-src], source[data-src], "
            "a[href], [data-file], [data-video], [data-stream]"
        ):
            for attribute in ("src", "data-src", "href", "data-file", "data-video", "data-stream"):
                value = str(element.get(attribute, "") or "").strip()
                if value:
                    raw_urls.append(value)

        raw_urls.extend(re.findall(
            r"""(?:(?:https?:)?//|/)[^\s"'<>]+?\.(?:m3u8|mpd|mp4|m4v)(?:\?[^\s"'<>]*)?""",
            html,
            flags=re.IGNORECASE,
        ))

        for raw in raw_urls:
            candidate = urljoin(str(page.get("url", "")), raw.strip().rstrip("),;"))
            parts = urlsplit(candidate)
            if parts.scheme.lower() not in {"http", "https"} or not parts.hostname:
                continue
            if not any((parts.path or "").lower().endswith(ext) for ext in media_extensions):
                continue
            if candidate not in seen:
                seen.add(candidate)
                candidates.append(candidate)
            if len(candidates) >= MAX_MEDIA_PROBES:
                return candidates
    return candidates


def safe_report_url(raw_url: str) -> str:
    """Remove query strings and fragments before writing URLs to a public report."""
    parts = urlsplit(raw_url)
    return urlunsplit((parts.scheme, parts.netloc, parts.path, "", ""))[:240]


def probe_media_urls(pages: list[dict]) -> list[dict]:
    """Make at most a few bounded Range requests; never bypass access controls."""
    results: list[dict] = []
    for raw_url in extract_media_candidates(pages):
        reported_url = safe_report_url(raw_url)
        current = raw_url
        status_code: int | None = None
        content_type = ""
        reachable = False
        note = "HTTP yanıtı alınamadı"
        try:
            for _ in range(4):
                current = validate_url(current)
                response = SESSION.get(
                    current,
                    headers={"Range": "bytes=0-1023"},
                    timeout=(3, 5),
                    allow_redirects=False,
                    stream=True,
                )
                try:
                    status_code = response.status_code
                    if status_code in {301, 302, 303, 307, 308}:
                        location = response.headers.get("Location")
                        if not location:
                            note = "Yönlendirme başlığı eksik"
                            break
                        current = urljoin(current, location)
                        continue
                    content_type = response.headers.get("Content-Type", "").split(";", 1)[0].strip().lower()
                    reachable = status_code in {200, 206} and content_type not in {
                        "text/html", "application/xhtml+xml"
                    }
                    note = (
                        "HTTP erişilebilir (oynatma doğrulaması değildir)"
                        if reachable else f"HTTP {status_code}; doğrudan medya yanıtı doğrulanamadı"
                    )
                    break
                finally:
                    response.close()
            else:
                note = "Çok fazla yönlendirme"
        except (requests.RequestException, ValueError, RuntimeError, OSError):
            note = "Erişim engeli, güvenlik filtresi veya zaman aşımı"

        results.append({
            "url": reported_url,
            "status": status_code,
            "content_type": content_type or "bilinmiyor",
            "reachable": reachable,
            "note": note,
        })
    return results


def extract_episode_inventory(pages: list[dict]) -> dict:
    """Report episode numbers visible in the bounded scan; do not assume unseen pages are missing."""
    by_season: dict[int, set[int]] = defaultdict(set)
    sources: dict[tuple[int, int], list[str]] = defaultdict(list)

    season_pattern = re.compile(
        r"(?i)\b(?:season|sezon|s)[\s._/-]*0?(\d{1,2})\b"
    )
    patterns = (
        re.compile(
            r"(?i)\bs(?:eason)?\s*0?(\d{1,2})[\s._/-]*e(?:p(?:isode)?)?[\s._/-]*0?(\d{1,3})\b"
        ),
        re.compile(
            r"(?i)\b(?:season|sezon)[\s._/-]*0?(\d{1,2}).{0,35}?\b(?:episode|episodes|ep|b[öo]l[üu]m)[\s._/-]*0?(\d{1,3})\b"
        ),
        re.compile(
            r"(?i)\b(\d{1,2})\s*\.?\s*(?:sezon|season).{0,25}?\b(\d{1,3})\s*\.?\s*(?:b[öo]l[üu]m|episode|ep)\b"
        ),
    )
    single_episode = re.compile(
        r"(?i)\b(?:episode|episodes|ep|b[öo]l[üu]m)[\s._/#:-]*0?(\d{1,3})\b"
    )

    for page in pages:
        soup = BeautifulSoup(str(page.get("html", "")), "html.parser")
        for anchor in soup.select("a[href]"):
            label = clean_label(
                anchor.get("aria-label", "") or
                anchor.get("title", "") or
                anchor.get_text(" ", strip=True)
            )
            href = urljoin(str(page.get("url", "")), str(anchor.get("href", "")))
            combined = f"{label} {href}"
            match = next((p.search(combined) for p in patterns if p.search(combined)), None)
            if match:
                try:
                    season_no = int(match.group(1))
                    episode_no = int(match.group(2))
                except (ValueError, IndexError):
                    continue
            else:
                episode_match = single_episode.search(combined)
                if not episode_match:
                    continue
                episode_no = int(episode_match.group(1))
                season_match = season_pattern.search(combined)
                season_no = int(season_match.group(1)) if season_match else 1

            if not (1 <= season_no <= 99 and 1 <= episode_no <= 999):
                continue
            by_season[season_no].add(episode_no)
            sources[(season_no, episode_no)].append(href)

    inventory = []
    potential_gaps = []
    for season_no, episodes in sorted(by_season.items()):
        ordered = sorted(episodes)
        missing = [
            number for number in range(ordered[0], ordered[-1] + 1)
            if number not in episodes
        ]
        inventory.append({
            "season": season_no,
            "episodes_found": ordered,
            "episode_count": len(ordered),
        })
        # Avoid alarming on sparse pages or huge gaps from arbitrary URL numbering.
        if len(ordered) >= 3 and missing and len(missing) <= 30:
            potential_gaps.append({
                "season": season_no,
                "missing": missing,
                "observed_min": ordered[0],
                "observed_max": ordered[-1],
            })

    return {
        "seasons_detected": len(by_season),
        "episodes_detected": sum(len(values) for values in by_season.values()),
        "inventory": inventory,
        "potential_gaps": potential_gaps,
    }


def valid_link_target(url: str, origin: str) -> bool:
    if not same_site(url, origin):
        return False
    parts = urlsplit(url)
    path = (parts.path or "/").lower()
    if path in {"", "/"}:
        return False
    if is_utility_link("", url):
        return False
    if any(path.endswith(ext) for ext in (
        ".jpg", ".jpeg", ".png", ".gif", ".webp", ".svg", ".ico", ".css",
        ".js", ".woff", ".woff2", ".ttf", ".pdf", ".zip", ".mp4", ".m3u8",
        ".mpd", ".srt", ".vtt", ".ass",
    )):
        return False
    blocked = (
        "/wp-admin", "/wp-content", "/wp-json", "/feed", "/tag/", "/author/",
        "/oyuncu/", "/actor/", "/login", "/logout", "/register", "/account",
        "/privacy", "/terms", "/contact", "/iletisim", "/giris", "/kayit",
        "/search/", "/page/", "/yorum", "/comment", "/comments", "/kesfet",
        "/discover", "/takvim", "/calendar", "/tartisma", "/discussion",
        "/baslik", "/topics", "/forum", "/sepet", "/cart",
    )
    if any(part in path for part in blocked):
        return False
    if parts.fragment:
        return False
    return len(path.strip("/")) > 1


def extract_catalogue_samples(
    pages: list[dict],
    origin: str,
    categories: list[tuple[str, str]],
    limit: int = 6,
) -> list[str]:
    """Find actual poster-backed catalogue titles to drive the emulator test."""
    category_keys = {
        (urlsplit(url).hostname or "").lower().removeprefix("www.")
        + urlsplit(url).path.rstrip("/")
        for url, _ in categories
    }
    placeholder_markers = (
        "placeholder", "no-poster", "noposter", "no-image", "noimage",
        "blank", "transparent", "avatar", "favicon", "spinner", "loading",
        "pixel", "missing", "not-found", "logo", "flag-icon", "country-flag",
    )
    found: list[str] = []
    seen: set[str] = set()
    episode_markers = re.compile(
        r"(?i)(?:\bs\d{1,2}e\d{1,3}\b|\b\d{1,2}\s*[x×]\s*\d{1,3}\b|"
        r"\b(?:b[öo]l[üu]m|episode|episodes|ep)\s*[-:#.]?\s*\d{1,3}\b)"
    )

    def usable_poster(image: Tag, base_url: str) -> bool:
        for attribute in (
            "data-poster", "data-original", "data-lazy-src", "data-src",
            "data-image", "src",
        ):
            raw = str(image.get(attribute, "") or "").strip()
            if not raw or raw.startswith("data:"):
                continue
            if is_flag_or_icon_asset(
                " ".join((raw, str(image.get("class", "")), str(image.get("alt", "")),
                          str(image.get("title", ""))))
            ):
                continue
            candidate = urljoin(base_url, raw)
            parsed = urlsplit(candidate)
            if parsed.scheme.lower() not in {"http", "https"} or not parsed.hostname:
                continue
            filename = parsed.path.rsplit("/", 1)[-1].lower()
            if any(marker in filename for marker in placeholder_markers):
                continue
            return True
        return False

    for page in pages:
        soup = BeautifulSoup(str(page.get("html", "")), "html.parser")
        for anchor in soup.select("a[href]"):
            href = urljoin(str(page.get("url", origin)), str(anchor.get("href", "")))
            if not valid_link_target(href, origin):
                continue
            href_parts = urlsplit(href)
            category_key = (href_parts.hostname or "").lower().removeprefix("www.") + href_parts.path.rstrip("/")
            if category_key in category_keys:
                continue

            heading = anchor.select_one("h1, h2, h3, h4, .title, .name, .movie-title, .film-title, .dizi-title")
            image = anchor.select_one("img")
            label_candidates = [
                anchor.get("title", ""),
                anchor.get("aria-label", ""),
                heading.get_text(" ", strip=True) if heading else "",
                anchor.get_text(" ", strip=True),
                image.get("alt", "") if image else "",
            ]
            label = next(
                (clean_label(value, 90) for value in label_candidates
                 if clean_label(value, 90) and len(clean_label(value, 90)) >= 2),
                "",
            )
            if not label or is_non_catalog_anchor(anchor, href, label):
                continue
            if normalized_filter_text(label) in {"film", "filmler", "dizi", "diziler", "izle", "ana sayfa", "home"}:
                continue
            if episode_markers.search(label + " " + href):
                continue

            if image is None:
                node = anchor
                for _ in range(4):
                    parent = node.parent
                    if not isinstance(parent, Tag):
                        break
                    candidate_image = parent.select_one("img")
                    if candidate_image is not None:
                        image = candidate_image
                        break
                    node = parent
            if image is None or not usable_poster(image, str(page.get("url", origin))):
                continue

            key = normalized_filter_text(label)
            if key in seen:
                continue
            seen.add(key)
            found.append(label)
            if len(found) >= limit:
                return found
    return found


def find_search_form(soup: BeautifulSoup, base_url: str) -> dict:
    key_names = {"s", "q", "search", "query", "term", "keyword", "keywords", "ara"}
    for form in soup.select("form"):
        inputs = form.select("input[name], select[name]")
        selected = next(
            (node.get("name", "") for node in inputs if node.get("name", "").lower() in key_names),
            "",
        )
        if not selected:
            continue
        method = (form.get("method") or "get").lower()
        action = urljoin(base_url, form.get("action") or base_url)
        if method != "get" or not same_site(action, base_url):
            return {"action": "", "field": selected, "method": method}
        return {"action": action, "field": selected, "method": "get"}
    return {"action": "", "field": "s", "method": "fallback"}


def is_category_link(label: str, url: str) -> bool:
    combined = f"{label} {url}".lower()
    terms = (
        "film", "dizi", "movie", "series", "kategori", "category", "genre", "tur/",
        "tür", "turler", "genre", "arsiv", "arşiv", "populer", "popüler",
        "son eklenen", "yeni eklenen", "bolum", "bölüm", "aksiyon", "komedi",
        "dram", "korku", "romantik", "gerilim", "animasyon", "fantastik",
        "bilim-kurgu", "belgesel", "anime", "korea", "kore",
    )
    return any(term in combined for term in terms)


def analyse_site(raw_url: str) -> dict:
    target = validate_url(raw_url)
    first = fetch_html(target)
    target_parts = urlsplit(first["url"])
    origin = f"{target_parts.scheme}://{target_parts.netloc}"
    home = origin + "/"
    root_page = fetch_html(home) if first["url"].rstrip("/") != home.rstrip("/") else first

    pages: list[dict] = []
    seen_urls: set[str] = set()
    for page in (root_page, first):
        if page["url"] not in seen_urls:
            pages.append(page)
            seen_urls.add(page["url"])

    root_soup = BeautifulSoup(root_page["html"], "html.parser")
    root_objects = structured_objects(root_soup)
    candidate_urls: list[tuple[int, str]] = []
    categories: list[tuple[str, str]] = []
    for anchor in root_soup.select("a[href]"):
        href = urljoin(root_page["url"], anchor.get("href", ""))
        label = clean_label(anchor.get("aria-label", "") or anchor.get("title", "") or anchor.get_text(" ", strip=True))
        if not label or not same_site(href, origin):
            continue
        if is_non_catalog_anchor(anchor, href, label):
            continue
        if is_category_link(label, href) and urlsplit(href).path.strip("/"):
            if valid_link_target(href, origin) and href.rstrip("/") != home.rstrip("/"):
                categories.append((href, label))
        if valid_link_target(href, origin):
            score = 0
            if anchor.select_one("img") or anchor.find_parent(["article", "li"]):
                score += 3
            if is_category_link(label, href):
                score += 2
            if len(urlsplit(href).path.strip("/").split("/")) >= 2:
                score += 1
            candidate_urls.append((score, href))

    for _, candidate in sorted(candidate_urls, key=lambda item: item[0], reverse=True):
        if len(pages) >= MAX_PAGES:
            break
        if candidate in seen_urls:
            continue
        seen_urls.add(candidate)
        try:
            page = fetch_html(candidate)
        except (requests.RequestException, ValueError, RuntimeError, OSError):
            continue
        if "html" not in page["content_type"].lower() and "<html" not in page["html"][:1200].lower():
            continue
        pages.append(page)

    # Prefer category links with descriptive names and keep labels short/unique.
    normalized_categories: list[tuple[str, str]] = []
    category_seen: set[str] = set()
    for href, label in categories:
        key = href.rstrip("/")
        if key in category_seen or not same_site(href, origin):
            continue
        category_seen.add(key)
        label = label or urlsplit(href).path.strip("/").split("/")[-1].replace("-", " ").title()
        if len(label) < 2 or label.lower() in {"ana sayfa", "home", "menu", "menü"}:
            continue
        if is_utility_link(label, href):
            continue
        normalized_categories.append((href, label))
    normalized_categories = normalized_categories[:9]

    sample_titles = extract_catalogue_samples(pages, origin, normalized_categories)

    search_info = find_search_form(root_soup, root_page["url"])
    if not search_info["action"]:
        for page in pages[1:]:
            form_soup = BeautifulSoup(page["html"], "html.parser")
            found = find_search_form(form_soup, page["url"])
            if found["action"]:
                search_info = found
                break

    all_text = "\n".join(soup_text(BeautifulSoup(p["html"], "html.parser")) for p in pages)
    all_html = "\n".join(p["html"] for p in pages)
    all_objects: list[dict] = []
    page_titles: list[str] = []
    same_host_image_count = 0
    card_link_count = 0
    episode_label_count = 0
    season_label_count = 0
    subtitle_link_count = 0
    for page in pages:
        soup = BeautifulSoup(page["html"], "html.parser")
        objects = structured_objects(soup)
        all_objects.extend(objects)
        page_titles.append(page_title(soup, objects))
        same_host_image_count += len(soup.select("img[src], img[data-src], img[data-lazy-src], img[data-original]"))
        card_link_count += sum(
            1 for a in soup.select("a[href]")
            if a.select_one("img") or a.find_parent(["article", "li"])
        )
        for a in soup.select("a[href]"):
            combined = f"{a.get_text(' ', strip=True)} {a.get('href', '')}".lower()
            if re.search(r"(?:episode|episodes|ep[\W_-]?\d|b[öo]l[üu]m|x\d{1,3})", combined):
                episode_label_count += 1
            if re.search(r"(?:season|sezon)[\W_-]?\d", combined):
                season_label_count += 1
            if re.search(r"\.(?:srt|vtt|ass|ttml)(?:\?|$)", a.get("href", ""), re.I):
                subtitle_link_count += 1
        subtitle_link_count += len(soup.select("track[src], track[data-src], track[data-subtitle]"))

    name = (
        first_json_text(all_objects, "alternateName")
        or page_title(root_soup, root_objects)
        or (target_parts.hostname or "Site").split(".")[0].title()
    )
    # og:site_name is preferred; avoid treating a page-specific title as a site name.
    site_name_node = root_soup.select_one('meta[property="og:site_name"], meta[name="application-name"]')
    if site_name_node and site_name_node.get("content"):
        name = clean_label(site_name_node["content"], 70)

    hostname = (target_parts.hostname or "site").lower().removeprefix("www.")
    host_parts = re.findall(r"[A-Za-z0-9]+", hostname)
    class_name = "Auto" + "".join(part[:1].upper() + part[1:] for part in host_parts)
    class_name = re.sub(r"[^A-Za-z0-9]", "", class_name)[:48]
    if not class_name or not class_name[0].isalpha():
        class_name = "AutoSite"
    module_name = class_name

    type_values: list[str] = []
    for obj in all_objects:
        typ = obj.get("@type")
        if isinstance(typ, str):
            type_values.append(typ.lower())
        elif isinstance(typ, list):
            type_values.extend(str(item).lower() for item in typ)
    has_schema_type = any("movie" in x or "tvseries" in x or "tvepisode" in x for x in type_values)

    lower_html = all_html.lower()
    lower_text = all_text.lower()
    rating_found = any(
        key in lower_html for key in ("aggregaterating", "ratingvalue", "imdb-rating", "imdb_rating", "imdb_score", "imdbscore")
    ) or bool(re.search(
        r"(?i)(?:IMDb|IMDB|puan|rating)\s*[:/\-]?\s*(?:10(?:[.,]0)?|[0-9](?:[.,][0-9])?)",
        all_text,
    ))
    poster_found = any(
        key in lower_html for key in ('property="og:image"', "name=\"twitter:image\"", "itemprop=\"image\"")
    ) or same_host_image_count > 0
    actor_found = any(key in lower_html or key in lower_text for key in ("actor", "cast", "oyuncu", "oyuncular"))
    genre_found = any(key in lower_html for key in ("genre", "/tur/", "/kategori/", "itemprop=\"genre\""))
    season_found = season_label_count > 0 or any("tvseason" in value for value in type_values)
    episode_found = episode_label_count > 0 or any("tvepisode" in value for value in type_values)
    subtitle_found = subtitle_link_count > 0 or bool(
        re.search(r"""(?i)[^\s"']+\.(?:srt|vtt|ass|ttml)(?:\?[^\s"']*)?""", all_html)
    )
    player_found = bool(
        re.search(r"\.(?:m3u8|mpd|mp4|m4v)(?:[?\"'&\s]|$)", lower_html)
        or any(x in lower_html for x in ("<iframe", "jwplayer", "videojs", "data-player", "data-embed"))
    )
    media_probes = probe_media_urls(pages)
    episode_inventory = extract_episode_inventory(pages)
    blocked = any(
        p["status"] in {401, 403, 429} or
        any(marker in p["html"].lower() for marker in (
            "cf-chl-", "just a moment", "verify you are human", "captcha challenge",
            "checking your browser", "access denied",
        ))
        for p in pages
    )

    return {
        "input_url": target,
        "origin": origin,
        "root_url": root_page["url"],
        "http_status": root_page["status"],
        "site_name": clean_label(name, 70) or hostname,
        "host": hostname,
        "class_name": class_name,
        "module_name": module_name,
        "pages_checked": pages,
        "categories": normalized_categories,
        "sample_titles": sample_titles,
        "search": search_info,
        "signals": {
            "poster": poster_found,
            "imdb_rating": rating_found,
            "actors": actor_found,
            "genres": genre_found,
            "seasons": season_found,
            "episodes": episode_found,
            "subtitles": subtitle_found,
            "player_embed_or_media": player_found,
            "media_endpoint_reachable": any(item["reachable"] for item in media_probes),
            "schema_metadata": bool(all_objects),
            "search_form": bool(search_info["action"]),
            "listing_cards": card_link_count > 0,
            "series_or_movie_schema": has_schema_type,
        },
        "counts": {
            "pages": len(pages),
            "images": same_host_image_count,
            "card_like_links": card_link_count,
            "season_links": season_label_count,
            "episode_links": episode_label_count,
            "subtitle_hints": subtitle_link_count,
            "schema_objects": len(all_objects),
            "episodes_detected": episode_inventory["episodes_detected"],
            "episode_seasons_detected": episode_inventory["seasons_detected"],
            "episode_gaps": len(episode_inventory["potential_gaps"]),
            "media_urls_probed": len(media_probes),
            "media_urls_reachable": sum(1 for item in media_probes if item["reachable"]),
        },
        "media_probes": media_probes,
        "episode_inventory": episode_inventory,
        "blocked": blocked,
        "page_titles": page_titles,
    }


def kotlin_string(value: str) -> str:
    return json.dumps(value, ensure_ascii=False)


def render_kotlin(config: dict) -> str:
    main_pages = [(config["origin"] + "/", "Ana Sayfa")]
    seen = {main_pages[0][0].rstrip("/")}
    for url, label in config["categories"]:
        if url.rstrip("/") in seen:
            continue
        seen.add(url.rstrip("/"))
        main_pages.append((url, label))
    page_entries = ",\n".join(
        "        " + kotlin_string(url) + " to " + kotlin_string(label)
        for url, label in main_pages
    )
    search = config["search"]
    action = search["action"] if search.get("method") == "get" else ""
    field = search.get("field") or "s"

    template = r'''package com.neoncs3

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

// AUTO-GENERATED by tools/auto_plugin_generator.py. Site-specific behaviour is inferred from public HTML.
class __CLASS__ : NeonMainAPI() {
    override var mainUrl = __ORIGIN__
    override var name = __SITE_NAME__
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var sequentialMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val configuredSearchAction = __SEARCH_ACTION__
    private val configuredSearchField = __SEARCH_FIELD__
    // Scraped from actual poster-backed catalogue cards; consumed by the real-playback smoke test.
    private val neonSmokeTestQueries = listOf(__SMOKE_QUERIES__)

    override val mainPage = mainPageOf(
__MAIN_PAGES__
    )

    private fun headers(referer: String = mainUrl + "/"): Map<String, String> =
        neonHeaders + mapOf(
            "Referer" to referer,
            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
        )

    private fun siteHost(url: String): String =
        runCatching { URI(url).host.orEmpty().lowercase().removePrefix("www.") }.getOrDefault("")

    private fun isSiteUrl(url: String): Boolean =
        (url.startsWith("http://", true) || url.startsWith("https://", true)) &&
            siteHost(url) == siteHost(mainUrl)

    // ddizimag.com exposes this root-level URL in page scripts, but it is not a playable video.
    private fun isBrokenSiteManifest(raw: String): Boolean {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return false
        return siteHost(raw) == siteHost(mainUrl) &&
            uri.path.orEmpty().trimEnd('/').equals("/manifest.webm", ignoreCase = true)
    }

    private fun normalize(raw: String, base: String = mainUrl): String =
        neonNormalizeUrl(raw, base)

    private fun isLikelyPlayerUrl(url: String): Boolean {
        if (neonIsMediaUrl(url)) return true
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val hint = (uri.path.orEmpty() + "?" + uri.rawQuery.orEmpty()).lowercase()
        return Regex("""(?i)(?:player|embed|iframe|video|stream|play|watch|manifest|media|playlist|source)(?:[/_.?=&-]|$)""")
            .containsMatchIn(hint)
    }

    // Player settings are sometimes stored in JSON/JavaScript instead of iframe or video tags.
    private fun playerUrlsFromText(raw: String, baseUrl: String): List<String> {
        val text = raw
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&#x2F;", "/")
            .replace("&amp;", "&")
        val values = LinkedHashSet<String>()
        neonExtractMediaUrls(text).forEach(values::add)

        val namedValuePattern = Regex(
            """(?is)["']?(?:src|file|file_url|source|source_url|url|embed|embed_url|iframe|iframe_url|video_url|video_src|videoUrl|play_url|player_url|playerUrl|hls|hls_url|playlist|stream|stream_url|media_url|manifest)["']?\s*[:=]\s*["']([^"'<>\s]+)["']"""
        )
        namedValuePattern.findAll(text).forEach { values.add(it.groupValues[1]) }

        val relativePlayerPattern = Regex(
            """(?i)["'](/[^"'<>\s]*(?:player|embed|iframe|watch|video|stream|play|manifest|media|playlist|source)[^"'<>\s]*)["']"""
        )
        relativePlayerPattern.findAll(text).forEach { values.add(it.groupValues[1]) }

        return values.mapNotNull { rawValue ->
            val cleaned = rawValue.trim().trimEnd('"', '\'', ')', ']', '}', ',', ';')
            val url = normalize(cleaned, baseUrl)
            url.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
                ?.takeIf(::isLikelyPlayerUrl)
        }.distinct()
    }

    private fun isEpisodeLike(value: String): Boolean =
        Regex("""(?i)(?:b[öo]l[üu]m|episodes?|ep[\s._-]*\d|s\d{1,2}e\d{1,3}|\d{1,2}\s*[x×]\s*\d{1,3})""")
            .containsMatchIn(value)

    private fun normalizedFilterText(value: String): String =
        value.lowercase()
            .replace("ı", "i")
            .replace("ş", "s")
            .replace("ğ", "g")
            .replace("ü", "u")
            .replace("ö", "o")
            .replace("ç", "c")
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()

    private val navigationOnlyLabels = setOf(
        "yorum", "yorumlar", "comment", "comments", "replies",
        "kesfet", "kesif", "explore", "discover",
        "takvim", "takvimi", "calendar", "schedule",
        "dizi takvimi", "film takvimi", "tv schedule",
        "tartisma", "tartismalar", "discussion", "discussions", "discuss",
        "baslik", "basliklar", "topic", "topics", "forum", "forums",
        "thread", "threads", "konu", "konular", "sohbet", "sohbetler",
        "community", "topluluk", "gundem", "duyurular",
        "login", "register", "giris", "kayit", "hesabim", "profil", "profilim",
        "profile", "profiles", "user", "users", "member", "members",
        "author", "authors", "account", "accounts", "uye", "uyeler",
        "kullanici", "kullanicilar", "ayarlar", "iletisim", "hakkimizda", "yardim",
        "privacy policy", "terms", "about us", "contact us",
        "ana sayfa", "home", "menu", "menü",
        "dizi", "diziler", "film", "filmler", "movies", "series",
        "izle", "tum diziler", "tum filmler", "tum bolumler",
        "en son bolumler", "son bolumler", "populer", "populer filmler"
    )

    private val utilityLabelPrefixes = setOf(
        "yorum", "yorumlar", "comment", "comments", "reply", "replies",
        "kesfet", "kesif", "explore", "discover", "takvim", "takvimi",
        "calendar", "schedule", "dizi takvimi", "film takvimi", "tv schedule",
        "tartisma", "tartismalar", "discussion", "discussions", "discuss",
        "baslik", "basliklar", "topic", "topics", "forum", "forums",
        "thread", "threads", "konu", "konular", "sohbet", "sohbetler",
        "community", "topluluk", "gundem", "duyurular",
        "profil", "profile", "user profile", "member profile",
        "kullanici profili", "uye profili", "my profile"
    )

    private val navigationOnlyPaths = setOf(
        "yorum", "yorumlar", "comment", "comments", "replies",
        "kesfet", "kesif", "explore", "discover", "takvim", "takvimi",
        "calendar", "schedule", "dizi takvimi", "film takvimi",
        "tartisma", "tartismalar", "discussion", "discussions", "discuss",
        "baslik", "basliklar", "topic", "topics", "forum", "forums",
        "thread", "threads", "konu", "konular", "sohbet", "sohbetler",
        "community", "topluluk", "gundem", "duyurular",
        "profile", "profiles", "profil", "profiller", "profilim",
        "user", "users", "member", "members", "author", "authors",
        "account", "accounts", "u", "uye", "uyeler", "kullanici",
        "kullanicilar", "avatar", "avatars", "activity", "notifications",
        "messages", "inbox", "groups", "group", "settings", "dashboard"
    )

    private fun isNavigationChrome(anchor: Element): Boolean {
        var node: Element? = anchor
        repeat(7) {
            val current = node ?: return false
            val tag = current.tagName().lowercase()
            if (tag == "nav" || tag == "footer" ||
                current.attr("role").equals("navigation", ignoreCase = true)) {
                return true
            }

            val hint = normalizedFilterText(current.id() + " " + current.className())
            val nonCatalogMarkers = listOf(
                "site header", "site nav", "navbar", "main nav", "primary nav",
                "main menu", "primary menu", "top menu", "menu item", "sidebar",
                "breadcrumb", "comments nav", "site footer", "footer menu",
                "profile", "user profile", "user card", "member card", "avatar",
                "author box", "author link", "discussion", "thread card",
                "forum topic", "topic card", "comment list", "reply list",
                "user meta", "member meta", "profile meta", "user info",
                "member info", "profile info", "user summary", "member summary"
            )
            if (nonCatalogMarkers.any { hint.contains(it) }) return true

            val image = current.selectFirst("img")
            if (image != null) {
                val imageHint = normalizedFilterText(
                    image.attr("class") + " " + image.id() + " " +
                        image.attr("alt") + " " + image.attr("title") + " " +
                        image.attr("src") + " " + image.attr("data-src")
                )
                if (listOf(
                        "avatar", "user photo", "profile photo", "profile picture",
                        "member photo", "author photo", "account photo"
                    ).any { imageHint.contains(it) }) return true
            }
            node = current.parent()
        }
        return false
    }

    private fun isNavigationItem(title: String, url: String): Boolean {
        val label = normalizedFilterText(title)
        if (label in navigationOnlyLabels ||
            utilityLabelPrefixes.any { label == it || label.startsWith("$it ") }) {
            return true
        }

        val uri = runCatching { URI(url) }.getOrNull() ?: return true
        val rawSegments = uri.path.orEmpty().split("/").filter { it.isNotBlank() }
        if (rawSegments.any { it.startsWith("@") }) return true
        val segments = rawSegments.map {
            normalizedFilterText(it.replace('-', ' ').replace('_', ' '))
        }
        if (segments.any { it in navigationOnlyPaths }) return true

        val query = uri.rawQuery.orEmpty()
        if (Regex(
                "(?i)(?:^|&)(?:user|userid|user_id|profile|profile_id|member|member_id|author|author_id|thread|thread_id|topic|topic_id|discussion|comment|reply|post_id)="
            ).containsMatchIn(query)) return true

        if (Regex("""(?i)^.{1,40}\s+\d{1,2}\s*(?:level|lvl)\b.*$""")
                .matches(title.trim())) return true
        if (Regex("""(?i)(?:\+\s*\d[\d,.]*\s*(?:puan|points?)|(?:puan|points?)\s*\+?\s*\d[\d,.]*)""")
                .containsMatchIn(title)) return true

        if (segments.size == 1 && segments.first() in setOf(
                "dizi", "diziler", "film", "filmler", "movies", "series",
                "home", "ana sayfa", "kesfet", "takvim", "yorumlar"
            )) return true

        return false
    }

    private fun isFlagOrIconAsset(value: String): Boolean {
        val hint = normalizedFilterText(value)
        if (listOf(
                "flag icon", "country flag", "language flag", "flag sprite"
            ).any { hint.contains(it) }) return true
        if (Regex("""(?i)(?:^|/)(?:flags?|flag-icons|country-flags?)/""")
                .containsMatchIn(value)) return true
        val file = value.substringBefore('?').substringBefore('#')
            .substringAfterLast('/').lowercase()
        return Regex(
            """(?i)^(?:flags?|flag[-_]icon|icon[-_]flag|country[-_]?flag|flag[-_]sprite|language[-_]?flag)(?:[-_][a-z]{2})?\.(?:svg|png|jpe?g|webp|gif|ico)$"""
        ).matches(file)
    }

    private fun isValidPosterUrl(url: String): Boolean {
        if (isFlagOrIconAsset(url)) return false
        if (!url.startsWith("https://", true) && !url.startsWith("http://", true)) {
            return false
        }
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val path = uri.path.orEmpty().lowercase()
        val file = path.substringAfterLast('/')
        if (listOf(
                "/avatar/", "/avatars/", "/user/", "/users/", "/profile/",
                "/profiles/", "/member/", "/members/", "/author/", "/authors/",
                "/account/", "/accounts/", "/community/", "/forum/", "/forums/"
            ).any { path.contains(it) }) return false
        val placeholderMarkers = listOf(
            "placeholder", "no-image", "no_image", "noimage", "no-poster",
            "noposter", "blank.gif", "transparent", "avatar", "favicon",
            "spinner", "loading", "pixel", "missing", "not-found",
            "user-photo", "profile-photo", "member-photo", "author-photo"
        )
        if (placeholderMarkers.any { file.contains(it) }) return false
        if (Regex("""(?i)^logo(?:[-_].*)?\.(?:png|jpe?g|webp|gif|svg|ico)$""")
                .matches(file)) return false
        return file.isNotBlank()
    }

    private fun isDetailUrl(raw: String, base: String = mainUrl): Boolean {
        val url = normalize(raw, base)
        if (!url.startsWith("http", true) || siteHost(url) != siteHost(mainUrl)) return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val path = uri.path.orEmpty().lowercase()
        if (path.isBlank() || path == "/") return false
        if (listOf(
                "/wp-admin", "/wp-content", "/wp-json", "/tag/", "/category/", "/genre/",
                "/kategori/", "/tur/", "/oyuncu/", "/actor/", "/search/", "/page/",
                "/login", "/register", "/giris", "/kayit", "/iletisim", "/contact",
                "/privacy", "/terms", "/feed", "/yorum", "/comment", "/kesfet",
                "/discover", "/takvim", "/calendar", "/tartisma", "/discussion",
                "/baslik", "/topics", "/forum", "/profile", "/profil", "/user",
                "/member", "/author", "/account", "/uye", "/kullanici", "/threads",
                "/thread", "/konu", "/konular", "/community", "/sohbet", "/replies"
            ).any { path.contains(it) }) return false
        if (listOf(
                ".jpg", ".jpeg", ".png", ".webp", ".gif", ".svg", ".ico",
                ".css", ".js", ".m3u8", ".mpd", ".mp4", ".webm", ".m4v",
                ".mov", ".flv", ".avi", ".mkv", ".mpeg", ".mpg", ".3gp",
                ".ts", ".m4s", ".mp3", ".m4a", ".aac", ".ogg", ".wav",
                ".srt", ".vtt", ".ass", ".ssa", ".sub", ".idx", ".woff",
                ".woff2", ".ttf", ".eot", ".webmanifest"
            ).any { path.endsWith(it) }) return false
        return path.trim('/').isNotBlank()
    }

    private fun containerFor(anchor: Element): Element {
        var node: Element? = anchor
        repeat(5) {
            if (node == null) return@repeat
            if (node!!.selectFirst("img") != null) return node!!
            node = node!!.parent()
        }
        return anchor
    }

    private fun imageUrl(image: Element?, baseUrl: String): String? {
        if (image == null) return null
        val imageHint = normalizedFilterText(
            image.attr("class") + " " + image.id() + " " +
                image.attr("alt") + " " + image.attr("title")
        )
        if (isFlagOrIconAsset(
                imageHint + " " + image.attr("src") + " " + image.attr("data-src") + " " +
                    image.attr("data-lazy-src") + " " + image.attr("data-original") + " " +
                    image.attr("data-poster")
            )) return null
        if (listOf(
                "avatar", "user photo", "profile photo", "profile picture",
                "member photo", "author photo", "account photo", "user profile"
            ).any { imageHint.contains(it) }) return null
        return sequenceOf(
            image.attr("data-src"),
            image.attr("data-lazy-src"),
            image.attr("data-original"),
            image.attr("data-poster"),
            image.attr("data-image"),
            image.attr("src")
        )
            .filter { it.isNotBlank() && !it.startsWith("data:", true) }
            .map { normalize(it, baseUrl) }
            .firstOrNull { isValidPosterUrl(it) }
    }

    private fun posterFor(anchor: Element, baseUrl: String): String? {
        return imageUrl(anchor.selectFirst("img"), baseUrl)
            ?: imageUrl(containerFor(anchor).selectFirst("img"), baseUrl)
    }

    private fun candidateAnchors(document: Document): List<Element> {
        return document.select(
            "article a[href], .movie-card a[href], .film-card a[href], .dizi-card a[href], " +
                ".post a[href], .item a[href], .card a[href], .movie a[href], .film a[href], " +
                ".dizi a[href], .poster a[href], .videos a[href], a[href]"
        )
            .filterNot { isNavigationChrome(it) }
            .distinctBy { normalize(it.attr("href"), mainUrl) }
    }

    private fun responseFromAnchor(anchor: Element, baseUrl: String): SearchResponse? {
        val href = normalize(anchor.attr("href"), baseUrl)
        if (!isDetailUrl(href, baseUrl)) return null
        val container = containerFor(anchor)
        val image = anchor.selectFirst("img") ?: container.selectFirst("img")
        val title = sequenceOf(
            anchor.attr("title"),
            anchor.attr("aria-label"),
            anchor.selectFirst("h1, h2, h3, h4, .title, .name, .movie-title, .film-title, .dizi-title")?.text(),
            image?.attr("alt"),
            anchor.text(),
            container.attr("title")
        ).mapNotNull { neonCleanText(it) }
            .firstOrNull { it.length in 2..160 } ?: return null
        if (title.equals(name, true) || title.equals("Ana Sayfa", true)) return null
        if (isEpisodeLike(title + " " + href)) return null
        if (isNavigationItem(title, href) || isNavigationChrome(anchor)) return null

        val poster = posterFor(anchor, baseUrl) ?: return null
        if (!isValidPosterUrl(poster)) return null
        val scoreValue = neonExtractRating(container.text())
        val series = Regex("""(?i)(dizi|series|season|sezon|tv-show|tvseries)""")
            .containsMatchIn(title + " " + href)
        val posterHeaders = headers(baseUrl)
        return if (series) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
                this.posterHeaders = posterHeaders
                scoreValue?.let { score = Score.from10(it) }
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
                this.posterHeaders = posterHeaders
                scoreValue?.let { score = Score.from10(it) }
            }
        }
    }

    private fun resultsFrom(document: Document, baseUrl: String): List<SearchResponse> {
        return candidateAnchors(document)
            .mapNotNull { responseFromAnchor(it, baseUrl) }
            .distinctBy { it.url }
            .take(120)
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val base = request.data
        val candidates = if (page <= 1) listOf(base) else listOf(
            base.trimEnd('/') + "/page/" + page + "/",
            base + (if (base.contains("?")) "&" else "?") + "page=" + page,
            base + (if (base.contains("?")) "&" else "?") + "paged=" + page,
        ).distinct()
        for (candidate in candidates) {
            val document = runCatching {
                app.get(candidate, headers = headers(candidate), timeout = 12_000L).document
            }.getOrNull() ?: continue
            val results = resultsFrom(document, candidate)
            if (results.isNotEmpty()) {
                return newHomePageResponse(request.name, results, hasNext = page < 100)
            }
        }
        return newHomePageResponse(request.name, emptyList(), hasNext = false)
    }

    private fun searchUrls(encoded: String): List<String> {
        val urls = ArrayList<String>()
        if (configuredSearchAction.isNotBlank()) {
            val separator = if (configuredSearchAction.contains("?")) "&" else "?"
            urls += configuredSearchAction + separator +
                URLEncoder.encode(configuredSearchField, "UTF-8") + "=" + encoded
        }
        urls += mainUrl + "/?s=" + encoded
        urls += mainUrl + "/?search=" + encoded
        urls += mainUrl + "/?q=" + encoded
        urls += mainUrl + "/search/" + encoded + "/"
        return urls.distinct()
    }

    private fun matchesQuery(result: SearchResponse, query: String): Boolean {
        val wanted = normalizedFilterText(query)
        if (wanted.length < 2) return false
        val title = normalizedFilterText(result.name)
        val path = runCatching {
            normalizedFilterText(URI(result.url).path.orEmpty())
        }.getOrDefault("")
        if (title.contains(wanted) || path.contains(wanted)) return true

        val genericWords = setOf(
            "the", "and", "of", "with", "for", "bir", "ve", "ile",
            "izle", "full", "hd", "film", "filmler", "dizi", "diziler",
            "movie", "movies", "series", "watch"
        )
        val terms = wanted.split(" ").filter { it.length >= 2 && it !in genericWords }
        return terms.size >= 2 && terms.all { term -> title.contains(term) || path.contains(term) }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        if (cleanQuery.length < 2) return emptyList()
        val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
        for (searchUrl in searchUrls(encoded)) {
            val response = runCatching {
                app.get(searchUrl, headers = headers(searchUrl), timeout = 12_000L)
            }.getOrNull() ?: continue
            // Some sites ignore ?s= / ?q= and return the home page for every query.
            // Never leak those unrelated home-page items into search results.
            val matches = resultsFrom(response.document, searchUrl)
                .filter { matchesQuery(it, cleanQuery) }
            if (matches.isNotEmpty()) return matches
        }
        return emptyList()
    }

    private fun structuredObjects(document: Document): List<JSONObject> {
        val found = ArrayList<JSONObject>()
        fun visit(value: Any?) {
            when (value) {
                is JSONObject -> {
                    found.add(value)
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val child = value.opt(key)
                        if (child is JSONObject || child is JSONArray) visit(child)
                    }
                }
                is JSONArray -> {
                    for (i in 0 until value.length()) {
                        val child = value.opt(i)
                        if (child is JSONObject || child is JSONArray) visit(child)
                    }
                }
            }
        }
        document.select("script[type*=ld+json]").forEach { script ->
            val raw = script.data().ifBlank { script.html() }
            runCatching {
                val value = raw.trim().removeSuffix(";")
                if (value.startsWith("[")) visit(JSONArray(value)) else visit(JSONObject(value))
            }
        }
        return found
    }

    private fun jsonText(value: Any?): String? {
        return when (value) {
            is String -> value.takeIf { it.isNotBlank() }
            is JSONObject -> sequenceOf(
                value.optString("url").takeIf { it.isNotBlank() && it != "null" },
                value.optString("name").takeIf { it.isNotBlank() && it != "null" },
                value.optString("@id").takeIf { it.isNotBlank() && it != "null" }
            ).firstOrNull { it != null }
            is JSONArray -> (0 until value.length()).asSequence()
                .mapNotNull { jsonText(value.opt(it)) }.firstOrNull()
            else -> null
        }
    }

    private fun schemaValue(document: Document, vararg keys: String): String? {
        val objects = structuredObjects(document)
        for (key in keys) {
            for (obj in objects) {
                jsonText(obj.opt(key))?.let { return it }
            }
        }
        return null
    }

    private fun schemaRating(document: Document): Double? {
        for (obj in structuredObjects(document)) {
            val aggregate = obj.optJSONObject("aggregateRating")
            val raw = aggregate?.optString("ratingValue")
                ?: obj.optString("ratingValue").takeIf { it.isNotBlank() && it != "null" }
            val value = raw?.replace(',', '.')?.toDoubleOrNull()
            if (value != null && value in 0.0..10.0) return value
        }
        return null
    }

    private fun schemaGenres(document: Document): List<String> {
        val values = ArrayList<String>()
        for (obj in structuredObjects(document)) {
            val raw = obj.opt("genre")
            when (raw) {
                is String -> values.add(raw)
                is JSONArray -> for (i in 0 until raw.length()) {
                    raw.optString(i).takeIf { it.isNotBlank() && it != "null" }?.let(values::add)
                }
            }
        }
        return values.map { it.trim() }.filter { it.length in 2..40 }.distinct().take(30)
    }

    private fun schemaTypes(document: Document): Set<String> {
        return structuredObjects(document).flatMap { obj ->
            when (val raw = obj.opt("@type")) {
                is String -> listOf(raw.lowercase())
                is JSONArray -> (0 until raw.length()).map { raw.optString(it).lowercase() }
                else -> emptyList()
            }
        }.toSet()
    }

    private fun seasonNumber(value: String): Int? {
        val before = Regex("""(?i)\b0?(\d{1,2})\s*\.?\s*(?:season|sezon)\b""")
            .find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (before != null) return before
        return Regex("""(?i)(?:season|sezon|s)[\s._/-]*0?(\d{1,2})""")
            .find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun episodeInfo(value: String, defaultSeason: Int?): Pair<Int, Int>? {
        val full = Regex(
            """(?i)(?:s|season|sezon)[\s._/-]*0?(\d{1,2})[\s._/-]*(?:e|ep|episodes?|b[öo]l[üu]m)[\s._/-]*0?(\d{1,3})"""
        ).find(value)
        if (full != null) {
            val season = full.groupValues[1].toIntOrNull() ?: defaultSeason ?: 1
            val episode = full.groupValues[2].toIntOrNull() ?: return null
            return season to episode
        }
        val compact = Regex("""(?i)\b(?:s)?0?(\d{1,2})\s*[x×]\s*0?(\d{1,3})\b""").find(value)
        if (compact != null) {
            return (compact.groupValues[1].toIntOrNull() ?: defaultSeason ?: 1) to
                (compact.groupValues[2].toIntOrNull() ?: return null)
        }
        val turkish = Regex(
            """(?i)(\d{1,2})\s*\.?\s*sezon.{0,24}?(\d{1,3})\s*\.?\s*b[öo]l[üu]m"""
        ).find(value)
        if (turkish != null) {
            return (turkish.groupValues[1].toIntOrNull() ?: 1) to
                (turkish.groupValues[2].toIntOrNull() ?: return null)
        }
        val onlyEpisode = Regex(
            """(?i)(?:b[öo]l[üu]m|episodes?|ep[\s._-]*)[\s._/#:-]*0?(\d{1,3})|\b(\d{1,3})\s*\.?\s*b[öo]l[üu]m"""
        ).find(value) ?: return null
        val number = (onlyEpisode.groupValues.getOrNull(1)?.toIntOrNull()
            ?: onlyEpisode.groupValues.getOrNull(2)?.toIntOrNull()) ?: return null
        return (seasonNumber(value) ?: defaultSeason ?: 1) to number
    }

    private data class EpisodeItem(
        val url: String,
        val title: String,
        val season: Int,
        val episode: Int,
        val poster: String?
    )

    private fun seasonLinks(document: Document, baseUrl: String): List<Pair<String, Int?>> {
        return document.select("a[href]").mapNotNull { anchor ->
            val href = normalize(anchor.attr("href"), baseUrl)
            if (!isDetailUrl(href, baseUrl) || href == baseUrl) return@mapNotNull null
            val text = anchor.text() + " " + anchor.attr("title") + " " + href
            if (episodeInfo(text, null) != null) return@mapNotNull null
            val season = seasonNumber(text) ?: return@mapNotNull null
            href to season
        }.distinctBy { it.first }.take(12)
    }

    private fun episodeItems(
        document: Document,
        baseUrl: String,
        defaultSeason: Int?
    ): List<EpisodeItem> {
        return document.select("a[href]").mapNotNull { anchor ->
            val href = normalize(anchor.attr("href"), baseUrl)
            if (!isDetailUrl(href, baseUrl) || href == baseUrl) return@mapNotNull null
            val container = containerFor(anchor)
            val title = sequenceOf(
                anchor.attr("title"),
                anchor.attr("aria-label"),
                anchor.text(),
                container.text(),
                href
            ).mapNotNull { neonCleanText(it) }.firstOrNull { it.length in 1..180 } ?: return@mapNotNull null
            val combined = title + " " + href + " " + anchor.classNames().joinToString(" ")
            val numbers = episodeInfo(combined, defaultSeason) ?: return@mapNotNull null
            if (numbers.second !in 1..999 || numbers.first !in 1..99) return@mapNotNull null
            EpisodeItem(
                url = href,
                title = title,
                season = numbers.first,
                episode = numbers.second,
                poster = posterFor(anchor, baseUrl)
            )
        }.distinctBy { it.url }
    }

    private fun cleanSeriesTitle(value: String): String {
        return value.replace(
            Regex("""(?i)[\s\-:|]*(?:s\d{1,2}e\d{1,3}|\d{1,2}\s*\.?\s*sezon\s*\d*\s*\.?\s*b[öo]l[üu]m.*)$"""),
            ""
        ).trim().ifBlank { value }
    }

    override suspend fun load(url: String): LoadResponse? {
        val normalizedUrl = normalize(url)
        val document = runCatching {
            app.get(normalizedUrl, headers = headers(normalizedUrl), timeout = 15_000L).document
        }.getOrNull() ?: return null
        val title = neonPageTitle(document) ?: schemaValue(document, "name") ?: return null
        val poster = neonPoster(document, normalizedUrl)
            ?: schemaValue(document, "image", "thumbnailUrl")
                ?.let { normalize(it, normalizedUrl) }
                ?.takeIf { it.startsWith("http", true) }
        val plot = schemaValue(document, "description") ?: neonPlot(document)
        val year = Regex("""(?<!\d)(?:19|20)\d{2}(?!\d)""")
            .find(schemaValue(document, "datePublished", "dateCreated").orEmpty())
            ?.value?.toIntOrNull() ?: neonExtractYear(document.text())
        val rating = schemaRating(document) ?: neonExtractRating(document.text())
        val tags = schemaGenres(document).ifEmpty { neonExtractGenres(document) }
        val actors = neonExtractActors(document, normalizedUrl)

        val linkedSeasons = seasonLinks(document, normalizedUrl)
        val foundEpisodes = ArrayList<EpisodeItem>()
        foundEpisodes += episodeItems(document, normalizedUrl, seasonNumber(normalizedUrl))
        for ((seasonUrl, seasonNo) in linkedSeasons) {
            val seasonDocument = runCatching {
                app.get(seasonUrl, headers = headers(normalizedUrl), timeout = 10_000L).document
            }.getOrNull() ?: continue
            foundEpisodes += episodeItems(seasonDocument, seasonUrl, seasonNo ?: seasonNumber(seasonUrl))
        }
        val episodes = foundEpisodes.distinctBy { it.url }
            .sortedWith(compareBy<EpisodeItem> { it.season }.thenBy { it.episode }.thenBy { it.url })
        val types = schemaTypes(document)
        val episodePage = episodeInfo(title + " " + normalizedUrl, seasonNumber(normalizedUrl)) != null
        val series = episodes.isNotEmpty() || linkedSeasons.isNotEmpty() ||
            types.any { it.contains("tvseries") || it.contains("tvseason") || it.contains("tvepisode") } ||
            Regex("""(?i)(/dizi/|/series/|/tv-show|tvseries|season|sezon)""")
                .containsMatchIn(normalizedUrl + " " + title)

        val episodeResponses = episodes.mapIndexed { index, item ->
            newEpisode(item.url) {
                name = item.title
                season = item.season
                episode = item.episode
                posterUrl = item.poster ?: poster
            }
        }.ifEmpty {
            if (episodePage || types.any { it.contains("tvepisode") }) {
                listOf(newEpisode(normalizedUrl) {
                    name = title
                    season = seasonNumber(normalizedUrl) ?: 1
                    episode = episodeInfo(title + " " + normalizedUrl, seasonNumber(normalizedUrl))?.second ?: 1
                    posterUrl = poster
                })
            } else emptyList()
        }

        val response: LoadResponse = if (series) {
            newTvSeriesLoadResponse(cleanSeriesTitle(title), normalizedUrl, TvType.TvSeries, episodeResponses) {
                posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                rating?.let { score = Score.from10(it) }
                addActors(actors)
            }
        } else {
            newMovieLoadResponse(title, normalizedUrl, TvType.Movie, normalizedUrl) {
                posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                rating?.let { score = Score.from10(it) }
                addActors(actors)
            }
        }
        return neonEnrichResponse(response, document, normalizedUrl)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val normalized = normalize(data)
        if (normalized.isBlank()) return false
        val pagePath = runCatching { URI(normalized).path.orEmpty() }.getOrDefault("/")
        Log.d(name.uppercase().take(23), "loadLinks start path=$pagePath")

        // The site often adds player URLs lazily or stores them in player scripts.
        // Probe the episode page first, then resolve its iframe/media candidates.
        val candidates = LinkedHashSet<String>()
        candidates.add(normalized)

        val document = neonCachedDocument(normalized, normalized)
            ?: runCatching {
                app.get(normalized, headers = headers(normalized), timeout = 15_000L).document
            }.getOrNull()

        if (document != null) {
            candidates.addAll(neonExtractPlayerCandidates(document, normalized))
            val playerAttributes = listOf(
                "src", "data-src", "data-lazy-src", "data-url", "data-vsrc",
                "data-video-src", "data-player", "data-embed", "data-embed-url",
                "data-iframe", "data-file", "data-source", "data-hls",
                "data-stream", "data-video", "data-play"
            )
            document.select(
                "iframe, video, source, [data-src], [data-lazy-src], [data-url], " +
                    "[data-vsrc], [data-video-src], [data-player], [data-embed], " +
                    "[data-embed-url], [data-iframe], [data-file], [data-source], " +
                    "[data-hls], [data-stream], [data-video], [data-play]"
            ).forEach { node ->
                playerAttributes.forEach { attribute ->
                    val raw = node.attr(attribute).trim()
                    if (raw.isBlank() || raw.startsWith("javascript:", true) ||
                        raw.startsWith("data:", true) || raw == "#"
                    ) return@forEach
                    val candidate = normalize(raw, normalized)
                    if (candidate.startsWith("http", true)) candidates.add(candidate)
                }
            }

            val inlineScriptText = document.select("script:not([src])").joinToString("\n") { script ->
                script.data().ifBlank { script.html() }
            }
            playerUrlsFromText(inlineScriptText, normalized).forEach(candidates::add)
            neonExtractMediaUrls(inlineScriptText).forEach(candidates::add)

            // Some newer pages keep the actual player configuration in an external JS bundle.
            // Probe only a few relevant scripts so the link resolver remains bounded.
            val scriptUrls = document.select("script[src]")
                .map { normalize(it.attr("src"), normalized) }
                .filter { it.startsWith("http", true) }
                .filter { it.substringBefore('?').substringBefore('#').endsWith(".js", true) }
                .filter {
                    isSiteUrl(it) ||
                        Regex("""(?i)(player|video|stream|embed|media|watch)""").containsMatchIn(it)
                }
                .distinct()
                .sortedBy {
                    when {
                        Regex("""(?i)(player|video|stream|embed|media|watch|episode)""").containsMatchIn(it) -> 0
                        Regex("""(?i)(app|main|bundle|index|chunk)""").containsMatchIn(it) -> 1
                        else -> 2
                    }
                }
                .take(3)

            for (scriptUrl in scriptUrls) {
                val scriptBody = runCatching {
                    app.get(
                        scriptUrl,
                        headers = headers(normalized),
                        timeout = 4_000L,
                        allowRedirects = true,
                    ).takeIf { it.isSuccessful }?.text
                }.getOrNull().orEmpty()
                if (scriptBody.isBlank()) continue
                playerUrlsFromText(scriptBody, scriptUrl).forEach(candidates::add)
                neonExtractMediaUrls(scriptBody).forEach(candidates::add)
            }
        }

        // Remove the known dead endpoint before any resolver or extractor can try to play it.
        candidates.removeAll { isBrokenSiteManifest(it) }

        val emitted = LinkedHashSet<String>()
        val forwardingCallback: (ExtractorLink) -> Unit = { link ->
            // Do not expose the site's non-existent root manifest as a media link.
            if (!isBrokenSiteManifest(link.url) && emitted.add(link.url)) callback(link)
        }
        neonResolveLinkCandidates(
            candidates = candidates,
            sourceName = name,
            subtitleCallback = subtitleCallback,
            callback = forwardingCallback,
            maxDepth = 4,
        )

        // Some embedded players require the site's regular CloudStream extractor.
        for (candidate in candidates.toList()) {
            if (isSiteUrl(candidate) || neonIsMediaUrl(candidate)) continue
            runCatching {
                loadExtractor(candidate, normalized, subtitleCallback, forwardingCallback)
            }
        }

        // A resolver may report a candidate even if the callback deliberately filtered it.
        Log.d(name.uppercase().take(23), "loadLinks finished candidates=${candidates.size}; emitted=${emitted.size}")
        return emitted.isNotEmpty()
    }
}
'''
    replacements = {
        "__CLASS__": config["class_name"],
        "__ORIGIN__": kotlin_string(config["origin"]),
        "__SITE_NAME__": kotlin_string(config["site_name"]),
        "__SEARCH_ACTION__": kotlin_string(action),
        "__SEARCH_FIELD__": kotlin_string(field),
        "__SMOKE_QUERIES__": ", ".join(kotlin_string(item) for item in config.get("sample_titles", [])),
        "__MAIN_PAGES__": page_entries,
    }
    for placeholder, value in replacements.items():
        template = template.replace(placeholder, value)
    return template


def render_plugin(config: dict) -> str:
    return f'''package com.neoncs3

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

// AUTO-GENERATED by tools/auto_plugin_generator.py.
@CloudstreamPlugin
class {config["class_name"]}Plugin : Plugin() {{
    override fun load(context: Context) {{
        super.load(context)
        registerMainAPI({config["class_name"]}())
    }}
}}
'''


def render_gradle(config: dict) -> str:
    description = kotlin_string("URL analizinden otomatik oluşturuldu: " + config["host"])
    icon = kotlin_string(config["origin"].rstrip("/") + "/favicon.ico")
    return (
        "version = 1\n\n"
        "cloudstream {\n"
        f"    description = {description}\n"
        '    authors = listOf("neoncs3")\n'
        "    status = 1\n"
        '    tvTypes = listOf("Movie", "TvSeries")\n'
        '    language = "tr"\n'
        f"    iconUrl = {icon}\n"
        "}\n\n"
        "android {\n"
        "    buildFeatures {\n"
        "        buildConfig = true\n"
        "    }\n"
        "}\n"
    )


def status(value: bool) -> str:
    return "Gözlendi" if value else "Sayfada tespit edilemedi"


QUALITY_WEIGHTS = {
    "poster": 15,
    "listing_cards": 20,
    "series_or_movie_schema": 10,
    "search_form": 10,
    "genres": 8,
    "imdb_rating": 6,
    "actors": 6,
    "seasons": 5,
    "episodes": 5,
    "subtitles": 5,
    "player_embed_or_media": 5,
    "media_endpoint_reachable": 5,
}


def quality_assessment(config: dict) -> dict:
    signals = config["signals"]
    score = sum(weight for key, weight in QUALITY_WEIGHTS.items() if signals.get(key))
    issues: list[str] = []
    code = int(config.get("http_status") or 0)

    if config.get("blocked"):
        issues.append("Bot koruması veya erişim engeli sinyali var.")
    if code < 200 or code >= 400:
        issues.append(f"Ana sayfa HTTP {code}; normal bir katalog yanıtı doğrulanamadı.")
    if not signals.get("listing_cards"):
        issues.append("Kart/katalog bağlantısı gözlenmedi.")
    if not signals.get("poster"):
        issues.append("Afiş görüntüsü sinyali gözlenmedi.")
    if not (signals.get("search_form") or config.get("categories")):
        issues.append("Ne arama formu ne de güvenilir kategori bağlantısı bulundu.")
    if not signals.get("player_embed_or_media"):
        issues.append("Oynatıcı veya doğrudan medya sinyali bulunamadı.")

    if config.get("blocked") or code < 200 or code >= 400:
        readiness = "BLOCKED_OR_UNREACHABLE"
    elif (
        signals.get("listing_cards")
        and signals.get("poster")
        and (signals.get("search_form") or config.get("categories"))
    ):
        readiness = "BUILD_CANDIDATE"
    else:
        readiness = "NEEDS_REVIEW"

    return {"score": score, "status": readiness, "issues": issues}


def build_site_snapshot(config: dict, previous: dict | None = None) -> dict:
    """Persist only stable, redacted signals; never store source HTML or signed query strings."""
    stable = {
        "host": config["host"],
        "origin": safe_report_url(config["origin"]),
        "site_name": config["site_name"],
        "http_status": config["http_status"],
        "blocked": bool(config.get("blocked")),
        "signals": config["signals"],
        "counts": config["counts"],
        "categories": [
            {"label": label, "url": safe_report_url(url)}
            for url, label in config["categories"]
        ],
        "sample_titles": config.get("sample_titles", []),
        "quality": quality_assessment(config),
    }
    canonical = json.dumps(stable, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    fingerprint = __import__("hashlib").sha256(canonical.encode("utf-8")).hexdigest()[:16]
    changes: list[str] = []

    previous_fingerprint = previous.get("fingerprint") if isinstance(previous, dict) else None
    if not previous_fingerprint:
        changes.append("İlk tarama: sonraki çalıştırmalar için başlangıç profili kaydedildi.")
    elif previous_fingerprint == fingerprint:
        changes.append("Önceki taramaya göre gözlenen yapı değişmedi.")
    else:
        old_signals = previous.get("signals", {}) if isinstance(previous.get("signals"), dict) else {}
        for key, value in stable["signals"].items():
            if key in old_signals and old_signals[key] != value:
                changes.append(f"Sinyal değişti: {key} ({old_signals[key]} → {value}).")

        old_counts = previous.get("counts", {}) if isinstance(previous.get("counts"), dict) else {}
        for key in ("card_like_links", "media_urls_reachable", "episodes_detected", "season_links", "subtitle_hints"):
            if key in old_counts and old_counts[key] != stable["counts"].get(key):
                changes.append(
                    f"Sayaç değişti: {key} ({old_counts[key]} → {stable['counts'].get(key)})."
                )

        old_categories = {
            item.get("url"): item.get("label", "")
            for item in previous.get("categories", [])
            if isinstance(item, dict) and item.get("url")
        }
        new_categories = {item["url"]: item["label"] for item in stable["categories"]}
        for url in sorted(set(new_categories) - set(old_categories)):
            changes.append(f"Yeni kategori bağlantısı: {new_categories[url]} ({url}).")
        for url in sorted(set(old_categories) - set(new_categories)):
            changes.append(f"Kaldırılan kategori bağlantısı: {old_categories[url]} ({url}).")

        old_titles = set(previous.get("sample_titles", []))
        new_titles = set(stable["sample_titles"])
        added_titles = sorted(new_titles - old_titles)
        removed_titles = sorted(old_titles - new_titles)
        if added_titles:
            changes.append("Yeni örnek içerikler: " + ", ".join(added_titles[:6]) + ".")
        if removed_titles:
            changes.append("Artık görülmeyen örnek içerikler: " + ", ".join(removed_titles[:6]) + ".")
        if not changes:
            changes.append("Özet parmak izi değişti; alanların ayrıntılı farkı belirlenemedi.")

    return {
        **stable,
        "schema_version": 1,
        "observed_at": datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC"),
        "fingerprint": fingerprint,
        "previous_fingerprint": previous_fingerprint,
        "changes": changes,
    }


def render_report(config: dict, snapshot: dict | None = None) -> str:
    sig = config["signals"]
    counts = config["counts"]
    assessment = quality_assessment(config)
    quality_score = assessment["score"]
    missing = [
        label for key, label in (
            ("poster", "Afiş seçicisi siteye özel ayar isteyebilir"),
            ("listing_cards", "İçerik kartları HTML'de tespit edilemedi"),
            ("search_form", "Arama formu otomatik bulunamadı"),
            ("player_embed_or_media", "Oynatıcı işareti bulunamadı"),
            ("seasons", "Sezon bağlantıları görülmedi"),
            ("episodes", "Bölüm bağlantıları görülmedi"),
        ) if not sig.get(key)
    ]
    checked = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    rows = [
        ("Site erişimi", f"HTTP {config['http_status']}"),
        ("Afiş görselleri", status(sig["poster"])),
        ("IMDb / puan sinyali", status(sig["imdb_rating"])),
        ("Oyuncu / cast sinyali", status(sig["actors"])),
        ("Tür / kategori sinyali", status(sig["genres"])),
        ("Sezon bağlantıları", status(sig["seasons"])),
        ("Bölüm bağlantıları", status(sig["episodes"])),
        ("Taranan bağlantılarda bölüm numarası", str(counts["episodes_detected"])),
        ("Altyazı bağlantısı veya track", status(sig["subtitles"])),
        ("Video / iframe oynatıcı sinyali", status(sig["player_embed_or_media"])),
        ("Doğrudan medya URL'si HTTP kontrolü", status(sig["media_endpoint_reachable"])),
        ("JSON-LD yapılandırılmış veri", status(sig["schema_metadata"])),
        ("Arama formu", status(sig["search_form"])),
        ("Kart / liste bağlantısı", status(sig["listing_cards"])),
        ("Gerçek içerik örneği", ", ".join(config.get("sample_titles", [])) or "Tespit edilemedi"),
    ]
    table = "\n".join(f"| {label} | {value} |" for label, value in rows)
    categories = "\n".join(
        f"- {label}: {safe_report_url(url)}" for url, label in config["categories"]
    ) or "- Ana sayfada güvenilir kategori bağlantısı bulunamadı."
    note = (
        "UYARI: Site bot koruması, CAPTCHA veya erişim engeli göstermiş olabilir. "
        "Eklenti iskeleti oluşturuldu; sayfada görünmeyen alanlar çalışma anında da gelmeyebilir."
        if config["blocked"] else
        "Not: Bu tarama sayfa HTML'sinde görülebilen işaretleri doğrular. Başarılı HTTP yanıtı veya iframe bulunması, videonun CloudStream içinde kesin oynayacağını kanıtlamaz."
    )
    return f"""# Otomatik eklenti analiz raporu

- Kontrol: {checked}
- Girilen URL: {safe_report_url(config['input_url'])}
- Ana adres: {safe_report_url(config['origin'])}
- Site adı: {config['site_name']}
- Oluşturulan modül: {config['module_name']}
- İncelenen sayfa sayısı: {counts['pages']}
- HTTP sonucu: {config['http_status']}
- **HTML analiz kalite puanı: {quality_score}/100** (yalnızca taramada gözlenen işaretler; oynatma garantisi değildir)
- **Derleme öncesi kalite kapısı: {assessment["status"]}**
- Kalite uyarıları: {("; ".join(assessment["issues"]) if assessment["issues"] else "Temel katalog koşulları gözlendi.")}
- Site parmak izi: {(snapshot or {}).get("fingerprint", "—")}

## Site yapısı değişiklik takibi

{chr(10).join("- " + item for item in (snapshot or {}).get("changes", ["Değişiklik kaydı bulunamadı."]))}

> Bu karşılaştırma yalnızca gözlenebilir HTML işaretlerini, sayaçları, örnek başlıkları ve kategori bağlantılarını izler. Site değişimini fark edebilir; değişikliğin nedenini veya oynatmayı tek başına kanıtlamaz.

## Otomatik düzeltme önerileri

{chr(10).join("- " + item for item in missing) if missing else "- Tarama sırasında belirgin eksik özellik işareti bulunmadı."}

## Bulunan özellikler

| Özellik | Sonuç |
|---|---|
{table}

## Tarama sayaçları

- Görsel etiketi: {counts['images']}
- Kart benzeri bağlantı: {counts['card_like_links']}
- Sezon ipucu: {counts['season_links']}
- Bölüm ipucu: {counts['episode_links']}
- Numara olarak eşleşen bölüm: {counts['episodes_detected']}
- Sezon eşleşmesi: {counts['episode_seasons_detected']}
- Olası bölüm boşluğu bulunan sezon: {counts['episode_gaps']}
- Altyazı ipucu: {counts['subtitle_hints']}
- JSON-LD nesnesi: {counts['schema_objects']}
- Kontrol edilen doğrudan medya URL'si: {counts['media_urls_probed']}
- HTTP yanıtı alınan medya URL'si: {counts['media_urls_reachable']}

## Taranan sezon ve bölüm numaraları

{chr(10).join(
    f"- Sezon {item['season']}: bulunan bölüm numaraları " + ", ".join(str(n) for n in item["episodes_found"])
    for item in config["episode_inventory"]["inventory"]
) if config["episode_inventory"]["inventory"] else "- Taranan bağlantılarda açık bir bölüm numarası eşleşmesi bulunamadı."}

{chr(10).join(
    f"- **Sezon {item['season']}**: taranan bağlantılarda {item['observed_min']}–{item['observed_max']} aralığında olası eksikler: " + ", ".join(str(n) for n in item["missing"])
    for item in config["episode_inventory"]["potential_gaps"]
) if config["episode_inventory"]["potential_gaps"] else "- Tespit edilebilir aralıklarda olası bölüm boşluğu bulunmadı."}

> Bu yalnızca taranan HTML bağlantılarını özetler; site sayfalama yapıyorsa veya bağlantıları JavaScript ile yüklüyorsa “eksik” numaralar gerçekte mevcut olabilir.

## Doğrudan medya URL kontrolü

| URL (imza/sorgu bilgisi gizlenmiştir) | HTTP | İçerik türü | Sonuç |
|---|---:|---|---|
{chr(10).join(f"| {item['url']} | {item['status'] if item['status'] is not None else '—'} | {item['content_type']} | {item['note']} |" for item in config['media_probes']) if config['media_probes'] else "| Doğrudan .m3u8/.mpd/.mp4 adresi bulunamadı | — | — | Oynatıcı/iframe ayrıca olabilir; test edilemedi |"}

## Keşfedilen ana sayfa/kategori bağlantıları

{categories}

## Doğruluk notu

{note}

Araç; afiş, puan, tür, oyuncu ve JSON-LD bilgilerini sayfa üzerinde bulduğunda kullanır. İçerik listelerinde geçerli afişi olmayan sonuçlar ve yorumlar/keşfet/takvim/tartışmalar/başlıklar gibi gezinme bağlantıları filtrelenir. CloudStream tarafında ayrıca genel HTML/iframe/HLS ve altyazı çözümlemesi uygulanır. DRM, giriş duvarı, CAPTCHA veya site koruması aşılmaz. Eksik alanlar raporda açıkça belirtilir.
"""


def run_self_test() -> None:
    sample = BeautifulSoup(
        '<html><head><meta property="og:site_name" content="Test Sinema">'
        '<script type="application/ld+json">{"@type":"Movie","name":"Örnek","aggregateRating":{"ratingValue":"7.7"}}</script>'
        '</head><body><article><a href="/film/ornek"><img data-src="/poster.jpg" alt="Örnek Film"></a></article>'
        '<a href="/sezon-1">1. Sezon</a><a href="/bolum-3">3. Bölüm</a>'
        '<video><track src="/altyazi.vtt"></video></body></html>',
        "html.parser",
    )
    objects = structured_objects(sample)
    assert page_title(sample, objects) == "Test Sinema"
    assert any(obj.get("@type") == "Movie" for obj in objects)
    assert sample.select_one("img[data-src]") is not None
    assert sample.select_one("track[src]") is not None
    assert is_category_link("Diziler", "https://example.org/diziler")
    assert is_utility_link("Yorumlar", "https://example.org/yorumlar")
    assert is_utility_link("Dizi Takvimi", "https://example.org/dizi-takvimi")
    assert is_utility_link("Keşfet", "https://example.org/kesfet")
    assert is_utility_link("Tartışmalar", "https://example.org/tartismalar")
    assert is_utility_link("Başlıklar", "https://example.org/basliklar")
    assert is_utility_link("bir kullanici", "https://example.org/profile/hkncan")
    assert is_utility_link("hkncan", "https://example.org/users/hkncan")
    assert is_utility_link("Konu", "https://example.org/threads/dizi-onerileri")
    assert is_utility_link("uye", "https://example.org/@hkncan")
    profile_anchor = BeautifulSoup(
        '<a class="profile-link" href="/hkncan"><img src="/images/avatar-hkncan.webp"></a>',
        "html.parser",
    ).select_one("a")
    assert profile_anchor is not None
    assert is_non_catalog_anchor(profile_anchor, "https://example.org/hkncan", "hkncan")
    assert not is_utility_link("Wednesday", "https://example.org/wednesday")
    assert is_flag_or_icon_asset("https://example.org/assets/flags/tr.svg")
    assert is_flag_or_icon_asset("flag-icon flag-icon-tr")
    assert is_flag_or_icon_asset("https://example.org/icons/country-flag-us.png")
    assert not is_flag_or_icon_asset("https://example.org/posters/flag-day-poster.webp")
    flag_anchor = BeautifulSoup(
        '<a href="/en"><img class="flag-icon flag-icon-us" '
        'src="/assets/flags/us.svg" alt="English"></a>',
        "html.parser",
    ).select_one("a")
    assert flag_anchor is not None
    assert is_non_catalog_anchor(flag_anchor, "https://example.org/en", "English")
    catalogue_fixture = [{
        "url": "https://example.org/",
        "html": '<article><a href="/film/ornek-film"><img src="/posters/ornek-film.webp" alt="Örnek Film"></a></article>'
                '<article><a href="/en"><img class="flag-icon flag-icon-us" src="/assets/flags/us.svg" alt="English"></a></article>',
    }]
    assert extract_catalogue_samples(catalogue_fixture, "https://example.org", []) == ["Örnek Film"]
    media_sample = extract_media_candidates([{
        "url": "https://example.org/watch/",
        "html": '<video src="/stream/master.m3u8?token=hidden"></video>'
                '<a href="https://cdn.example.org/movie.mp4?sig=secret">Video</a>',
    }])
    assert media_sample
    assert all(urlsplit(item).path.lower().endswith((".m3u8", ".mpd", ".mp4", ".m4v")) for item in media_sample)
    assert safe_report_url("https://cdn.example.org/master.m3u8?token=secret") == "https://cdn.example.org/master.m3u8"
    episodes_fixture = extract_episode_inventory([{
        "url": "https://example.org/series/",
        "html": '<a href="/s01e01">S01E01</a>'
                '<a href="/s01e02">S01E02</a>'
                '<a href="/s01e05">S01E05</a>',
    }])
    assert episodes_fixture["episodes_detected"] == 3
    assert episodes_fixture["potential_gaps"][0]["missing"] == [3, 4]
    assert normalize_input("https://example.org/") == "https://example.org/"
    demo = {
        "origin": "https://example.org",
        "site_name": "Test Sinema",
        "class_name": "AutoExampleOrg",
        "host": "example.org",
        "sample_titles": ["Flag Day", "Örnek Film"],
        "categories": [("https://example.org/diziler", "Diziler")],
        "search": {"action": "https://example.org/", "field": "s", "method": "get"},
    }
    generated = render_kotlin(demo)
    generated_plugin = render_plugin(demo)
    assert "class AutoExampleOrg : NeonMainAPI()" in generated
    assert "@CloudstreamPlugin" in generated_plugin
    assert "class AutoExampleOrgPlugin : Plugin()" in generated_plugin
    assert "registerMainAPI(AutoExampleOrg())" in generated_plugin
    assert "override suspend fun load(url: String)" in generated
    assert "override suspend fun search(query: String)" in generated
    assert "override suspend fun loadLinks(" in generated
    assert "neonResolveLinkCandidates(" in generated
    assert "neonExtractPlayerCandidates(document, normalized)" in generated
    assert "loadExtractor(candidate, normalized, subtitleCallback, forwardingCallback)" in generated
    assert "private fun matchesQuery(result: SearchResponse, query: String): Boolean" in generated
    assert "Never leak those unrelated home-page items" in generated
    assert "posterFor(anchor, baseUrl) ?: return null" in generated
    assert "isNavigationItem(title, href)" in generated
    assert "private fun isNavigationChrome(anchor: Element): Boolean" in generated
    assert "private fun isNavigationItem(title: String, url: String): Boolean" in generated
    assert "navigationOnlyPaths" in generated
    assert "isValidPosterUrl(poster)" in generated
    assert "private fun isFlagOrIconAsset(value: String): Boolean" in generated
    assert "private val neonSmokeTestQueries = listOf(\"Flag Day\", \"Örnek Film\")" in generated
    assert "if (isFlagOrIconAsset(" in generated
    assert "isNavigationChrome(it)" in generated
    assert "profile photo" in generated
    assert "forum    private fun" not in generated
    assert "discussion    private fun" not in generated
    assert "__CLASS__" not in generated and "__ORIGIN__" not in generated

    report_fixture = {
        "input_url": "https://example.org/",
        "origin": "https://example.org",
        "site_name": "Test Sinema",
        "module_name": "AutoExampleOrg",
        "host": "example.org",
        "http_status": 200,
        "signals": {
            "poster": True, "listing_cards": True, "series_or_movie_schema": True,
            "search_form": True, "genres": False, "imdb_rating": False, "actors": False,
            "seasons": True, "episodes": True, "subtitles": False,
            "player_embed_or_media": True, "media_endpoint_reachable": True,
            "schema_metadata": True,
        },
        "counts": {
            "pages": 1, "images": 3, "card_like_links": 2, "season_links": 1,
            "episode_links": 3, "subtitle_hints": 0, "schema_objects": 1,
            "episodes_detected": 3, "episode_seasons_detected": 1, "episode_gaps": 1,
            "media_urls_probed": 1, "media_urls_reachable": 1,
        },
        "episode_inventory": {
            "seasons_detected": 1, "episodes_detected": 3,
            "inventory": [{"season": 1, "episodes_found": [1, 2, 5], "episode_count": 3}],
            "potential_gaps": [{"season": 1, "missing": [3, 4], "observed_min": 1, "observed_max": 5}],
        },
        "categories": [("https://example.org/diziler", "Diziler")],
        "media_probes": [{
            "url": "https://cdn.example.org/master.m3u8",
            "status": 206,
            "content_type": "application/vnd.apple.mpegurl",
            "reachable": True,
            "note": "HTTP erişilebilir (oynatma doğrulaması değildir)",
        }],
        "blocked": False,
    }
    report_fixture["sample_titles"] = ["Örnek Film", "İkinci Film"]
    snapshot_first = build_site_snapshot(report_fixture)
    snapshot_same = build_site_snapshot(report_fixture, snapshot_first)
    changed_fixture = dict(report_fixture)
    changed_fixture["signals"] = dict(report_fixture["signals"])
    changed_fixture["signals"]["player_embed_or_media"] = False
    snapshot_changed = build_site_snapshot(changed_fixture, snapshot_first)
    assert snapshot_first["fingerprint"] == snapshot_same["fingerprint"]
    assert any("Sinyal değişti: player_embed_or_media" in item for item in snapshot_changed["changes"])
    assert snapshot_first["sample_titles"] == ["Örnek Film", "İkinci Film"]
    report = render_report(report_fixture, snapshot_changed)
    assert "HTML analiz kalite puanı" in report
    assert "Derleme öncesi kalite kapısı" in report
    assert "Site yapısı değişiklik takibi" in report
    assert "Sinyal değişti: player_embed_or_media" in report
    assert "token=secret" not in report
    assert "master.m3u8" in report and "206" in report
    assert "Sezon 1" in report and "3, 4" in report
    assert "token=secret" not in report
    print("AUTO PLUGIN GENERATOR SELF-TEST: PASSED")


def main() -> int:
    parser = argparse.ArgumentParser(description="URL'den CloudStream eklentisi oluştur")
    parser.add_argument("url", nargs="?", help="Eklenti üretilecek sitenin URL'si")
    parser.add_argument("--self-test", action="store_true", help="Ağ erişimi olmadan yerel test")
    args = parser.parse_args()

    if args.self_test:
        run_self_test()
        return 0
    if not args.url:
        parser.error("Bir site URL'si girin.")
    try:
        config = analyse_site(args.url)
        module_dir = ROOT / config["module_name"]
        source_dir = module_dir / "src" / "main" / "kotlin" / "com" / "neoncs3"
        source_path = source_dir / (config["class_name"] + ".kt")
        plugin_path = source_dir / (config["class_name"] + "Plugin.kt")
        gradle_path = module_dir / "build.gradle.kts"
        report_path = REPORT_DIR / (config["module_name"] + ".md")
        snapshot_path = REPORT_DIR / (config["module_name"] + ".json")

        if module_dir.exists() and not (source_path.exists() and "AUTO-GENERATED by tools/auto_plugin_generator.py" in source_path.read_text(encoding="utf-8", errors="replace")):
            raise RuntimeError(
                f"{module_dir.name} klasörü zaten var ve otomatik oluşturulmuş modül değil; üzerine yazılmadı."
            )

        if plugin_path.exists() and "AUTO-GENERATED by tools/auto_plugin_generator.py" not in plugin_path.read_text(encoding="utf-8", errors="replace"):
            raise RuntimeError(
                f"{plugin_path.relative_to(ROOT)} elle yazılmış veya başka kaynaktan geliyor; üzerine yazılmadı."
            )

        source_dir.mkdir(parents=True, exist_ok=True)
        REPORT_DIR.mkdir(parents=True, exist_ok=True)
        source_path.write_text(render_kotlin(config), encoding="utf-8")
        plugin_path.write_text(render_plugin(config), encoding="utf-8")
        gradle_path.write_text(render_gradle(config), encoding="utf-8")
        previous_snapshot = None
        if snapshot_path.exists():
            try:
                previous_value = json.loads(snapshot_path.read_text(encoding="utf-8"))
                previous_snapshot = previous_value if isinstance(previous_value, dict) else None
            except (OSError, json.JSONDecodeError):
                previous_snapshot = None
        snapshot = build_site_snapshot(config, previous_snapshot)
        report_path.write_text(render_report(config, snapshot), encoding="utf-8")
        snapshot_path.write_text(
            json.dumps(snapshot, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )

        print("AUTO PLUGIN GENERATION: PASSED")
        print(f"Site: {config['origin']}")
        print(f"Modül: {config['module_name']}")
        print(f"Kaynak: {source_path.relative_to(ROOT)}")
        print(f"CloudStream kayıt sınıfı: {plugin_path.relative_to(ROOT)}")
        print(f"Manifest: {gradle_path.relative_to(ROOT)}")
        print(f"Rapor: {report_path.relative_to(ROOT)}")
        print(f"İncelenen sayfa: {config['counts']['pages']}")
        print(f"Test için bulunan gerçek katalog örneği: {len(config.get('sample_titles', []))}")
        assessment = quality_assessment(config)
        print(f"Kalite kapısı: {assessment['status']} ({assessment['score']}/100)")
        for issue in assessment["issues"]:
            print(f"  WARNING: {issue}")
        print(f"Site fingerprint: {snapshot['fingerprint']}")
        for change in snapshot["changes"]:
            print(f"  SITE CHANGE: {change}")
        print("Özellik sinyalleri:")
        for key, value in config["signals"].items():
            print(f"  {'FOUND' if value else 'NOT OBSERVED'}: {key}")
        if config["blocked"]:
            print("WARNING: Site koruması/HTTP engeli gözlendi; çalışan video bağlantısı garanti edilemez.")
        return 0
    except Exception as exc:
        print(f"AUTO PLUGIN GENERATION: FAILED: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
