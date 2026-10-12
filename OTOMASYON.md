# NeonCS — Otomasyon ve Durum Raporları

Bu dosya, NeonCS deposunun otomatik kontrollerini ve son durum raporlarını toplar. Ana [README.md](README.md) kurulum ve eklenti listesini sade tutar.

**Bu sistemler herhangi bir yapay zekâ API'si veya AI API anahtarı kullanmaz.** GitHub Actions, Python ve Gradle ile çalışırlar.

## İş akışı durumu

[![CloudStream Derleyici](https://github.com/neoncs3/neoncs33/actions/workflows/Derleyici.yml/badge.svg?branch=master)](https://github.com/neoncs3/neoncs33/actions/workflows/Derleyici.yml)
[![Kalite Kontrolü](https://github.com/neoncs3/neoncs33/actions/workflows/Kalite.yml/badge.svg?branch=master)](https://github.com/neoncs3/neoncs33/actions/workflows/Kalite.yml)
[![Domain Kontrolü](https://github.com/neoncs3/neoncs33/actions/workflows/Kontrol.yml/badge.svg?branch=master)](https://github.com/neoncs3/neoncs33/actions/workflows/Kontrol.yml)
[![Provider Sağlığı](https://github.com/neoncs3/neoncs33/actions/workflows/Saglik.yml/badge.svg?branch=master)](https://github.com/neoncs3/neoncs33/actions/workflows/Saglik.yml)
[![Gerçek Oynatma Testi](https://github.com/neoncs3/neoncs33/actions/workflows/Oynatma.yml/badge.svg?branch=master)](https://github.com/neoncs3/neoncs33/actions/workflows/Oynatma.yml)

## Otomasyonlar

| İş akışı | Görevi | Çalışma biçimi |
|---|---|---|
| [CloudStream Derleyici](.github/workflows/Derleyici.yml) | Kotlin eklentilerini derler, paketleri ve `plugins.json` manifestini doğrular, başarılı paketleri `builds` dalına yayımlar. | Kaynak değişikliğinde, her gün ve elle |
| [Kalite Kontrolü](.github/workflows/Kalite.yml) | Depo yapısını, Python sözdizimini ve gerekli kontrol dosyalarını doğrular. | İlgili dosya değişikliklerinde ve elle |
| [Domain Değişiklik Kontrolü](.github/workflows/Kontrol.yml) | Alan adlarını denetler. Cloudflare/anti-bot yanıtını yeni domain kanıtı saymaz; doğrulanmış değişiklikleri kaydeder ve gerekirse yeniden derleme başlatır. | Yaklaşık her 9 saatte bir, ilgili yapılandırma değişince ve elle |
| [Provider Sağlık Kontrolü](.github/workflows/Saglik.yml) | Site erişimini ve sağlayıcı kaynak kodundaki beklenen hatları raporlar. | Zamanlanmış, kaynak değişikliğine bağlı ve elle çalıştırma |
| [Gerçek Oynatma Testi](.github/workflows/Oynatma.yml) | Android emülatöründe CloudStream/ExoPlayer ile gerçek bağlantı çözümleme ve oynatma ilerlemesini test eder. | Başarılı derlemeden sonra ve elle |

## Otomatik raporlar

- [**Provider sağlık tablosu**](health-status/index.md) — HTTP erişimi, yönlendirme adresi, medya ipuçları ve kaynak kodu kontrolleri.
- [**Gerçek oynatma sonuçları**](playback-status/latest.md) — PASS, BLOCKED ve FAIL sonuçlarıyla son ExoPlayer testi.
- [**Domain durum tablosu**](domain-status/index.md) — kayıtlı domainlerin doğrulanmış değişiklik, erişilememe veya anti-bot durumu.
- [**GitHub Actions çalıştırmaları**](https://github.com/neoncs3/neoncs33/actions) — canlı iş akışı günlükleri ve hata ayrıntıları.

## Domain durumları nasıl okunur?

- **DEĞİŞMEDİ:** Kayıtlı adres değişmedi.
- **DEĞİŞTİ:** Yeni alan adı ve sağlayıcı içeriği doğrulandı.
- **ULAŞILAMIYOR:** Yanıt alınamadı veya içerik doğrulanamadı.
- **KORUMALI:** Cloudflare/anti-bot engeli görüldü. Bu, domainin değiştiği anlamına gelmez; doğrulama yapılmadan kayıtlı adres korunur.

## Oynatma durumları nasıl okunur?

- **PASS:** Test sırasında video oynatımının ilerlediği doğrulandı.
- **BLOCKED:** Kaynak site/CDN, otomasyon ortamını HTTP 403/429 veya anti-bot korumasıyla engellemiş olabilir. Tek başına eklentinin bozuk olduğunu kanıtlamaz.
- **FAIL:** Denenen içeriklerde çalışan bir oynatma doğrulanamadı. Rapordaki son hata incelenmelidir.

Sağlık raporundaki yeşil durum yalnız başına videonun oynadığını kanıtlamaz; bu, kaynak kodu yapısı ve HTTP erişimi kontrolüdür. Gerçek oynatma için **Gerçek oynatma sonuçları** raporunu esas alın.

## Yerel kalite kontrolü

Python kontrollerini yerel ortamda çalıştırmak için:

```bash
python3 tools/validate_repo.py
python3 -m compileall -q tools KONTROL.py
```

Android/Kotlin derlemesi GitHub Actions üzerinde JDK 17 ve Android SDK ile yapılır.
