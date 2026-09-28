# Nexus Browser — Android (GitHub Actions ile derleme)

Bu sürüm, Termux'ta Android SDK kurma derdini tamamen ortadan kaldırıyor:
**derleme işini GitHub'ın kendi sunucuları yapıyor.** Sen sadece kodu bir
GitHub deposuna yüklüyorsun, GitHub Actions otomatik olarak derliyor ve
sana indirilebilir bir APK veriyor.

## 1. GitHub'a yükleme (Termux'tan, git ile)

Termux'ta:

```bash
pkg install git -y
cd nexus-android-gradle
git init
git add .
git commit -m "İlk sürüm"
```

GitHub'da (tarayıcıdan) yeni, **boş** bir repo oluştur (README/gitignore
eklemeden — "Create repository" ekranında hiçbir kutucuğu işaretleme).
Sonra Termux'a dön:

```bash
git branch -M main
git remote add origin https://github.com/KULLANICI_ADIN/nexus-browser.git
git push -u origin main
```

(İlk `git push`'ta GitHub kullanıcı adı/şifre yerine bir **Personal Access
Token** isteyecek — GitHub hesap ayarlarından "Developer settings ›
Personal access tokens" üzerinden oluşturup şifre yerine onu yapıştır.)

## 2. Derlemeyi izleme

Push işleminden birkaç saniye sonra GitHub'daki reponun **Actions**
sekmesine git. "Nexus Browser APK Derle" adında bir çalıştırma göreceksin
(otomatik başlar). Yeşil tik olunca tıkla, en altta **Artifacts** bölümünde
`NexusBrowser-debug-apk` dosyasını göreceksin — indir, içinden APK çıkar.

Elle tekrar tetiklemek istersen: Actions sekmesi → soldan workflow'u seç →
"Run workflow" butonu.

## 3. Telefona kurma

İndirdiğin `.apk` dosyasını telefonuna aktar (Termux'a indirdiysen zaten
oradasın), dosya yöneticisinden dokun. "Bilinmeyen kaynaklardan yükleme"
izni istenirse bir kerelik Ayarlar'dan aç.

## Bir şeyler ters giderse

Actions sekmesindeki kırmızı çarpıya tıkla, log'u aç, hata veren adımı
bul ve o kısmı bana yapıştır — Gradle/Kotlin hataları genelde net satır
numarası verir, birlikte düzeltiriz. İlk derlemenin hatasız geçmesi
şart değil, bu normal bir süreç.

---


Önceki Electron/masaüstü denemesinin yerini alan, **gerçekten Android telefonda
çalışan** sürüm. Bu klasördeki kod standart bir **Gradle** Android projesidir —
Termux'ta manuel `aapt2`/`d8` derlemesi artık gerekmiyor, yukarıdaki GitHub
Actions akışı her şeyi hallediyor.

## Mimari

Android'in `WebView` bileşeni (Android'in kendi Chromium motoru — hiçbir
yerde "Chrome" ismi görünmez) üç katmanda kullanılıyor:

```
┌───────────────────────────────┐
│ topBarWebView (sabit, 104dp)   │  assets/top/   → sekme şeridi + araç çubuğu
├───────────────────────────────┤
│ contentContainer               │  Kotlin'in yönettiği gerçek WebView'ler
│  (her sekme = 1 gerçek WebView)│  (görünen siteler burada render edilir)
└───────────────────────────────┘
overlayWebView (tam ekran, varsayılan gizli)  assets/overlay/ → tüm paneller
                                               (menü/geçmiş/yer imi/şifre/gizlilik)
```

`topBarWebView` ve `overlayWebView`, `AndroidHost` adında ortak bir JavaScript
köprüsü (`MainActivity.kt` içindeki `addJavascriptInterface`) üzerinden
Kotlin'e komut gönderir (sekme oluştur/kapat/geçiş, git/geri/ileri, ayar
değiştir, şifre kaydet...). Kotlin, gezinme durumundaki değişiklikleri
(`onPageStarted`/`onPageFinished`) `topBarWebView.evaluateJavascript(...)`
ile geri **topBarWebView**'e bildirir.

Her yeni sekme, `assets/home/index.html`'i (kendi tasarımlı hızlı erişim
sayfası) yükleyen **gerçek** bir `WebView` olarak oluşturulur — Electron
sürümündeki gibi ayrı bir "div" değil, gerçek bir sayfa yükü.

## Taşınan / eklenen özellikler (Faz 1 + Faz 2 + Faz 3 + Faz 4)

**Faz 1-2 (önceki mesajdan):** sekmeler, sabitleme, akıllı adres çubuğu, ileri/geri/yenile,
hızlı erişim sayfası, geçmiş, yer imleri, gizli sekme, reklam/izleyici engelleme,
parmak izi tespiti, HTTPS zorunlu modu, şifre yöneticisi (Android Keystore/AES-GCM),
gizlilik panosu.

**Faz 3 (yeni):**
- **Performans modları** — Performans / Dengeli / Tasarruf / Veri Tasarrufu (Menü → Performans & Veri)
- **Sekme uyutma** — açıldığında arka plandaki sekmeler `WebView.onPause()` ile duraklatılır
- **Resim yükleme sınırı (MB)** — `shouldInterceptRequest` içinde HEAD isteğiyle `Content-Length`
  kontrolü (bazı sunucular HEAD'i desteklemez, o durumda sınırlama atlanır — yaklaşık bir çözüm)
- **Önbellek sınırı (MB)** — uygulamanın önbellek klasörü boyutu her sayfa yüklemesinde
  kontrol edilir, sınır aşılırsa tüm sekmelerin önbelleği temizlenir
- **AI sekme gruplama** — Groq API ile (kendi anahtarınızı Menü → AI Ayarları'na girin),
  açık sekmeleri konularına göre gruplar ve panelde listeler (tab çubuğunu görsel olarak
  otomatik yeniden düzenlemez — bu, kapsam dışı bırakılan bir sonraki adım)
- **Akıllı arama önerileri** — yerel geçmiş/yer imlerinden, AI gerektirmeden, anlık öneri

**Faz 4 (yeni):**
- **Sızıntı kontrolü** — Have I Been Pwned k-anonymity API'si (anahtarsız), Şifre
  Yöneticisi panelinden "Tüm Şifrelerde Sızıntı Kontrolü"
- **Ekran görüntüsü** — `WebView.draw()` ile View'ın ekran pikselleri doğrudan yakalanır,
  bu yüzden sitenin JS tabanlı ekran görüntüsü engelleri by-pass edilir; `Resimler/NexusBrowser`'a kaydedilir
- **PDF olarak kaydet** — Android'in resmi Yazdırma sistemi (`PrintManager`) ile "PDF olarak
  kaydet" seçeneği; `.pdf` linkleri sistemdeki bir PDF uygulamasıyla açılır
- **Bölünmüş ekran** — aktif sekme + seçilen ikinci sekme, ekranı dikey ikiye bölerek
- **AI sayfa özetleme** — güvenilirlik/gizlilik/dolandırıcılık değerlendirmesi (Groq);
  **dürüst not**: model canlı web araması yapamaz, "şikayet var mı" kısmı modelin genel
  bilgisiyle sınırlı, güncel/spesifik şikayet taraması değildir
- **Kişiselleştirme** — vurgu rengi seçimi (Menü → Kişiselleştirme), anlık uygulanır
- **Otomatik güncelleme** — Menü → Güncellemeleri Kontrol Et; kendi barındıracağınız bir
  `version.json` adresini (Ayarlar → Güncelleme URL'si — şu an sadece kod tarafında var,
  arayüze eklenmedi, `updateCheckUrl` ayarını gerekirse elle JSON'a yazabilirsiniz) kontrol eder

## Sınırlamalar (dürüstçe)

- Bu kod burada **derlenip test edilemedi** (Android SDK/Kotlin derleyicisi bu ortamda yok).
  Parantez/süslü parantez dengesi programatik olarak doğrulandı ama bu, mantıksal/tip
  hatalarını yakalamaz. İlk derlemede hata çıkarsa bana yapıştırın, birlikte düzeltiriz.
- Gizli sekmenin çerez/depolama izolasyonu tam değil (Android WebView'in native sınırlaması).
- **Site bildirimleri desteklenmiyor** — sıfırdan yazılan bir WebView tarayıcıda gerçek web
  push bildirimleri (Chrome/Firefox'un yaptığı gibi) pratikte inşa edilemez; bu özellik
  bilinçli olarak kapsam dışı bırakıldı.
- Resim boyutu sınırı ve HTTPS zorunlu modu yaklaşık/kısmi çözümler (yukarıda açıklandı).
- AI sekme gruplama, tab çubuğunu görsel olarak yeniden düzenlemiyor, sadece panelde
  grup önerisi gösteriyor.
- Otomatik güncelleme için `updateCheckUrl` ayarına arayüzden giriş eklenmedi; şu an
  sadece Kotlin/ayarlar JSON'unda destekleniyor.

