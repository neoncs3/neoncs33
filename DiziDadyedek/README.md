# DiziDadyedek

DiziDAD için bağımsız CloudStream sağlayıcısı. Orijinal `DiziDad` klasörü değiştirilmeden tutulur.

## İçerik
- Film ve dizi katalogları; yerli/yabancı, Netflix, anime, Kore, Hint, Pakistan, dublajlı ve bölüm listeleri.
- Afişleri kart bağlantısına göre bulur; LiteSpeed `data-src` alanını geçici `src` görselinden önce okur.
- `Yerli Dizi` / `Yerli Film` gibi genel alt metinleri başlık olarak kullanmaz.
- Arama, sayfalama, detay bilgileri, sezon/bölüm listeleri ve fragman bağlantısı.
- Video bağlantısı çıkarımı çalışan orijinal DiziDad kodundan taşınmıştır.

Kök `settings.gradle.kts`, `build.gradle.kts` olan her klasörü otomatik olarak derlemeye dahil eder.
