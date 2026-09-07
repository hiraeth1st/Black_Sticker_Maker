# Sticker Atölyesi — Android uygulama projesi

**Durum: 0.1.0 APK derlendi; Android 10 emülatöründe 8 test geçti.**
Android derlemesi, Android test APK'sı, lint ve bağımsız Java/WebP kontrolleri başarılı.
[Doğrulanan derleme ve cihaz testleri](https://github.com/hiraeth1st/Black_Sticker_Maker/actions/runs/34105715503).
Kaynak commit: `2203cc4d62ab30e081511ef1aa35d4e659c29320`.

Gerçek WhatsApp uygulamasında paket ekleme ve 10'dan fazla paket aktarımı henüz
fiziksel telefonda doğrulanmadı. Emülatör testleri dönüşümü ve ContentProvider
sözleşmesini doğrular; WhatsApp'ın kendisini çalıştırmaz.

## Kullanım akışı

1. Uygulamayı aç, paket adına örneğin `Kediler` yaz.
2. `Klasör seç` ile görsellerinin bulunduğu klasörü seç; alt klasörler de taranır.
   Alternatif olarak `Dosya / ZIP` ile bir ZIP veya birden fazla dosya seç.
3. Dönüştürme bitince `Kediler · Sabit 1`, `Kediler · Hareketli 1` gibi paketler oluşur.
4. Pakete dokunarak çıkartmaları önizle. Hareketliyi oynatmak için küçük resme dokun.
5. Her paketteki `+` / `WhatsApp’a ekle` düğmesini kullan ve WhatsApp'ın açtığı
   ekranda eklemeyi onayla. Normal WhatsApp ve Business birlikte yüklüyse seçim sunulur.
6. Çıkartmalar, ekleme başarılı olduğunda WhatsApp'ın çıkartma sekmesinden kullanılır.

Bu uygulama için 2.000 görseli sohbete yüklemen gerekmez; dosyalar telefondan seçilir.

## Kaynak kodunda bulunan özellikler

- Android 9 ve üzeri için Türkçe, yerel Android arayüzü.
- Klasör ve alt klasörler, çoklu dosya, ZIP içe aktarma.
- PNG, JPG/JPEG, WebP, GIF, HEIC/HEIF, BMP; MP4, M4V, MOV, WebM, MKV, 3GP.
  Kapsayıcı uzantısının desteklenmesi her video codec'inin her telefonda açılacağı
  anlamına gelmez. Desteklenmeyen dosyalar rapora yazılır.
- Uygun WebP dosyalarında yeniden sıkıştırma yapılmadan byte düzeyinde koruma.
- Diğer görsellerde 512×512 şeffaf tuvale oran korunarak yerleştirme. Otomatik
  arka plan silme ve kırpma yapılmaz; dosyanın mevcut zemini korunur.
- GIF ve videolardan hareketli WebP; videodaki ses alınmaz. 10 saniyeden uzun
  hareketlilerde ilk 10 saniye kullanılır ve rapora yazılır.
- Boyut sınırına sığmak için kademeli kalite / kare hızı / ayrıntı azaltma.
  Dönüşüm başlangıçta 12 kare/sn, en düşük denemede 4 kare/sn kullanır;
  zor dosyalarda kalite kaybı olabilir, sığmayan dosya atlanır.
- Sabit ve hareketli çıkartmaların ayrı paketlere bölünmesi. Paket başına en
  fazla 30. 31 → 28+3, 32 → 29+3; dosya kopyalayarak paket doldurma yapılmaz.
- 1–2 çıkartma kalan bir tür eksik paket olarak tutulur; sonraki içe aktarmada
  aynı türdeki yeni çıkartmalarla otomatik birleştirilir.
- SHA-256 ile byte düzeyinde aynı dosyaları atlama. Görsel olarak benzer ancak
  farklı kodlanmış dosyaları eşitlemez.
- SQLite üzerinde kalıcı iş kuyruğu, duraklat/devam et, işlenenlerden erken
  paket oluşturma, bozuk dosya ve kalite değişiklikleri raporu.
- Tamamlanan dosyalar işlem kesilince korunur. O sırada işlenen tek dosya yeniden
  dönüştürülür. Android uygulamayı öldürürse uygulama içinden `Devam et` seçilir.
- Paket arama, önizleme ve ad değiştirme.
- Dönüşmüş çıkartmaları ZIP olarak yedekleme. Yedek tekrar içe aktarılabilir;
  paketler yeniden gruplanır. `paketler.json` bilgi amaçlıdır; eski paket kimlikleri
  ve adları birebir geri yüklenmez. Aynı kurulumdaki mevcut dosyalar tekrar alınmaz.
- İnternet izni, hesap, reklam, analiz servisi veya sunucu yok.
- WhatsApp için salt okunur ContentProvider ve resmi paket ekleme Intent'i.

## WhatsApp sınırları ve doğrulanması gereken nokta

[WhatsApp'ın resmî Android belgesi](https://github.com/WhatsApp/stickers/blob/main/Android/README.md):

- Her pakette 3–30 çıkartma; sabit ve hareketli aynı pakette olamaz.
- 512×512 WebP; sabit en fazla 100 KB, hareketli en fazla 500 KB.
- Hareketli dosya en fazla 10 saniye, her kare en az 8 ms.
- 96×96 paket simgesi, en fazla 50 KB.
- Kullanıcı her paketi ayrı ayrı onaylamalıdır. Otomatik “tüm paketleri WhatsApp'a ekle” yoktur.
- Aynı belgede uygulama başına **1–10 paket** yönergesi de bulunur. Bu projenin
  yerel arşivi dinamik sayıda paket tutar; fakat 10'dan fazla paketin tek uygulamadan
  WhatsApp'a eklenmesi bu teslimde doğrulanmış değildir. **2.000 dosyanın tamamının
  aynı WhatsApp kurulumuna aktarılması henüz garanti edilen bir özellik değildir.**
  2.000 aynı tür dosya, arşivde 67 pakete bölünür; tür dağılımı ve eksik gruplar
  toplam paket sayısını etkileyebilir. Gerçek cihaz denemesinde 11. paket ve tüm
  koleksiyon özellikle doğrulanmalıdır.

Hedef, telefonundaki WhatsApp çıkartma sekmesinde paketleri kullanmandır. Sticker.ly
gibi herkese açık paket sayfası / paylaşım bağlantısı / mağaza dağıtımı bu projede
bulunmaz. Başka bir kişinin tüm paketi tek bağlantıdan kurması ayrıca ele alınmalıdır.

## APK üretme

Projenin kök dizini, `settings.gradle` ve `app` klasörünün bulunduğu dizindir.

### GitHub Actions

1. Bu dizinin içeriğini sana ait yeni bir GitHub deposunun köküne koy.
   `.github/workflows/android.yml` ve `development.keystore` dahil olmalı.
2. Actions → **Android APK** → **Run workflow**.
3. Derleme ve lint başarılıysa `Sticker-Atolyesi-APK` çıktısından ZIP'i indir.
   İçindeki `app-debug.apk` telefona kurulacak dosyadır.
4. Ayrı `device-tests` işi örnek PNG/GIF/WebP/video dönüşümlerini ve sağlayıcı
   sözleşmesini Android emülatöründe test eder. **Bu testler gerçek WhatsApp
   uygulamasının onay ekranını test etmez.** İki işin sonucunu da incele.

Bu akış çalıştırıldı: Android derlemesi ve 8 cihaz testi başarılı. APK, yukarıdaki
derlemenin `Sticker-Atolyesi-APK` çıktısında bulunur.

### Android Studio / terminal

Gerekenler: JDK 17, Gradle 8.9, Android SDK Platform 35 ve Build Tools 35.0.0.
[AGP 8.7 uyumluluğu](https://developer.android.com/build/releases/agp-8-7-0-release-notes).

Bu arşiv Gradle'ın çalıştırılabilir dağıtımını veya wrapper JAR'ını içermez.
Android Studio'da proje kökünü açıp Gradle 8.9 kullan veya kurulu Gradle ile:

```sh
gradle --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

Çıktı: `app/build/outputs/apk/debug/app-debug.apk`.

Projede aynı kişisel geliştirme sürümünü güncelleyebilmek için sabit bir
**geliştirme** imza anahtarı bulunur. Parolası standart `android` değeridir;
bu anahtar yayıma uygun gizli bir üretim anahtarı değildir. Mağaza yayını için
yeni ve gizli release anahtarı, sürüm yönetimi ve dağıtım süreci gerekir.

## Doğrulama

Çalıştırılan kontrol:

```sh
python tools/verify_core.py
```

JDK 17 ve Pillow gerektirir. Uygulamanın gerçek `PackPlanner.java` ve `Webp.java`
dosyalarını derler. 0–10.000 dosya için paket bölmeyi, bozuk RIFF uzunluklarını,
WebP sınırlarını ve animasyon yazıcısını kontrol eder. Oluşturulan kayıplı/kayıpsız
animasyonlar bağımsız libwebp/Pillow ile çözümlenir; kare sırası ve şeffaflık
karşılaştırılır. Diğer Java dosyalarında yalnızca sözdizimi taraması yapar;
Android tür denetiminin veya cihaz testinin yerini almaz.

Android 10 emülatöründe çalıştırılan ek test:

```sh
gradle :app:connectedDebugAndroidTest
```

Cihazdaki son kabul denemesi:

1. 31 PNG → 28+3; 32 GIF → 29+3, hareketli ve sabit ayrı.
2. 10 saniyeden uzun video → 10 saniye ve dönüşüm raporu.
3. İşlem ortasında duraklat/devam et ve uygulama sürecini kapatıp yeniden açma.
4. Bozuk dosya + geçerli dosyalar → bozuk dosya raporda, diğerleri paketlerde.
5. WhatsApp ve Business'ta paket ekleme; onayı iptal etme; eksik paket düğmesinin kapalı oluşu.
6. 11. paket, sonra yaklaşık 2.000 dosyalık gerçek arşivin tamamı.
7. Telefonun yeniden başlatılmasından sonra paketlerin okunabilmesi.

Çok sayıda videoyu dönüştürmek telefonu ısıtabilir ve uzun sürebilir. Dosyalar
sırayla işlenir; süre cihaz ve içerikle değişir. ZIP önce uygulamanın geçici iş
dizinine kopyalanır, başarılı iş sonunda temizlenir. Tek kaynak medya 256 MB,
GIF 24 MB, WebP 32 MB, kaynak ZIP 8 GB ile sınırlıdır. Kaynak dosyalar değiştirilmez.
Uygulama verisini temizlemek/kaldırmak yerel paket arşivini siler; önce ZIP yedekle.

## Teknik kaynak

- [WhatsApp çıkartma entegrasyonu](https://github.com/WhatsApp/stickers/blob/main/Android/README.md)
- [WebP RIFF kapsayıcı tanımı](https://developers.google.com/speed/webp/docs/riff_container)
- [Android ImageDecoder](https://developer.android.com/reference/android/graphics/ImageDecoder)
- [Ön plan hizmeti süre sınırları](https://developer.android.com/develop/background-work/services/fgs/timeout)

Android uygulamasının çalışma zamanı üçüncü taraf bağımlılığı yoktur. WebP piksel
kodlamasını Android'in codec'i, video kare çözümlemesini MediaMetadataRetriever,
GIF çizimini Android Movie yapar. RIFF animasyon paketleme ve dosya/paket yönetimi
bu projede yazılmıştır; FFmpeg Kit veya sunucuya medya gönderimi kullanılmaz.
