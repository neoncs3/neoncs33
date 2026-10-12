# URL ile otomatik CloudStream eklentisi oluşturma

## Kullanım

1. Depoda **Actions** sekmesini aç.
2. **Otomatik Eklenti Oluşturucu** iş akışını seç.
3. **Run workflow** düğmesine bas.
4. Tek alana sitenin ana URL'sini yaz ve çalıştır. Örnek: https://ornek-site.example/

Klasör adı, sağlayıcı sınıfı, ana sayfa/kategori bağlantıları, arama kalıpları, Kotlin dosyası ve eklenti manifesti URL'den otomatik üretilir. Aynı alan adı için üretilmiş modül varsa otomatik oluşturulmuş dosyalar yenilenir; el ile yazılmış bir modülün üzerine yazılmaz.

## Otomatik kontrol edilen alanlar

Araç, erişilebilen ana sayfayı ve seçilen aynı alan adı sayfalarını inceler. Afişler, IMDb/puan işaretleri, türler, oyuncu/cast bilgileri, JSON-LD metadata, arama formu, sezon/bölüm bağlantıları, altyazı izleri ve video/iframe/HLS ipuçlarını arar. Sonuçları auto-generated/reports klasörüne raporlar.

Üretilen CloudStream eklentisi sayfa ayrıntılarında genel metadata çıkarımı ve sezon/bölüm bağlantılarını toplama mantığı içerir. Video bağlantılarında NeonCore'un HTML, iframe ve doğrudan medya bağlantısı çözümlemesini kullanır; HTML'de açıkça erişilebilen altyazı izlerini de bildirir.

## Derleme ve oynatma

Otomatik oluşturucu önce kendi testini ve kaynak biçimi kontrolünü çalıştırır. Ardından yeni modülü depoya kaydeder, CloudStream Derleyici iş akışını başlatır ve bu derleme sonucunu bekler. Derleme başarılı olursa NeonCS Gerçek Oynatma Testi akışı devreye girerek emülatörde oynatma tanılamasını çalıştırır. Derleme ve oynatma durumları Actions sekmesinden izlenir.

## Gerçekçi sınırlar

- URL tek başına, sitede hiç gösterilmeyen IMDb puanını, oyuncu listesini, bölüm listesini veya altyazıyı garanti etmez. Rapor alanların gerçekten gözlenip gözlenmediğini belirtir.
- Her site farklı HTML ve JavaScript kullanabilir; genel algılama her özel oynatıcı API'sini kusursuz tanıyamaz. Rapor, derleme başarısını gerçek oynatma başarısından ayrı tutar.
- Oturum açma duvarı, CAPTCHA, bot koruması, DRM veya başka erişim kontrolleri aşılmaz. Koruma/HTTP engeli algılanırsa raporda uyarı verilir.
- URL'nin erişilebilir olması veya iframe bulunması, bir videonun kesin oynayacağını kanıtlamaz. Gerçek oynatma raporu ayrı kontrol edilir.
- URL kamuya açık HTTP/HTTPS adresi olmalıdır; yerel ağ ve özel IP adreslerine istek göndermek güvenlik nedeniyle engellenir.

Başlatma bağlantısı: https://github.com/neoncs3/neoncs33/actions/workflows/OtomatikEklentiOlustur.yml
