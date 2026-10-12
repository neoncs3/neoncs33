# URL ile otomatik CloudStream eklentisi oluşturma

## Kullanım

1. Depoda **Actions** sekmesini aç.
2. **Otomatik Eklenti Oluşturucu** iş akışını seç.
3. **Run workflow** düğmesine bas.
4. Tek alana sitenin ana URL'sini yaz ve çalıştır. Örnek: https://ornek-site.example/

Klasör adı, sağlayıcı sınıfı, ana sayfa/kategori bağlantıları, arama kalıpları, Kotlin dosyası ve eklenti manifesti URL'den otomatik üretilir. Aynı alan adı için üretilmiş modül varsa otomatik oluşturulmuş dosyalar yenilenir; el ile yazılmış bir modülün üzerine yazılmaz.

## Otomatik kontrol edilen alanlar

Araç, erişilebilen ana sayfayı ve seçilen aynı alan adı sayfalarını inceler. Afişler, IMDb/puan işaretleri, türler, oyuncu/cast bilgileri, JSON-LD metadata, arama formu, sezon/bölüm bağlantıları, altyazı izleri ve video/iframe/HLS ipuçlarını arar. Kullanıcı profilleri, avatarlar, yazar/üye bağlantıları, yorumlar, forumlar, tartışmalar, başlıklar ve takvim gibi katalog dışı içerikleri ayıklar; afişi olmayan kartları sonuç listesine eklemez. Bayrak/ülke ikonu gibi gezinme görsellerini de afiş olarak kabul etmez. Arama endpointi sorguyu yok sayıp ana sayfayı döndürürse, alakasız kartlar sorgu sonucuna karışmaması için başlık/adres eşleşmesiyle filtrelenir. Sonuçları auto-generated/reports klasörüne raporlar.

Her rapor, HTML taramasında görülen sinyallere dayalı 100 üzerinden bir **analiz kalite puanı**, derleme öncesi kalite kapısı ve eksik görünen özellikler için öneriler içerir. Katalog afişlerinden örnek başlıklar çıkarılır ve gerçek oynatma testinde önce bu sitede gerçekten bulunan başlıklar aranır. Aynı modül sonraki kez tarandığında sinyal, kategori, katalog kartı ve örnek başlık değişimleri güvenli bir JSON parmak izinde karşılaştırılır; sorgu/token bilgileri kaydedilmez. Sayfa HTML'sinde açıkça bulunan en fazla üç doğrudan .m3u8/.mpd/.mp4 adresine sınırlı bir HTTP Range kontrolü yapılır. İmzalı bağlantıların sorgu/token bölümü rapora yazılmaz. Bu kontrol sadece URL'nin o anda medya yanıtı döndürüp döndürmediğini ölçer; oynatma başarısı olarak raporlanmaz. Taranan HTML bağlantılarındaki sezon/bölüm numaraları ayrıca sayılır; arada görünmeyen bölüm numaraları varsa bunlar kesin hata değil, kontrol edilmesi gereken olası boşluklar olarak işaretlenir.

Üretilen CloudStream eklentisi sayfa ayrıntılarında genel metadata çıkarımı ve sezon/bölüm bağlantılarını toplama mantığı içerir. Video bağlantılarında NeonCore'un HTML, iframe ve doğrudan medya bağlantısı çözümlemesini kullanır; HTML'de açıkça erişilebilen altyazı izlerini de bildirir.

## Derleme ve oynatma

Otomatik oluşturucu önce kendi testini ve kaynak biçimi kontrolünü çalıştırır. Ardından yeni modülü depoya kaydeder, CloudStream Derleyici iş akışını başlatır ve bu derleme sonucunu bekler. Derleme başarılı olursa NeonCS Gerçek Oynatma Testi akışı devreye girer; test, sabit test sağlayıcılarına ek olarak builds/plugins.json içindeki tüm otomatik oluşturulmuş Auto*.cs3 paketlerini de emülatöre yükler. Yüklenen paketlerin manifest hash'i ve boyutu doğrulanır; otomatik oluşturulan sağlayıcılar için önce taramada bulunan afişli katalog başlıkları arama sorgusu olarak kullanılır, bunlar bulunamazsa genel yedek sorgular denenir. Ardından detay sayfası, bağlantı çözümleme ve ExoPlayer üzerinden gerçek oynatma denenir. Derleme ve oynatma durumları Actions sekmesinden izlenir.

## Gerçekçi sınırlar

- URL tek başına, sitede hiç gösterilmeyen IMDb puanını, oyuncu listesini, bölüm listesini veya altyazıyı garanti etmez. Rapor alanların gerçekten gözlenip gözlenmediğini belirtir.
- Her site farklı HTML ve JavaScript kullanabilir; genel algılama her özel oynatıcı API'sini kusursuz tanıyamaz. Rapor, derleme başarısını gerçek oynatma başarısından ayrı tutar.
- Oturum açma duvarı, CAPTCHA, bot koruması, DRM veya başka erişim kontrolleri aşılmaz. Koruma/HTTP engeli algılanırsa raporda uyarı verilir.
- URL'nin erişilebilir olması veya iframe bulunması, bir videonun kesin oynayacağını kanıtlamaz. Gerçek oynatma raporu ayrı kontrol edilir.
- Derleme başarısız olursa yeni .cs3 paketleri yayımlanmaz; önceki yayımlanmış paketler derleme adımı başarısız olduğu için değiştirilmez.
- URL kamuya açık HTTP/HTTPS adresi olmalıdır; yerel ağ ve özel IP adreslerine istek göndermek güvenlik nedeniyle engellenir.

Başlatma bağlantısı: https://github.com/neoncs3/neoncs33/actions/workflows/OtomatikEklentiOlustur.yml
