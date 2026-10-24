# Changelog

## 1.1.0 — 2026-10-24

### Baru
- **Antarmuka Light Clean yang sepenuhnya custom**: seluruh layar digambar ulang dengan komponen buatan sendiri — tombol rekam beranimasi, toggle, slider, pilihan, top bar, dialog, kontrol player, panel bola melayang, dan toolbar gambar. Tidak ada lagi komponen yang terlihat seperti bawaan Android, dan Material You dynamic color dimatikan agar tampilan konsisten
- Ikon vektor baru yang digambar khusus untuk seluruh aplikasi, termasuk ikon aksi notifikasi
- **Audio Lab**: pemrosesan mikrofon bergaya podcast yang sepenuhnya opsional dan mati secara default
  - Reduksi noise sistem (noise suppressor, penghilang gema, penguat otomatis perangkat)
  - Noise gate adaptif untuk memotong desis latar saat tidak berbicara
  - Kompresor podcast dengan high-pass 80 Hz, makeup gain, dan soft limiter
  - Penguat mikrofon 0-12 dB
  - Preset Off, Natural, Podcast, dan Studio
- Engine perekaman codec digeneralisasi menjadi `CodecRecorderEngine` untuk audio internal, mode internal + mikrofon, dan mode mikrofon dengan efek; jalur standar otomatis dipakai sebagai cadangan bila efek tidak tersedia

### Diubah
- Layar utama baru: kartu perekam dengan timer besar, akses cepat mikrofon dan resolusi, ringkasan pengaturan, peringatan izin, enam alat, dan media terbaru
- Pengaturan ditulis ulang sebagai kartu-kartu custom per bagian
- Galeri, player, trim, daftar aplikasi sensitif, dan editor Zona Sensor memakai desain baru yang sama
- Dokumentasi dibersihkan dan ditulis dengan gaya yang lebih rapi

## 1.0.0 — 2026-10-24

Rilisan perdana RekaminAja.

### Fitur
- Rekam layar native (MediaProjection), resolusi native/1080p/720p/540p/480p, 15-60 FPS, bitrate otomatis/manual sampai 24 Mbps
- Audio: tanpa suara, mikrofon, audio internal (Android 10+), dan internal + mic (mix PCM)
- Jeda/lanjut, hitung mundur 0/3/5/10 detik, kontrol dari notifikasi
- Bola melayang dengan timer dan panel kontrol
- Screenshot sekali ketuk
- Facecam bundar (Camera2, bisa diseret, tekan-tahan untuk ganti kamera)
- Gambar/coret di layar (kuas overlay)
- Privacy Shield: Zona Sensor (kotak hitam yang benar-benar tersensor permanen di video)
- Auto-pause Aplikasi Sensitif: rekaman berhenti sementara dan layar tertutup saat aplikasi bank/e-wallet/chat pilihanmu tampil
- Galeri bawaan, player, share/hapus, trim video tanpa re-encode (tanpa FFmpeg)
- Quick Settings Tile dan shortcut launcher
- Bahasa Inggris dan Indonesia
- Tanpa izin internet, tanpa iklan, tanpa pelacak, tanpa watermark

### Teknis
- minSdk 24 (Android 7.0), targetSdk 36 (Android 16), compileSdk 37 (Android 17)
- AGP 9.4.1 + Built-in Kotlin, Gradle 9.6, JDK 17
- Release di-minify R8 + shrink resources
