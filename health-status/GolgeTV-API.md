# GölgeTV API Tanılama

Bu rapor otomatik oluşturulur. Şifre, cookie, bearer token, tam yanıt gövdesi veya medya URL'si içermez.

| Test | Uç nokta | HTTP | İçerik türü | Kayıt | Afişli kayıt | Tanınan alanlar | HTML bağlantı/görsel/afiş |
|---|---|---:|---|---:|---:|---|---:|
| config | https://imgcom.site/panel/rec/recjson.php | ERR:gaierror | - | 0 | 0 | - | 0/0/0 |
| films | https://golgetv.com/filmapi.php | ERR:gaierror | - | 0 | 0 | - | 0/0/0 |
| series | https://golgetv.com/diziapi.php | ERR:gaierror | - | 0 | 0 | - | 0/0/0 |
| movie_search | https://golgetv.com/search.php | ERR:gaierror | - | 0 | 0 | - | 0/0/0 |
| series_search | https://golgetv.com/dizisearch.php | ERR:gaierror | - | 0 | 0 | - | 0/0/0 |
| homepage | https://golgetv.com/ | ERR:gaierror | - | 0 | 0 | - | 0/0/0 |
| configured_channel_json | - | SKIP | - | 0 | 0 | public jsonurl yok | - |

## Yorumlama

- HTTP 401/403 veya ağ hatası: normal katalog isteği erişilemiyor; bu test giriş yapmayı veya korumalı nonce/doğrulama uç noktalarını denemez.
- HTTP 200 ve 0 kayıt: yanıt gövdesi beklenen katalog şemasında değil veya katalog içermiyor.
- Rapor alan adlarını ve sayılarını gösterir; içerik başlıkları, afiş URL'leri, stream URL'leri veya kişisel bilgiler yazılmaz.
