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

- `RekaminAja-vX.Y.Z-full-arm64-v8a.apk` — untuk HP ARM 64-bit modern, termasuk
  Auto Subtitle offline dan model suaranya.
- `RekaminAja-vX.Y.Z-full-armeabi-v7a.apk` — untuk HP ARM 32-bit lama, juga
  termasuk Auto Subtitle offline. Transkripsi akan lebih lambat di hardware lama.
- `RekaminAja-vX.Y.Z-lite-universal.apk` — edisi kecil universal untuk x86 atau
  pengguna yang tidak memerlukan Auto Subtitle.

Rencana distribusi: APKPure terlebih dahulu, Google Play menyusul.

## Fitur

| Fitur | Keterangan |
|---|---|
| Rekam layar HD | Resolusi native sampai 480p, 15-60 FPS, bitrate otomatis atau manual sampai 24 Mbps |
| Audio internal | Android 10+ melalui AudioPlaybackCapture, termasuk mode internal + mikrofon |
| Mikrofon / tanpa suara | Jalur MediaRecorder native yang stabil untuk mode standar |
| Audio Lab | Efek suara podcast yang sepenuhnya opsional: reduksi noise sistem, noise gate, kompresor, dan penguat mikrofon. Semua mati secara default |
| Auto Subtitle | Membuat subtitle SRT bahasa Indonesia dari suara mikrofon setelah perekaman selesai, sepenuhnya offline dengan model Whisper yang dibundel di APK |
| Jeda dan lanjut | Termasuk kontrol dari notifikasi |
| Bola melayang | Timer dan panel kontrol untuk stop, jeda, facecam, sensor, dan gambar |
| Screenshot | Sekali ketuk, tersimpan di Pictures/RekaminAja |
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

Semua efek **mati secara default**. Selama tidak dinyalakan, RekaminAja memakai jalur
mikrofon standar apa adanya. Saat efek menyala, mikrofon melewati jalur codec agar
suaranya bisa diproses; bila jalur codec tidak tersedia di perangkat, aplikasi
otomatis kembali ke jalur standar.

## Auto Subtitle

Auto Subtitle membuat file subtitle `.srt` ber-timestamp dari suara manusia setelah
rekaman selesai. Buka tombol **Auto Subtitle** di player, atau tekan lama video di
galeri. Hasilnya dapat ditampilkan sebagai teks saat video diputar, dibagikan, atau
dibuat ulang. Salinan publik SRT juga diterbitkan ke folder RekaminAja bila perangkat
mengizinkan.

Fitur ini memang berjalan **setelah perekaman**, bukan saat merekam, agar perekaman
tetap ringan dan mulus. Transkripsi memakai Whisper multilingual yang berjalan lokal
dengan model terkuantisasi yang dibundel di edisi Full ARM64 dan armeabi-v7a. Tidak
ada audio yang diunggah dan aplikasi tetap tidak memiliki izin INTERNET.

Auto Subtitle ditujukan untuk rekaman mikrofon atau internal + mikrofon yang berisi
suara manusia. Rekaman yang diketahui hanya memakai audio internal atau tanpa suara
tidak perlu dibuatkan subtitle. Untuk video lama yang sumber audionya tidak diketahui,
aplikasi memeriksa keberadaan suara sebelum memprosesnya. Perangkat ARM 32-bit lama
akan memproses lebih lambat; ketepatan hasil bergantung pada kejernihan suara dan
kebisingan latar.

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
- Dependency utama: AndroidX Core, AppCompat, Material, RecyclerView, Kotlin Coroutines,
  dan modul Whisper Android lokal untuk transkripsi ARM64/armeabi-v7a
- `ScreenRecordService` (foreground service `mediaProjection|microphone|camera`)
  mengikuti aturan Android 14+: FGS dimulai dulu, lalu `registerCallback()`,
  lalu `createVirtualDisplay()`
- Dua engine perekaman: `MediaRecorderEngine` untuk jalur standar, dan
  `CodecRecorderEngine` (MediaCodec H.264 + AAC + MediaMuxer) untuk audio internal,
  mode campuran, dan pemrosesan mikrofon Audio Lab
- Pemrosesan PCM Audio Lab ditulis khusus di `MicDsp` (high-pass, gate, kompresor, limiter)
- Auto Subtitle mengekstrak audio menjadi potongan WAV mono 16 kHz, mentranskripsinya
  per potongan dengan Whisper Base Q5_1, lalu menyusun cue SRT yang ringkas
- Model subtitle diunduh Gradle hanya pada mesin build/CI dari revisi terpaku dan
  diverifikasi SHA-256 sebelum dibundel ke edisi Full; model biner tidak disimpan di Git
- Edisi Lite memakai stub subtitle tanpa dependency native/model Whisper agar tetap
  kecil dan kompatibel universal
- Seluruh komponen UI digambar sendiri: tombol rekam, toggle, slider, pilihan, dialog,
  kontrol player, dan panel overlay
- Release: R8 minify dan shrink resources

## Build dari source

Butuh JDK 17, Android SDK API 37, Build-Tools 37.0.0, NDK 27.2.12479018,
dan CMake 3.22.1 untuk native Whisper.

```bash
git clone --recurse-submodules https://github.com/xykal/RekaminAja.git
cd RekaminAja
./gradlew assembleDebug     # APK debug
./gradlew assembleRelease   # APK release (butuh signing, lihat docs/BUILD_RELEASE.md)
```

Build dan rilis resmi dibuat oleh GitHub Actions: push tag `v*` menghasilkan APK, AAB,
dan `SHA256SUMS.txt` sebagai GitHub Release. Panduan signing dan publikasi, termasuk
APKPure, ada di [docs/BUILD_RELEASE.md](docs/BUILD_RELEASE.md).

## Roadmap

- Editor lanjutan (teks dan stiker)
- Rekaman terjadwal dan batas durasi
- Pilihan encoder HEVC untuk ukuran file lebih kecil
- Deteksi teks sensitif otomatis (OCR on-device) untuk sensor pintar
- Publikasi APKPure, lalu Google Play

## Kontribusi

Issue dan pull request dipersilakan. Untuk perubahan besar, buka issue terlebih dahulu
agar arahnya selaras.

## Lisensi dan privasi

[MIT](LICENSE) · [Privacy Policy](PRIVACY_POLICY.md) · [Third-Party Notices](THIRD_PARTY_NOTICES.md)
— tidak ada data yang dikumpulkan.

---

### English (short)

**RekaminAja** is a free and open-source Android screen recorder written in native
Kotlin: HD recording up to 60 FPS, internal audio on Android 10+, internal + mic mix,
an opt-in Audio Lab that makes a phone microphone sound closer to a podcast mic,
offline Indonesian auto subtitles generated as timestamped SRT files after recording,
floating ball, facecam, draw-on-screen, one-tap screenshots, a built-in gallery with
lossless trimming, and a Quick Settings tile — plus a Privacy Shield with permanent
censor zones and auto-pause for your chosen sensitive apps. The interface is a fully
custom Light Clean design. No watermark, no ads, no tracking, and no INTERNET
permission at all. Android 7.0+ (API 24), built with AGP 9.4.1 and Gradle 9.6 against
SDK 37. Download the APK from
[Releases](https://github.com/xykal/RekaminAja/releases).
