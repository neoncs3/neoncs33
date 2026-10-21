#!/usr/bin/env python3
"""Sanitized public-endpoint diagnostics for GölgeTV. Never sends or logs credentials."""
import json
import re
import urllib.error
import urllib.parse
import urllib.request
from html.parser import HTMLParser
from pathlib import Path

UA = ("Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
MAIN = "https://golgetv.com"
CONFIG = "https://imgcom.site/panel/rec/recjson.php"
OUTPUT = Path("health-status/GolgeTV-API.md")
MAX_BYTES = 2_000_000
POSTER_KEYS = {"poster", "image", "imageurl", "image_url", "filmimg", "diziimg",
               "thumbnail", "thumbnailurl", "logo", "icon", "cover", "thumb_square"}
TITLE_KEYS = {"title", "name", "isim", "filmtitle", "dizititle", "filmname", "diziname"}
DETAIL_KEYS = {"detailurl", "detail_url", "url", "link", "href", "permalink",
               "filmurl", "diziurl", "pageurl"}
BLOCKED_WORDS = ("nonce", "verify", "attest", "token", "auth", "login", "license", "ticket")


def safe_url(raw):
    try:
        p = urllib.parse.urlsplit(raw.strip())
        if p.scheme.lower() not in {"http", "https"} or not p.hostname:
            return None
        if p.hostname.lower() in {"localhost", "127.0.0.1", "::1"} or p.hostname.lower().endswith(".local"):
            return None
        if any(w in (p.path + "?" + p.query).lower() for w in BLOCKED_WORDS):
            return None
        # No query, fragment or credentials are emitted into the report.
        return urllib.parse.urlunsplit((p.scheme.lower(), p.netloc, p.path or "/", "", ""))
    except Exception:
        return None


def fetch(url, referer=None):
    req = urllib.request.Request(url, headers={
        "User-Agent": UA,
        "Accept": "application/json,text/html,application/xhtml+xml,*/*;q=0.8",
        "Accept-Language": "tr-TR,tr;q=0.9,en;q=0.8",
        "Origin": MAIN,
        "Referer": referer or MAIN + "/",
        "X-Requested-With": "com.golge.golgetv",
    })
    try:
        with urllib.request.urlopen(req, timeout=12) as response:
            body = response.read(MAX_BYTES + 1).decode("utf-8", "replace")[:MAX_BYTES]
            return {"status": response.status, "content_type": response.headers.get("Content-Type", "").split(";", 1)[0], "text": body, "network_error": ""}
    except urllib.error.HTTPError as e:
        try:
            body = e.read(64_000).decode("utf-8", "replace")
        except Exception:
            body = ""
        return {"status": e.code, "content_type": e.headers.get("Content-Type", "").split(";", 1)[0] if e.headers else "", "text": body, "network_error": ""}
    except Exception as e:
        reason = getattr(e, "reason", None)
        error_name = type(reason).__name__ if reason is not None else type(e).__name__
        # Report exception class only; never include potentially sensitive request details.
        return {"status": 0, "content_type": "", "text": "NETWORK_ERROR:" + error_name, "network_error": error_name}


def dns_query(name, rrtype):
    """Query Google Public DNS over HTTPS; public DNS metadata only, no credentials."""
    query = urllib.parse.urlencode({"name": name, "type": rrtype})
    request = urllib.request.Request(
        "https://dns.google/resolve?" + query,
        headers={"Accept": "application/dns-json", "User-Agent": UA},
    )
    try:
        with urllib.request.urlopen(request, timeout=6) as response:
            payload = json.loads(response.read(128_000).decode("utf-8", "replace"))
        status = payload.get("Status")
        status_names = {0: "NOERROR", 1: "FORMERR", 2: "SERVFAIL", 3: "NXDOMAIN", 4: "NOTIMP", 5: "REFUSED"}
        answers = sorted({
            str(item.get("data", "")).rstrip(".")
            for item in payload.get("Answer", [])
            if item.get("data")
        })
        return {
            "name": name,
            "type": rrtype,
            "status": status_names.get(status, "UNKNOWN"),
            "answer": answers[:8],
            "error": "",
        }
    except Exception as exc:
        reason = getattr(exc, "reason", None)
        error_name = type(reason).__name__ if reason is not None else type(exc).__name__
        return {"name": name, "type": rrtype, "status": "QUERY_ERROR", "answer": [], "error": error_name}


def parse_json(text):
    value = text.strip().lstrip("\ufeff")
    value = re.sub(r"^\)\]\}',?\s*", "", value)
    for _ in range(3):
        try:
            result = json.loads(value)
        except Exception:
            return None
        if isinstance(result, str) and result.strip().startswith(("{", "[")):
            value = result.strip()
            continue
        return result
    return None


def walk_objects(value, depth=0):
    if depth > 12:
        return
    if isinstance(value, dict):
        yield value
        for child in value.values():
            yield from walk_objects(child, depth + 1)
    elif isinstance(value, list):
        for child in value:
            yield from walk_objects(child, depth + 1)
    elif isinstance(value, str) and value.strip().startswith(("{", "[")):
        parsed = parse_json(value)
        if parsed is not None and not isinstance(parsed, str):
            yield from walk_objects(parsed, depth + 1)


def shape(data):
    objects = list(walk_objects(data))
    records = []
    poster_count = 0
    fields = set()
    for obj in objects:
        lower = {str(k).lower(): v for k, v in obj.items()}
        fields.update(set(lower) & (TITLE_KEYS | DETAIL_KEYS | POSTER_KEYS |
                                    {"sources", "type", "category", "dil", "description", "imdb", "year"}))
        if TITLE_KEYS.intersection(lower) and DETAIL_KEYS.intersection(lower):
            records.append(obj)
            if any(str(v).strip() for k, v in lower.items() if k in POSTER_KEYS):
                poster_count += 1
    return {"objects": len(objects), "records": len(records),
            "records_with_poster": poster_count, "fields": sorted(fields)}


class Cards(HTMLParser):
    def __init__(self):
        super().__init__()
        self.anchors = self.images = self.posterish = 0

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag == "a" and a.get("href"):
            self.anchors += 1
        if tag == "img":
            self.images += 1
            src = a.get("data-src") or a.get("data-lazy-src") or a.get("data-original") or a.get("src") or ""
            if src and not any(s in src.lower() for s in ("avatar", "sprite", "placeholder")):
                self.posterish += 1


def describe(name, url, response):
    text = response["text"]
    data = parse_json(text) if text and not text.startswith("NETWORK_ERROR:") else None
    sh = shape(data) if data is not None else {"objects": 0, "records": 0, "records_with_poster": 0, "fields": []}
    cards = Cards()
    if text and "<" in text[:500]:
        try:
            cards.feed(text)
        except Exception:
            pass
    endpoint = safe_url(url) or "(URL hidden)"
    line = "| {} | {} | {} | {} | {} | {} | {} | {}/{}/{} |".format(
        name, endpoint, response["status"] or ("ERR:" + (response.get("network_error") or "unknown")), response["content_type"] or "-",
        sh["records"], sh["records_with_poster"], ", ".join(sh["fields"]) or "-",
        cards.anchors, cards.images, cards.posterish)
    detail = {
        "name": name, "url": endpoint, "status": response["status"],
        "content_type": response["content_type"], "network_error": response.get("network_error", ""),
        "objects": sh["objects"], "records": sh["records"], "records_with_poster": sh["records_with_poster"],
        "fields": sh["fields"], "html_anchors": cards.anchors,
        "html_images": cards.images, "html_posterish": cards.posterish,
    }
    return line, detail


def main():
    probes = [
        # General internet/DNS controls separate runner networking from provider-domain failure.
        ("control_example", "https://example.com/"),
        ("control_github_api", "https://api.github.com/"),
        ("config", CONFIG),
        ("films", MAIN + "/filmapi.php"),
        ("series", MAIN + "/diziapi.php"),
        ("movie_search", MAIN + "/search.php?search=diagnostic"),
        ("series_search", MAIN + "/dizisearch.php?search=diagnostic"),
        ("homepage", MAIN + "/"),
    ]
    records = []
    lines = [
        "# GölgeTV API Tanılama", "",
        "Bu rapor otomatik oluşturulur. Şifre, cookie, bearer token, tam yanıt gövdesi veya medya URL'si içermez.", "",
        "| Test | Uç nokta | HTTP | İçerik türü | Kayıt | Afişli kayıt | Tanınan alanlar | HTML bağlantı/görsel/afiş |",
        "|---|---|---:|---|---:|---:|---|---:|",
    ]
    config_jsonurl = None
    for name, url in probes:
        result = fetch(url, MAIN + "/")
        parsed = parse_json(result["text"]) if result["text"] else None
        line, detail = describe(name, url, result)
        records.append(detail)
        lines.append(line)
        if name == "config" and parsed is not None:
            for obj in walk_objects(parsed):
                for key, value in obj.items():
                    if str(key).lower() in {"jsonurl", "json_url", "channelsurl", "channels_url"} and isinstance(value, str):
                        candidate = safe_url(value)
                        if candidate:
                            config_jsonurl = candidate
                            break
                if config_jsonurl:
                    break

    if config_jsonurl:
        result = fetch(config_jsonurl, CONFIG)
        line, detail = describe("configured_channel_json", config_jsonurl, result)
        records.append(detail)
        lines.append(line)
    else:
        lines.append("| configured_channel_json | - | SKIP | - | 0 | 0 | public jsonurl yok | - |")

    # Independent DNS-over-HTTPS checks distinguish NXDOMAIN and active DNS records
    # from failures in the runner's normal resolver. These checks are anonymous.
    dns_queries = [
        ("golgetv.com", "A"),
        ("golgetv.com", "NS"),
        ("www.golgetv.com", "A"),
        ("imgcom.site", "A"),
        ("imgcom.site", "NS"),
        ("www.imgcom.site", "A"),
    ]
    from concurrent.futures import ThreadPoolExecutor
    with ThreadPoolExecutor(max_workers=6) as pool:
        dns_results = list(pool.map(lambda pair: dns_query(pair[0], pair[1]), dns_queries))

    lines += [
        "",
        "## Bağımsız DNS-over-HTTPS kontrolü",
        "",
        "| Ad | Kayıt türü | DNS sonucu | Yanıt |",
        "|---|---|---|---|",
    ]
    for item in dns_results:
        answer = ", ".join(item["answer"]) if item["answer"] else "-"
        if item["error"]:
            answer = "sorgu hatası: " + item["error"]
        lines.append("| {} | {} | {} | {} |".format(
            item["name"], item["type"], item["status"], answer
        ))
    records.append({"name": "dns_over_https", "results": dns_results})

    lines += [
        "", "## Yorumlama", "",
        "- DNS NXDOMAIN: sorgulanan ad için DNS yanıtı bulunamadı; bu tek başına alan adının kayıt/ownership durumunu kanıtlamaz. NOERROR ve yanıt adresi ise DNS kaydının bulunduğunu gösterir, web servisi ayrıca kontrol edilmelidir.",
        "- HTTP 401/403 veya ağ hatası: normal katalog isteği erişilemiyor; bu test giriş yapmayı veya korumalı nonce/doğrulama uç noktalarını denemez.",
        "- HTTP 200 ve 0 kayıt: yanıt gövdesi beklenen katalog şemasında değil veya katalog içermiyor.",
        "- Rapor alan adlarını ve sayılarını gösterir; içerik başlıkları, afiş URL'leri, stream URL'leri veya kişisel bilgiler yazılmaz.", ""
    ]
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text("\n".join(lines), encoding="utf-8")
    Path("golgetv-api-diagnostic.json").write_text(json.dumps(records, ensure_ascii=False, indent=2), encoding="utf-8")
    print("\n".join(lines))


if __name__ == "__main__":
    main()
