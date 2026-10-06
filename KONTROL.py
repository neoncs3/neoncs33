# NeonCS otomatik domain kontrol ve NeonCore runtime manifesti

from Kekik.cli import konsol
from cloudscraper import CloudScraper
from Crypto.Cipher import AES
from Crypto.Util.Padding import unpad
import os
import re
import base64
import json
from datetime import datetime, timezone
from urllib.parse import quote, urlparse


class MainUrlUpdater:
    EXCLUDED = {
        "gradle",
        "CanliTV",
        "OxAx",
        "__Temel",
        "SineWix",
        "YouTube",
        "NetflixMirror",
        "HQPorner",
    }

    def __init__(self, base_dir="."):
        self.base_dir = base_dir
        self.oturum = CloudScraper()
        self.domain_manifest_path = os.path.join(
            self.base_dir, "NeonCore", "domains.json"
        )
        self.domain_status_dir = os.path.join(
            self.base_dir, "domain-status"
        )
        self.domain_candidates_path = os.path.join(
            self.base_dir, "NeonCore", "domain-candidates.json"
        )
        self.domain_status_path = os.path.join(
            self.domain_status_dir, "status.json"
        )
        self.domain_status_index_path = os.path.join(
            self.domain_status_dir, "index.md"
        )

    @property
    def eklentiler(self):
        return sorted(
            dosya for dosya in os.listdir(self.base_dir)
            if os.path.isdir(os.path.join(self.base_dir, dosya))
            and not dosya.startswith(".")
            and dosya not in self.EXCLUDED
        )

    def _kt_dosyasini_bul(self, dizin, dosya_adi):
        for kok, _alt_dizinler, dosyalar in os.walk(dizin):
            if dosya_adi in dosyalar:
                return os.path.join(kok, dosya_adi)
        return None

    def _eklenti_adi_bul(self, kt_dosya_yolu):
        rel = os.path.relpath(kt_dosya_yolu, self.base_dir)
        return rel.split(os.sep, 1)[0]

    @property
    def kt_dosyalari(self):
        return [
            yol for eklenti in self.eklentiler
            if (yol := self._kt_dosyasini_bul(eklenti, f"{eklenti}.kt"))
        ]

    def _mainurl_bul(self, kt_dosya_yolu):
        with open(kt_dosya_yolu, "r", encoding="utf-8") as file:
            icerik = file.read()

        match = re.search(
            r'override\s+var\s+mainUrl\s*=\s*"([^"]+)"',
            icerik,
        )
        return match.group(1).strip().rstrip("/") if match else None

    def _mainurl_guncelle(self, kt_dosya_yolu, eski_url, yeni_url):
        with open(kt_dosya_yolu, "r", encoding="utf-8") as file:
            icerik = file.read()

        yeni_icerik = icerik.replace(eski_url, yeni_url)
        if yeni_icerik == icerik:
            return False

        with open(kt_dosya_yolu, "w", encoding="utf-8") as file:
            file.write(yeni_icerik)

        return True

    def _versiyonu_artir(self, build_gradle_yolu):
        if not os.path.isfile(build_gradle_yolu):
            return None

        with open(build_gradle_yolu, "r", encoding="utf-8") as file:
            icerik = file.read()

        match = re.search(
            r"""(version\s*=\s*)(["']?)(\d+)\2""",
            icerik,
        )
        if not match:
            return None

        eski_versiyon = int(match.group(3))
        yeni_versiyon = eski_versiyon + 1

        yeni_icerik = (
            icerik[:match.start(3)]
            + str(yeni_versiyon)
            + icerik[match.end(3):]
        )

        with open(build_gradle_yolu, "w", encoding="utf-8") as file:
            file.write(yeni_icerik)

        return yeni_versiyon

    def _rectv_ver(self):
        istek = self.oturum.post(
            url="https://firebaseremoteconfig.googleapis.com/v1/projects/791583031279/namespaces/firebase:fetch",
            headers={
                "X-Goog-Api-Key": "AIzaSyBbhpzG8Ecohu9yArfCO5tF13BQLhjLahc",
                "X-Android-Package": "com.rectv.shot",
                "User-Agent": "Dalvik/2.1.0 (Linux; U; Android 12)",
            },
            json={
                "appBuild": "81",
                "appInstanceId": "evON8ZdeSr-0wUYxf0qs68",
                "appId": "1:791583031279:android:1",
            },
        )
        return (
            istek.json()
            .get("entries", {})
            .get("api_url", "")
            .replace("/api/", "")
            .rstrip("/")
        )

    def _golgetv_ver(self):
        istek = self.oturum.get(
            "https://raw.githubusercontent.com/sevdaliyim/sevdaliyim/refs/heads/main/ssl2.key"
        ).text
        cipher = AES.new(
            b"trskmrskslmzbzcnfstkcshpfstkcshp",
            AES.MODE_CBC,
            b"trskmrskslmzbzcn",
        )
        encrypted_data = base64.b64decode(istek)
        decrypted_data = unpad(
            cipher.decrypt(encrypted_data),
            AES.block_size,
        ).decode("utf-8")
        return json.loads(decrypted_data, strict=False)["apiUrl"].rstrip("/")

    def _guvenli_domain(self, url):
        if not url:
            return None
        match = re.match(r"^https?://[^/]+", url.strip())
        return match.group(0).rstrip("/") if match else None

    def _domain_adaylarini_oku(self):
        if not os.path.isfile(self.domain_candidates_path):
            return {}
        try:
            with open(self.domain_candidates_path, "r", encoding="utf-8") as file:
                data = json.load(file)
            return data if isinstance(data, dict) else {}
        except Exception:
            return {}

    def _domain_adaylari(self, eklenti_adi, mainurl):
        data = self._domain_adaylarini_oku().get(eklenti_adi, {})
        candidates = data.get("candidates", []) if isinstance(data, dict) else []
        values = [mainurl] + [value for value in candidates if isinstance(value, str)]
        try:
            parsed = urlparse(mainurl)
            host = parsed.hostname or ""
            if host.startswith("www."):
                values.append(mainurl.replace("://www.", "://", 1))
            elif host:
                values.append(mainurl.replace("://", "://www.", 1))
        except Exception:
            pass
        return list(dict.fromkeys(value.rstrip("/") for value in values if value))

    def _domain_belirtecleri(self, eklenti_adi):
        data = self._domain_adaylarini_oku().get(eklenti_adi, {})
        markers = data.get("markers", []) if isinstance(data, dict) else []
        return [m.lower().strip() for m in markers if isinstance(m, str) and m.strip()]

    def _anti_bot_korumasini_tespit_et(self, response):
        status = getattr(response, "status_code", None)
        if status not in {403, 429, 503}:
            return False
        text = getattr(response, "text", "")[:300000].lower()
        markers = (
            "cloudflare",
            "just a moment",
            "attention required",
            "checking your browser",
            "verify you are human",
            "cf-chl-",
        )
        return any(marker in text for marker in markers)

    def _domain_icerigi_uygun(self, eklenti_adi, response):
        markers = self._domain_belirtecleri(eklenti_adi)
        if not markers:
            return True
        return any(marker in getattr(response, "text", "")[:1500000].lower() for marker in markers)

    def _arama_ile_domain_ara(self, eklenti_adi, mainurl):
        query = quote(f'"{eklenti_adi}" "{mainurl}"')
        try:
            response = self.oturum.get(
                "https://html.duckduckgo.com/html/?q=" + query,
                timeout=15,
            )
            if not response.ok:
                return None
            html = response.text
        except Exception:
            return None

        urls = re.findall(r'href=["\'](https?://[^"\']+)["\']', html, re.I)
        blocked = {
            "duckduckgo.com", "google.com", "bing.com", "youtube.com",
            "facebook.com", "instagram.com", "x.com",
        }
        seen = set()

        for value in urls:
            try:
                parsed = urlparse(value)
            except Exception:
                continue
            host = (parsed.hostname or "").lower()
            if not host or host in seen:
                continue
            if any(host == item or host.endswith("." + item) for item in blocked):
                continue
            seen.add(host)
            candidate = f"{parsed.scheme}://{host}"
            try:
                page = self.oturum.get(candidate, allow_redirects=True, timeout=12)
                if page.ok and self._domain_icerigi_uygun(eklenti_adi, page):
                    return self._guvenli_domain(page.url) or candidate
            except Exception:
                continue
        return None

    def _domain_durumlarini_oku(self):
        if not os.path.isfile(self.domain_status_path):
            return {}

        try:
            with open(self.domain_status_path, "r", encoding="utf-8") as file:
                veri = json.load(file)
            return veri if isinstance(veri, dict) else {}
        except Exception:
            return {}

    def _domain_durumunu_guncelle(
        self,
        durumlar,
        eklenti_adi,
        mevcut_domain,
        kontrol_domaini=None,
        durum="unchanged",
    ):
        eski = durumlar.get(eklenti_adi, {})

        if durum == "changed" and kontrol_domaini:
            durumlar[eklenti_adi] = {
                "status": "changed",
                "domain": kontrol_domaini,
                "previous_domain": mevcut_domain,
                "changed_at": datetime.now(timezone.utc).replace(
                    microsecond=0
                ).isoformat().replace("+00:00", "Z"),
            }
            return

        if durum == "unreachable":
            durumlar[eklenti_adi] = {
                "status": "unreachable",
                "domain": mevcut_domain,
                "previous_domain": eski.get("previous_domain"),
                "changed_at": eski.get("changed_at"),
            }
            return

        if durum == "protected":
            durumlar[eklenti_adi] = {
                "status": "protected",
                "domain": mevcut_domain,
                "previous_domain": eski.get("previous_domain"),
                "changed_at": eski.get("changed_at"),
            }
            return

        durumlar[eklenti_adi] = {
            "status": "unchanged",
            "domain": kontrol_domaini or mevcut_domain,
            "previous_domain": eski.get("previous_domain"),
            "changed_at": eski.get("changed_at"),
        }

    def _domain_durumlarini_yaz(self, durumlar):
        os.makedirs(self.domain_status_dir, exist_ok=True)

        yeni = json.dumps(
            {
                key: durumlar[key]
                for key in sorted(durumlar)
                if isinstance(key, str)
                and isinstance(durumlar[key], dict)
            },
            ensure_ascii=False,
            indent=2,
        ) + "\n"

        changed = False
        eski = ""
        if os.path.isfile(self.domain_status_path):
            with open(self.domain_status_path, "r", encoding="utf-8") as file:
                eski = file.read()
        if yeni != eski:
            with open(self.domain_status_path, "w", encoding="utf-8") as file:
                file.write(yeni)
            changed = True

        index_lines = [
            "# 🌐 NeonCS Domain Durumları",
            "",
            "Bu alan KONTROL.py tarafından otomatik güncellenir.",
            "",
            "| Eklenti | Durum | Güncel Domain | Önceki Domain | Tarih |",
            "|---|---|---|---|---|",
        ]

        for eklenti in sorted(durumlar):
            veri = durumlar[eklenti]
            domain = veri.get("domain", "-")
            status = veri.get("status", "unchanged")
            if status == "changed":
                ikon = "🔄 **DEĞİŞTİ**"
                previous = veri.get("previous_domain") or "-"
                changed_at = veri.get("changed_at") or "-"
            elif status == "unreachable":
                ikon = "⚠️ **ULAŞILAMIYOR**"
                previous = veri.get("previous_domain") or "-"
                changed_at = veri.get("changed_at") or "-"
            elif status == "protected":
                ikon = "🛡️ **KORUMALI**"
                previous = veri.get("previous_domain") or "-"
                changed_at = veri.get("changed_at") or "-"
            else:
                ikon = "✅ **DEĞİŞMEDİ**"
                previous = "-"
                changed_at = "-"
            index_lines.append(
                "| **" + eklenti + "** | " + ikon + " | "
                + "[" + domain + "](" + domain + ") | "
                + previous + " | " + changed_at + " |"
            )

        index_lines.extend([
            "",
            "### İkonlar",
            "",
            "🔄 **DEĞİŞTİ** = Son kontrolde domain değişti.",
            "",
            "✅ **DEĞİŞMEDİ** = Son kontrolde domain aynı kaldı.",
            "",
            "⚠️ **ULAŞILAMIYOR** = Domain yanıt vermedi veya içerik doğrulanamadı.",
            "",
            "🛡️ **KORUMALI** = Domain Cloudflare/anti-bot yanıtı veriyor; domain değiştiği anlamına gelmez.",
        ])

        index_yeni = "\n".join(index_lines) + "\n"
        old_index = ""
        if os.path.isfile(self.domain_status_index_path):
            with open(self.domain_status_index_path, "r", encoding="utf-8") as file:
                old_index = file.read()
        if index_yeni != old_index:
            with open(self.domain_status_index_path, "w", encoding="utf-8") as file:
                file.write(index_yeni)
            changed = True

        for eklenti in sorted(durumlar):
            veri = durumlar[eklenti]
            domain = veri.get("domain", "-")
            status = veri.get("status", "unchanged")
            if status == "changed":
                ikon = "🔄 **DEĞİŞTİ**"
                previous = veri.get("previous_domain") or "-"
                changed_at = veri.get("changed_at") or "-"
            else:
                ikon = "✅ **DEĞİŞMEDİ**"
                previous = "-"
                changed_at = "-"
            content = (
                "# " + ikon + " " + eklenti + "\n\n"
                + "**Güncel domain:** [" + domain + "](" + domain + ")\n\n"
                + "**Önceki domain:** " + previous + "\n\n"
                + "**Değişiklik tarihi:** " + changed_at + "\n"
            )
            path = os.path.join(self.domain_status_dir, eklenti + ".md")
            old = ""
            if os.path.isfile(path):
                with open(path, "r", encoding="utf-8") as file:
                    old = file.read()
            if content != old:
                with open(path, "w", encoding="utf-8") as file:
                    file.write(content)
                changed = True

        return changed

    def _domain_manifestini_yaz(self, domains):
        os.makedirs(os.path.dirname(self.domain_manifest_path), exist_ok=True)

        eski = ""
        if os.path.isfile(self.domain_manifest_path):
            with open(self.domain_manifest_path, "r", encoding="utf-8") as file:
                eski = file.read()

        try:
            mevcut = json.loads(eski) if eski else {}
        except Exception:
            mevcut = {}

        mevcut.update(domains)

        temiz = {
            key: value
            for key, value in sorted(mevcut.items())
            if isinstance(key, str)
            and isinstance(value, str)
            and value.startswith(("http://", "https://"))
        }

        yeni = json.dumps(
            temiz,
            ensure_ascii=False,
            indent=2,
        ) + "\n"

        if yeni == eski:
            return False

        with open(self.domain_manifest_path, "w", encoding="utf-8") as file:
            file.write(yeni)

        return True

    @property
    def mainurl_listesi(self):
        result = {}
        for yol in self.kt_dosyalari:
            mainurl = self._mainurl_bul(yol)
            if mainurl:
                result[yol] = mainurl
        return result

    def guncelle(self):
        domains = {}
        durumlar = self._domain_durumlarini_oku()
        kaynaklar = self.mainurl_listesi

        for dosya, mainurl in kaynaklar.items():
            eklenti_adi = self._eklenti_adi_bul(dosya)
            konsol.log(f"[~] Kontrol Ediliyor : {eklenti_adi}")

            final_url = None

            # 1) Kayıtlı domain -> 2) aday domainler -> 3) kontrollü web keşfi.
            for aday in self._domain_adaylari(eklenti_adi, mainurl):
                try:
                    response = self.oturum.get(
                        aday,
                        allow_redirects=True,
                        timeout=20,
                    )
                    protected = self._anti_bot_korumasini_tespit_et(response)
                    if not response.ok and not protected:
                        raise RuntimeError(f"HTTP {response.status_code}")
                    if not protected and not self._domain_icerigi_uygun(eklenti_adi, response):
                        raise RuntimeError("site içeriği doğrulanamadı")
                    final_url = response.url.rstrip("/")
                    kontrol_durumu = "protected" if protected else (
                        "changed" if aday.rstrip("/") != mainurl else "unchanged"
                    )
                    break
                except Exception as hata:
                    konsol.log(
                        f"[!] Aday başarısız : {aday} -> "
                        f"{type(hata).__name__}: {hata}"
                    )

            if final_url is None:
                final_url = self._arama_ile_domain_ara(eklenti_adi, mainurl)

            if final_url is None:
                konsol.log(f"[!] Kontrol Edilemedi : {mainurl}")
                domains[eklenti_adi] = self._guvenli_domain(mainurl) or mainurl
                self._domain_durumunu_guncelle(
                    durumlar,
                    eklenti_adi,
                    mainurl,
                    durum="unreachable",
                )
                continue

            final_url = self._guvenli_domain(final_url)
            if not final_url:
                konsol.log(f"[!] Geçersiz domain yanıtı : {mainurl}")
                domains[eklenti_adi] = self._guvenli_domain(mainurl) or mainurl
                self._domain_durumunu_guncelle(
                    durumlar,
                    eklenti_adi,
                    mainurl,
                    durum="unreachable",
                )
                continue

            domains[eklenti_adi] = final_url
            if final_url != mainurl:
                kontrol_durumu = "changed"
            elif kontrol_durumu != "protected":
                kontrol_durumu = "unchanged"

            self._domain_durumunu_guncelle(
                durumlar,
                eklenti_adi,
                mainurl,
                final_url,
                durum=kontrol_durumu,
            )
            konsol.log(f"[+] Kontrol Edildi   : {mainurl}")

            if mainurl == final_url:
                continue

            if self._mainurl_guncelle(dosya, mainurl, final_url):
                yeni_ver = self._versiyonu_artir(
                    os.path.join(self.base_dir, eklenti_adi, "build.gradle.kts")
                )
                konsol.log(
                    f"[»] {mainurl} -> {final_url}"
                    + (f" | v{yeni_ver}" if yeni_ver else "")
                )

        # Hiçbir domain kontrolü başarısız olduğunda bile mevcut adresi koru.
        for dosya, mainurl in kaynaklar.items():
            domains.setdefault(
                self._eklenti_adi_bul(dosya),
                self._guvenli_domain(mainurl) or mainurl,
            )

        if self._domain_manifestini_yaz(domains):
            konsol.log("[+] NeonCore domain manifesti güncellendi")
        else:
            konsol.log("[=] NeonCore domain manifesti güncel")

        if self._domain_durumlarini_yaz(durumlar):
            konsol.log("[+] Domain-status sistemi güncellendi")
        else:
            konsol.log("[=] Domain-status sistemi güncel")


if __name__ == "__main__":
    MainUrlUpdater().guncelle()
