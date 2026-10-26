# RekaminAja

**Perekam layar Android gratis dan sumber terbuka — tanpa watermark, tanpa iklan, tanpa pelacakan.**

RekaminAja adalah perekam layar native Kotlin yang ringan untuk Android 7.0 ke atas.
Selain fitur perekaman yang lengkap, RekaminAja membawa dua fitur privasi yang jarang
ditemukan di perekam layar populer: Zona Sensor yang tersensor permanen di file video,
dan jeda otomatis saat aplikasi sensitif pilihanmu tampil di latar depan.

Status build dan rilis terbaru tersedia di
[GitHub Actions](https://github.com/xykal/RekaminAja/actions) dan
[Releases](https://github.com/xykal/RekaminAja/releases).

## Unduh

Ambil APK terbaru di halaman [Releases](https://github.com/xykal/RekaminAja/releases),
lalu install seperti biasa:

- `RekaminAja-vX.Y.Z-universal.apk` — satu APK kecil pure Kotlin untuk semua
  arsitektur yang didukung Android 7.0 ke atas.
- File `.aab` disediakan untuk distribusi app store dan tidak perlu dipasang manual.

Rencana distribusi: APKPure terlebih dahulu, Google Play menyusul.

## Fitur

| Fitur | Keterangan |
|---|---|
| Rekam sampai UHD/120 FPS | Native, UHD 4K (maks 3840x2160), QHD 1440p, 1080p, 720p, 540p, dan 480p; 24-120 FPS mengikuti panel dan encoder, H.264 atau H.265/HEVC, bitrate manual sampai 100 Mbps, profil kualitas, penurunan target yang aman, dan estimasi storage per menit. UHD menjaga rasio dan dapat meng-upscale panel lebih rendah untuk output; detail asli tetap dibatasi sumber panel |
| Audio internal | Android 10+ melalui AudioPlaybackCapture, termasuk mode internal + mikrofon |
| Mikrofon / tanpa suara | Jalur MediaRecorder native yang stabil untuk mode standar |
| Audio Lab | Efek suara podcast yang sepenuhnya opsional: reduksi noise sistem, noise gate, kompresor, dan penguat mikrofon. Semua mati secara default |
| Deck Efek Suara | Voice FX 2.0 dengan satu jalur mic live yang konsisten untuk Mikrofon dan Internal + Mikrofon: Super Bersih, Ngebas Podcast, Echo, Bergema, Alien, Robot, Cempreng, transisi preset/gain bebas klik, intensitas 0 yang benar-benar kering, dan volume mic 10-200% |
| Jeda dan lanjut | Termasuk kontrol dari notifikasi custom berisi timer, jeda/stop, dan pengatur volume mic |
| Ringkasan kesiapan | Layar utama menampilkan resolusi/FPS/codec efektif setelah dicek terhadap perangkat, perkiraan ukuran per menit, izin yang belum lengkap, dan status sesi tangkapan |
| Bola melayang | Bola gaya tab tepi yang setengahnya menyelip keluar layar saat diam; visual mungil dengan target sentuh minimum 48dp, jarak awal antarbola lebih lega, ukuran 34-56dp dan opasitas bisa diatur, chip waktu terpisah saat merekam, ketuk untuk toolbox, tekan-tahan untuk mulai/stop cepat, dan seret ke lingkaran hapus di bawah untuk menyembunyikan |
| Screenshot | Bola kamera terpisah dengan petunjuk pertama, screenshot instan selama sesi izin tangkapan aktif, dapat diambil saat merekam dari layar utama atau toolbox, kontrol RekaminAja disembunyikan sesaat agar tidak ikut terfoto, lalu tersedia pratinjau notifikasi dengan aksi Lihat dan Bagikan |
| Facecam | Overlay kamera bundar berbasis Camera2, bisa diseret, tekan-tahan untuk ganti kamera |
| Gambar di layar | Kuas overlay; coretan ikut terekam, cocok untuk tutorial |
| Zona Sensor | Kotak hitam yang diatur di editor dan digambar selama merekam, sehingga tersensor permanen di file video |
| Auto-pause aplikasi sensitif | Pilih aplikasi bank, e-wallet, atau chat; rekaman jeda otomatis dan layar tertutup saat aplikasi itu tampil |
| Trim video | Potong awal/akhir tanpa re-encode (cepat dan lossless, tanpa FFmpeg) |
| Galeri bawaan | Putar, bagikan, dan hapus hasil rekaman serta screenshot |
| Quick Settings Tile dan shortcut launcher | Mulai rekam dari panel notifikasi atau layar utama |
| Hitung mundur | Overlay 3-2-1 sebelum perekaman dimulai |
| Antarmuka Light Clean | UI custom yang terang dan bersih, digambar sendiri end-to-end, tersedia dalam bahasa Indonesia dan Inggris |
| Tanpa izin INTERNET | Tidak ada iklan dan tidak ada analytics; rekaman tidak pernah meninggalkan perangkat |

Rekaman tersimpan di `Movies/RekaminAja`, screenshot di `Pictures/RekaminAja`.

## Audio Lab

Audio Lab membuat suara mikrofon terdengar lebih bersih dan lebih dekat ke karakter
mikrofon podcast:

- **Reduksi noise sistem** memakai noise suppressor, penghilang gema, dan penguat
  otomatis bawaan perangkat bila tersedia.
- **Noise gate** memotong desis latar saat kamu tidak berbicara.
- **Kompresor podcast** meratakan bagian keras dan pelan, lalu limiter lembut menjaga
  suara agar tidak pecah.
- Tersedia preset Off, Natural, Podcast, dan Studio, atau atur sendiri tiap efek dan
  penguat mikrofonnya.

Semua efek **mati secara default**. Mikrofon dan Internal + Mikrofon selalu melewati
jalur mic live yang sama agar preset dapat diganti di tengah rekaman tanpa perilaku
yang berubah-ubah. Preset Normal pada volume 100% dengan efek podcast mati adalah
bypass mentah. Bila perangkat menolak jalur live tersebut, aplikasi otomatis kembali
ke mikrofon standar dan memberi tahu pengguna.

## Cara kerja Privacy Shield

Jendela overlay memang ikut tertangkap oleh MediaProjection. RekaminAja memanfaatkan
perilaku ini alih-alih melawannya:

1. **Zona Sensor** adalah jendela overlay hitam pekat yang tidak menghalangi sentuhan.
   Apa pun di baliknya — notifikasi, nomor telepon, isi chat — tertutup di video final.
2. **Auto-pause sensitif** memantau aplikasi latar depan melalui Usage Access, hanya
   untuk aplikasi yang kamu pilih. Saat aplikasi sensitif tampil, rekaman dijeda dan
   pelindung penuh dipasang. Catatan: deteksi Usage Access bisa terlambat 1-2 detik;
   aplikasi dengan `FLAG_SECURE` (kebanyakan m-banking) sudah otomatis tampil hitam.
3. Audio internal mengikuti aturan Android: aplikasi yang melarang perekaman audionya
   akan terdengar hening. Itu kebijakan platform, bukan bug.

## Arsitektur dan stack (2026)

- Native Kotlin: AGP 9.4.1 (Built-in Kotlin), Gradle 9.6.0, JDK 17
- `minSdk 24` (Android 7.0), `targetSdk 36` (Android 16), `compileSdk 37` (Android 17)
- Dependency utama: AndroidX Core, AppCompat, Material, dan RecyclerView
- `ScreenRecordService` (foreground service `mediaProjection|microphone|camera`)
  mengikuti aturan Android 14+: FGS dimulai dulu, lalu `registerCallback()`,
  lalu satu VirtualDisplay persisten dibuat untuk sesi itu
- VirtualDisplay yang sama diarahkan ulang dengan `resize()` dan `setSurface()`
  antara encoder dan `ImageReader`, sehingga rekaman berikutnya dan screenshot
  saat merekam tidak membuat VirtualDisplay berulang
- Dua engine perekaman: `MediaRecorderEngine` untuk jalur standar, dan
  `CodecRecorderEngine` (MediaCodec H.264/HEVC + AAC + MediaMuxer) untuk audio
  internal, mode campuran, dan pemrosesan mikrofon Audio Lab
- Pemrosesan PCM Audio Lab ditulis khusus di `MicDsp` (high-pass, gate, kompresor, limiter)
- Seluruh komponen UI digambar sendiri: tombol rekam, toggle, slider, pilihan, dialog,
  kontrol player, dan panel overlay
- Release: R8 minify dan shrink resources

## Build dari source

Butuh JDK 17, Android SDK API 37, dan Build-Tools 37.0.0. Project ini pure
Kotlin dan tidak membutuhkan NDK, CMake, atau submodule native.

```bash
git clone https://github.com/xykal/RekaminAja.git
cd RekaminAja
./gradlew assembleDebug     # APK debug
./gradlew assembleRelease   # APK release (butuh signing, lihat docs/BUILD_RELEASE.md)
```

Build dan rilis resmi dibuat oleh GitHub Actions: push tag `v*` menghasilkan APK, AAB,
dan `SHA256SUMS.txt` sebagai GitHub Release. Panduan signing dan publikasi, termasuk
APKPure, ada di [docs/BUILD_RELEASE.md](docs/BUILD_RELEASE.md).

## Roadmap

- Alat pascaproduksi: crop, kompres, ubah kecepatan, ekspor GIF, dan anotasi screenshot
- Rekaman terjadwal
- Meter level mikrofon dan tes dengar sebelum merekam
- Deteksi teks sensitif otomatis (OCR on-device) untuk sensor pintar
- Publikasi APKPure, lalu Google Play

## Kontribusi

Issue dan pull request dipersilakan. Untuk perubahan besar, buka issue terlebih dahulu
agar arahnya selaras.

## Lisensi dan privasi

[MIT](LICENSE) · [Privacy Policy](PRIVACY_POLICY.md) — tidak ada data yang dikumpulkan.

---

### English (short)

**RekaminAja** is a free and open-source Android screen recorder written in native
Kotlin: hardware-aware recording up to UHD and 120 FPS where supported, internal
audio on Android 10+, internal + mic mix, an opt-in Audio Lab that makes a phone
microphone sound closer to a podcast mic, floating balls, facecam, draw-on-screen,
one-tap screenshots including while recording, a built-in gallery with
lossless trimming, and a Quick Settings tile — plus a Privacy Shield with permanent
censor zones and auto-pause for your chosen sensitive apps. The interface is a fully
custom Light Clean design. No watermark, no ads, no tracking, and no INTERNET
permission at all. Android 7.0+ (API 24), built with AGP 9.4.1 and Gradle 9.6 against
SDK 37. Download the APK from
[Releases](https://github.com/xykal/RekaminAja/releases).
