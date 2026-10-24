# RekaminAja 🎬

**Perekam layar Android gratis & sumber terbuka — tanpa watermark, tanpa iklan, tanpa pelacakan.**

[![CI](https://github.com/xykal/RekaminAja/actions/workflows/ci.yml/badge.svg)](https://github.com/xykal/RekaminAja/actions/workflows/ci.yml)
[![Release](https://github.com/xykal/RekaminAja/actions/workflows/release.yml/badge.svg)](https://github.com/xykal/RekaminAja/releases)
![Android](https://img.shields.io/badge/Android-7.0%2B%20(API%2024)-3DDC84)
![License](https://img.shields.io/badge/License-MIT-blue)

> Versi “XRecorder tapi punya sendiri”: native Kotlin, ringan, dan punya fitur
> privasi yang tidak ada di recorder populer — **Zona Sensor permanen di video**
> dan **jeda otomatis saat aplikasi sensitif tampil**.

## 📱 Unduh

Ambil APK terbaru di halaman [**Releases**](https://github.com/xykal/RekaminAja/releases)
(`RekaminAja-vX.Y.Z.apk`), lalu install seperti biasa.
*Rencana: APKPure dulu, Google Play menyusul.*

## ✨ Fitur

| Fitur | Keterangan |
|---|---|
| 🎥 Rekam layar HD | Resolusi native–480p, 15–60 FPS, bitrate otomatis/manual s.d. 24 Mbps |
| 🎧 Audio internal | Android 10+ via AudioPlaybackCapture; ada mode **internal + mic** |
| 🎤 Mikrofon / tanpa suara | Engine MediaRecorder native (paling stabil) |
| ⏸️ Jeda & lanjut | Termasuk kontrol dari notifikasi |
| 🫧 Bola melayang | Timer, panel kontrol: stop/jeda/facecam/sensor/gambar |
| 📸 Screenshot | Sekali ketuk, tersimpan di Pictures/RekaminAja |
| 🤳 Facecam | Overlay kamera bundar (Camera2), seret & tekan-tahan ganti kamera |
| 🖊️ Gambar di layar | Kuas overlay — coretan ikut terekam, cocok buat tutorial |
| ⬛ **Zona Sensor** | Kotak hitam yang disetel di editor, digambar selama merekam → tersensor **permanen** di file video |
| 🔒 **Auto-pause aplikasi sensitif** | Pilih app bank/e-wallet/chat — rekaman jeda otomatis + layar tertutup saat app itu tampil |
| ✂️ Trim video | Potong awal/akhir tanpa re-encode (cepat & lossless, tanpa FFmpeg) |
| 🖼️ Galeri bawaan | Putar, bagikan, hapus hasil rekaman & screenshot |
| ⚡ Quick Settings Tile + shortcut launcher | Mulai rekam dari panel notifikasi |
| ⏱️ Hitung mundur | Overlay 3–2–1 sebelum mulai |
| 🎨 Material 3 + Material You | Dynamic color Android 12+, UI bahasa Indonesia & Inggris |
| 🚫📶 **Tanpa izin INTERNET** | Tidak ada iklan/analytics; rekaman tidak pernah meninggalkan HP |

Rekaman tersimpan di **`Movies/RekaminAja`**, screenshot di **`Pictures/RekaminAja`**.

## 🔒 Cara kerja Privacy Shield

Aplikasi overlay (bola melayang, facecam, dst.) memang **ikut tertangkap** oleh
MediaProjection. RekaminAja memanfaatkan perilaku ini alih-alih melawannya:

1. **Zona Sensor** = jendela overlay hitam pekat (tidak menghalangi sentuhan).
   Apa pun yang ada di baliknya — notifikasi, nomor HP, chat — tertutup di video final.
2. **Auto-pause sensitif** memantau aplikasi latar depan (Usage Access, hanya aplikasi
   yang *kamu* pilih). Saat app sensitif tampil: rekaman dijeda + pelindung penuh dipasang.
   Catatan: deteksi Usage Access bisa terlambat 1–2 detik; aplikasi dengan `FLAG_SECURE`
   (kebanyakan m-banking) sudah otomatis hitam dari sananya.
3. Audio internal mengikuti aturan Android: app yang melarang perekaman audionya
   akan terdengar hening — itu kebijakan platform, bukan bug.

## 🧱 Arsitektur & stack (2026)

- **Native Kotlin** — AGP **9.4.1** (Built-in Kotlin), Gradle **9.6.0**, JDK 17
- `minSdk 24` (Android 7.0) · `targetSdk 36` (Android 16) · `compileSdk 37` (Android 17)
- Dependency ramping: AndroidX Core · AppCompat · Material · RecyclerView
- `ScreenRecordService` (foreground service `mediaProjection|microphone|camera`)
  mengikuti aturan Android 14+: FGS dulu → `registerCallback()` → `createVirtualDisplay()`
- Dua engine: `MediaRecorderEngine` (mic/senyap, pause native) dan
  `InternalAudioEngine` (MediaCodec H.264 + AAC + MediaMuxer, mix PCM)
- Release: R8 minify + shrink resources

## 🛠️ Build dari source

Butuh: JDK 17 + Android SDK API 37 & Build-Tools 37.0.0.

```bash
git clone https://github.com/xykal/RekaminAja.git
cd RekaminAja
./gradlew assembleDebug     # APK debug
./gradlew assembleRelease   # APK release (butuh signing, lihat docs/BUILD_RELEASE.md)
```

Build & rilis resmi dibuat oleh **GitHub Actions**:
push tag `v*` → APK + AAB + `SHA256SUMS.txt` terbit sebagai GitHub Release.
Panduan signing & publikasi (termasuk APKPure): **[docs/BUILD_RELEASE.md](docs/BUILD_RELEASE.md)**

## 🗺️ Roadmap

- [ ] Editor lanjutan (teks/stiker, musik latar)
- [ ] Rekaman terjadwal & batas durasi
- [ ] Pilihan encoder HEVC untuk ukuran file lebih kecil
- [ ] Deteksi teks sensitif otomatis (OCR on-device) untuk sensor pintar
- [ ] Publikasi APKPure → Google Play

## 🤝 Kontribusi

Issue dan PR dipersilakan. Untuk perubahan besar, buka issue dulu biar searah.

## 📄 Lisensi & privasi

[MIT](LICENSE) · [Privacy Policy](PRIVACY_POLICY.md) — tidak ada data yang dikumpulkan, titik.

---

### English (short)

**RekaminAja** is a free & open-source Android screen recorder (native Kotlin):
HD recording up to 60 FPS, internal audio (Android 10+), internal+mic mix, floating
ball, facecam, draw-on-screen, one-tap screenshots, built-in gallery & lossless video
trimming, Quick Settings tile — plus a **Privacy Shield** with permanent censor zones
and auto-pause for your chosen sensitive apps. **No watermark, no ads, no tracking,
no INTERNET permission at all.** Android 7.0+ (API 24), built with AGP 9.4.1 /
Gradle 9.6 against SDK 37. Download the APK from
[Releases](https://github.com/xykal/RekaminAja/releases).
