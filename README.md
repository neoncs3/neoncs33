# NeonCS — CloudStream Türkçe Eklenti Deposu

NeonCS, CloudStream için Türkçe içerik sağlayıcılarını derleyen ve durumlarını düzenli kontrol eden bir eklenti deposudur. Otomasyonlar GitHub Actions, Python ve Gradle üzerinden çalışır; **herhangi bir yapay zekâ API anahtarı veya ücretli AI servisi gerekmez.**

[![CloudStream Derleyici](https://github.com/neoncs3/neoncs33/actions/workflows/Derleyici.yml/badge.svg?branch=master)](https://github.com/neoncs3/neoncs33/actions/workflows/Derleyici.yml)
[![Kalite Kontrolü](https://github.com/neoncs3/neoncs33/actions/workflows/Kalite.yml/badge.svg?branch=master)](https://github.com/neoncs3/neoncs33/actions/workflows/Kalite.yml)
[![Domain Kontrolü](https://github.com/neoncs3/neoncs33/actions/workflows/Kontrol.yml/badge.svg?branch=master)](https://github.com/neoncs3/neoncs33/actions/workflows/Kontrol.yml)
[![Provider Sağlığı](https://github.com/neoncs3/neoncs33/actions/workflows/Saglik.yml/badge.svg?branch=master)](https://github.com/neoncs3/neoncs33/actions/workflows/Saglik.yml)
[![Gerçek Oynatma Testi](https://github.com/neoncs3/neoncs33/actions/workflows/Oynatma.yml/badge.svg?branch=master)](https://github.com/neoncs3/neoncs33/actions/workflows/Oynatma.yml)

## Kurulum

CloudStream uygulamasındaki depo ekleme alanına şu adresi ekleyin:

`https://raw.githubusercontent.com/neoncs3/neoncs33/master/repo.json`

Derlenmiş eklentiler ve liste manifesti [builds dalında](https://github.com/neoncs3/neoncs33/tree/builds) yayımlanır. Güncel paket listesi: [plugins.json](https://raw.githubusercontent.com/neoncs3/neoncs33/builds/plugins.json).

## Eklentiler

| Eklenti | Kaynak kodu | Derlenmiş paket | Otomatik durum |
|---|---|---|---|
| AsyaFilmIzle | [Kod](AsyaFilmIzle/) | [CS3](https://raw.githubusercontent.com/neoncs3/neoncs33/builds/AsyaFilmIzle.cs3) | [Durum](health-status/AsyaFilmIzle.md) |
| DiziBoxizle | [Kod](DiziBoxizle/) | [CS3](https://raw.githubusercontent.com/neoncs3/neoncs33/builds/DiziBoxizle.cs3) | [Durum](health-status/DiziBoxizle.md) |
| DiziPal | [Kod](DiziPal/) | [CS3](https://raw.githubusercontent.com/neoncs3/neoncs33/builds/DiziPal.cs3) | [Durum](health-status/DiziPal.md) |
| Dizigecesi | [Kod](Dizigecesi/) | [CS3](https://raw.githubusercontent.com/neoncs3/neoncs33/builds/Dizigecesi.cs3) | [Durum](health-status/Dizigecesi.md) |
| Dramadizilerim | [Kod](Dramadizilerim/) | [CS3](https://raw.githubusercontent.com/neoncs3/neoncs33/builds/Dramadizilerim.cs3) | [Durum](health-status/Dramadizilerim.md) |
| FilmModu | [Kod](FilmModu/) | [CS3](https://raw.githubusercontent.com/neoncs3/neoncs33/builds/FilmModu.cs3) | [Durum](health-status/FilmModu.md) |
| JetFilmizle | [Kod](JetFilmizle/) | [CS3](https://raw.githubusercontent.com/neoncs3/neoncs33/builds/JetFilmizle.cs3) | [Durum](health-status/JetFilmizle.md) |
| SetFilmIzle | [Kod](SetFilmIzle/) | [CS3](https://raw.githubusercontent.com/neoncs3/neoncs33/builds/SetFilmIzle.cs3) | [Durum](health-status/SetFilmIzle.md) |
| SinemaCX | [Kod](SinemaCX/) | [CS3](https://raw.githubusercontent.com/neoncs3/neoncs33/builds/SinemaCX.cs3) | [Durum](health-status/SinemaCX.md) |
| WebDramaTurkey | [Kod](WebDramaTurkey/) | [CS3](https://raw.githubusercontent.com/neoncs3/neoncs33/builds/WebDramaTurkey.cs3) | [Durum](health-status/WebDramaTurkey.md) |

## Otomasyonlar

| İş akışı | Ne yapar? | Çalışma zamanı |
|---|---|---|
| [CloudStream Derleyici](.github/workflows/Derleyici.yml) | Kotlin eklentilerini derler, `.cs3` dosyalarını ve `plugins.json` manifestini doğrular, paketleri `builds` dalına gönderir. | Kaynak değişikliğinde, her gün ve elle |
| [Kalite Kontrolü](.github/workflows/Kalite.yml) | Depo yapısını, sağlayıcı arayüzlerini ve Python sözdizimini denetler. | İlgili dosya değişikliğinde ve elle |
| [Domain Değişiklik Kontrolü](.github/workflows/Kontrol.yml) | Sağlayıcı domainlerini doğrular; anti-bot yanıtlarını değişiklik kanıtı saymaz. Doğrulanmış kaynak değişikliklerini otomatik kaydeder ve yeniden derleme başlatır. | Yaklaşık her 9 saatte bir, ilgili ayar değişince ve elle |
| [Provider Sağlık Kontrolü](.github/workflows/Saglik.yml) | Site erişimini ve sağlayıcı hattının yapısal kontrollerini raporlar. | Her 6 saatte bir, kaynak değişikliğinde ve elle |
| [Gerçek Oynatma Testi](.github/workflows/Oynatma.yml) | CloudStream ve ExoPlayer üzerinde otomatik Android emülatörüyle gerçek bağlantı çözümleme/oynatma testi çalıştırır. | Başarılı derlemeden sonra ve elle |

## Otomatik raporlar

- [Provider sağlık tablosu](health-status/index.md)
- [Gerçek oynatma sonuçları](playback-status/latest.md)
- [Domain durumları](domain-status/index.md)
- [GitHub Actions çalıştırmaları](https://github.com/neoncs3/neoncs33/actions)

## Durumları nasıl yorumlamalı?

- **PASS:** Emülatörde video oynatımının ilerlediği doğrulandı.
- **BLOCKED:** Kaynak site/CDN, otomasyon ortamını 403, 429 veya anti-bot korumasıyla engellemiş olabilir. Bu tek başına eklentinin bozuk olduğunu kanıtlamaz.
- **FAIL:** Test edilen adaylardan çalışan bir oynatma doğrulanamadı; rapordaki hata ayrıntısı incelenmelidir.
- Sağlık tablosundaki yeşil durum, tek başına CloudStream uygulamasında gerçek videonun sonuna kadar oynadığının garantisi değildir. Bunun için gerçek oynatma raporuna bakın.

## Geliştirme

Kaynak doğrulamasını yerel ortamda çalıştırmak için:

```bash
python3 tools/validate_repo.py
python3 -m compileall -q tools KONTROL.py
```

Android/Kotlin derlemesi GitHub Actions üzerinde JDK 17 ve Android SDK ile yapılır. Kaynak değişikliği gönderildiğinde derleme otomatik başlar; başarılı derleme yeni paketleri yayımlar ve ardından gerçek oynatma testini tetikler.

## Lisans ve kaynaklar

Site sağlayıcıları üçüncü taraf web sitelerine erişim sağlar. Bu depo bu sitelerin erişilebilirliğini, içerik doğruluğunu veya sürekli çalışmasını garanti etmez. Kaynak dosyalardaki her sağlayıcının alan adı ve bağlantı çözümleme yöntemleri zamanla değişebilir.
