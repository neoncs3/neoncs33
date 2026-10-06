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
        self.domain_status_path = os.path.join(
            self.base_dir, "NeonCore", "domain-status.json"
        )
        self.readme_path = os.path.join(self.base_dir, "README.md")

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
        kontrol_domaini,
    ):
        eski = durumlar.get(eklenti_adi, {})
        degisti = mevcut_domain != kontrol_domaini

        if degisti:
            durumlar[eklenti_adi] = {
                "status": "changed",
                "domain": kontrol_domaini,
                "previous_domain": mevcut_domain,
                "changed_at": datetime.now(timezone.utc).replace(
                    microsecond=0
                ).isoformat().replace("+00:00", "Z"),
            }
            return

        if eski.get("status") == "changed":
            durumlar[eklenti_adi] = {
                "status": "changed",
                "domain": kontrol_domaini,
                "previous_domain": eski.get("previous_domain"),
                "changed_at": eski.get("changed_at"),
            }
            return

        durumlar[eklenti_adi] = {
            "status": "unchanged",
            "domain": kontrol_domaini,
            "previous_domain": None,
            "changed_at": None,
        }

    def _domain_durumlarini_yaz(self, durumlar):
        os.makedirs(os.path.dirname(self.domain_status_path), exist_ok=True)

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

        eski = ""
        if os.path.isfile(self.domain_status_path):
            with open(self.domain_status_path, "r", encoding="utf-8") as file:
                eski = file.read()

        if yeni == eski:
            return False

        with open(self.domain_status_path, "w", encoding="utf-8") as file:
            file.write(yeni)

        return True

    def _readme_yaz(self, durumlar):
        satirlar = [
            "# NeonCS CloudStream Eklentileri",
            "",
            "## 🌐 Domain Durumları",
            "",
            "Bu tablo, otomatik domain kontrolü tarafından güncellenir.",
            "",
            "| Eklenti | Durum | Güncel Domain | Önceki Domain | Değişiklik |",
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
            else:
                ikon = "✅ **DEĞİŞMEDİ**"
                previous = "-"
                changed_at = "-"

            domain_link = (
                f"[{domain}]({domain})"
                if domain.startswith("http://") or domain.startswith("https://")
                else domain
            )
            previous_link = (
                f"[{previous}]({previous})"
                if previous.startswith("http://") or previous.startswith("https://")
                else previous
            )

            satirlar.append(
                f"| **{eklenti}** | {ikon} | {domain_link} | "
                f"{previous_link} | {changed_at} |"
            )

        satirlar.extend(
            [
                "",
                "### İkonlar",
                "",
                "🔄 **DEĞİŞTİ** = Domain daha önce otomatik kontrolde değişmiş.",
                "",
                "✅ **DEĞİŞMEDİ** = Kayıtlı domain otomatik kontrollerde değişmemiş.",
                "",
                "Domain değiştiğinde mainUrl, eklenti sürümü, NeonCore manifesti ve bu tablo aynı otomatik PR içinde güncellenir.",
            ]
        )

        yeni = "\n".join(satirlar) + "\n"

        eski = ""
        if os.path.isfile(self.readme_path):
            with open(self.readme_path, "r", encoding="utf-8") as file:
                eski = file.read()

        if yeni == eski:
            return False

        with open(self.readme_path, "w", encoding="utf-8") as file:
            file.write(yeni)

        return True

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

            try:
                if eklenti_adi == "RecTV":
                    final_url = self._rectv_ver()
                elif eklenti_adi == "GolgeTV":
                    final_url = self._golgetv_ver()
                else:
                    istek = self.oturum.get(
                        mainurl,
                        allow_redirects=True,
                        timeout=20,
                    )
                    if not istek.ok:
                        raise RuntimeError(f"HTTP {istek.status_code}")
                    final_url = istek.url.rstrip("/")
            except Exception as hata:
                konsol.log(f"[!] Kontrol Edilemedi : {mainurl}")
                konsol.log(f"[!] {type(hata).__name__} : {hata}")
                domains[eklenti_adi] = self._guvenli_domain(mainurl) or mainurl
                continue

            final_url = self._guvenli_domain(final_url)
            if not final_url:
                konsol.log(f"[!] Geçersiz domain yanıtı : {mainurl}")
                domains[eklenti_adi] = self._guvenli_domain(mainurl) or mainurl
                continue

            domains[eklenti_adi] = final_url
            self._domain_durumunu_guncelle(
                durumlar,
                eklenti_adi,
                mainurl,
                final_url,
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
            konsol.log("[+] Domain durumları güncellendi")
        else:
            konsol.log("[=] Domain durumları güncel")

        if self._readme_yaz(durumlar):
            konsol.log("[+] GitHub README domain tablosu güncellendi")
        else:
            konsol.log("[=] GitHub README domain tablosu güncel")


if __name__ == "__main__":
    MainUrlUpdater().guncelle()
