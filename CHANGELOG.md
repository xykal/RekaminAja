# Changelog

## 1.0.0 — 2026-10-24

Rilisan perdana RekaminAja 🎉

### Fitur
- Rekam layar native (MediaProjection), resolusi native/1080p/720p/540p/480p, 15–60 FPS, bitrate otomatis/manual sampai 24 Mbps
- Audio: tanpa suara, mikrofon, **audio internal** (Android 10+), dan **internal + mic** (mix PCM)
- Jeda/lanjut, hitung mundur 0/3/5/10 detik, kontrol dari notifikasi
- Bola melayang dengan timer & panel kontrol
- Screenshot sekali ketuk
- Facecam bundar (Camera2, bisa diseret, tekan-tahan untuk ganti kamera)
- Gambar/coret di layar (kuas overlay)
- **Privacy Shield**: Zona Sensor (kotak hitam yang benar-benar tersensor permanen di video)
- **Auto-pause Aplikasi Sensitif**: rekaman berhenti sementara + layar tertutup saat app bank/e-wallet/chat pilihanmu tampil
- Galeri bawaan, player, share/hapus, **trim video** tanpa re-encode (tanpa FFmpeg)
- Quick Settings Tile + shortcut launcher
- Material 3 + Material You dynamic color, bahasa Inggris & Indonesia
- Tanpa izin internet, tanpa iklan, tanpa pelacak, tanpa watermark

### Teknis
- minSdk 24 (Android 7.0) · targetSdk 36 (Android 16) · compileSdk 37 (Android 17)
- AGP 9.4.1 + Built-in Kotlin, Gradle 9.6, JDK 17
- Release di-minify R8 + shrink resources
