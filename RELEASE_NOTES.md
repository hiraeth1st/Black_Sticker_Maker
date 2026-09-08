# Black Sticker Maker 1.0.0

Android 9 ve üzeri. Aşağıdaki APK'yı indirip mevcut uygulamanın üzerine kurabilirsiniz.

- Tek görsel, GIF veya video seçerek ayrı paket oluşturma.
- Mevcut pakette bir çıkartmaya dokunup **Ayrı paket yap** ile dönüştürmeden yeniden kullanma.
- 1–2 çıkartmalı paketlerin aktarım düğmeleri açık; sonraki içe aktarmalarda paket kimlikleri korunur.
- Tekli aktarımı reddeden WhatsApp sürümleri için isteğe bağlı **3 kopyalı uyumlu paket**. Bu seçenek gerçekten üç aynı çıkartma içerir; tek çıkartmalı paket gibi gösterilmez.
- Toplu klasör, dosya ve ZIP içe aktarma, sabit/hareketli dönüştürme, önizleme ve yedekleme devam eder.
- Debug kapalı, imzalı release APK. Derleme, lint ve Android emülatör testleri geçmeden yayımlanmaz.

## WhatsApp uyumluluğu

WhatsApp'ın yayımladığı üçüncü taraf Android paket sözleşmesi en az 3 çıkartma ister: https://github.com/WhatsApp/stickers/blob/main/Android/README.md
Tekli paketi uygulama oluşturur ve WhatsApp'a sunar; kabul edilmesi WhatsApp sürümüne bağlıdır. Sticker.ly'nin özel entegrasyonu bu uygulamada uygulanmış değildir. Tekli veya 3 kopyalı aktarımın fiziksel WhatsApp cihazında kabulü henüz doğrulanmamıştır. Emülatör testleri dönüşümü, paket kalıcılığını ve içerik sağlayıcısının sunduğu dosyaları doğrular.

## Mevcut kurulumdan güncelleme

0.1.0 ile güncelleme uyumu için aynı depodaki herkese açık geliştirme imzası korunmuştur. Bu gizli üretim anahtarı değildir. APK release derlemesidir ancak imza güvenliği bakımından mağaza dağıtımına hazır değildir. Veritabanı biçimi değiştirilmez; paketler korunur. Güncellemeden önce uygulama içinden ZIP yedeği alabilirsiniz. ZIP yeniden içe alma paketleri yeniden gruplar, kimlikleri birebir geri yüklemez.
