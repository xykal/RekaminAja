# Changelog

## 1.2.1 — 2026-10-25

### Baru
- **Bola melayang muncul otomatis** setiap kali aplikasi dibuka (bila pengaturan bola aktif dan izin overlay sudah diberikan), tanpa perlu mengetuk tile Bola terlebih dahulu
- **Izin tangkapan layar cukup sekali per sesi**: selama bola melayang tampil, sesi MediaProjection disimpan sehingga rekaman berikutnya mulai seketika tanpa dialog izin Android lagi; notifikasi berubah menjadi mode "Siap merekam" dengan tombol Mulai dan Akhiri sesi; sesi berakhir saat bola disembunyikan atau izin dicabut dari sistem

### Perbaikan
- Memulai rekaman dari bola melayang **tidak lagi membuka tampilan aplikasi**: halaman izin kini berjalan di task terpisah dan transparan, sehingga dialog izin tampil di atas aplikasi yang sedang digunakan dan fokus kembali ke sana setelahnya
- Bola melayang dikecilkan lagi dari 46dp menjadi **38dp**

### Catatan
- Build release tetap memakai **R8** (`minifyEnabled`) + shrink resources seperti versi-versi sebelumnya

## 1.2.0 — 2026-10-25

### Baru
- **Deck Efek Suara di panel melayang**: preset karakter suara mic yang bisa diganti langsung saat merekam — Normal, Super Bersih, Ngebas Podcast, Echo, Bergema, Alien, Robot, dan Cempreng — lengkap dengan slider intensitas
- **Atur volume di mana saja**: volume mic (10-200%) dan volume HP bisa diatur dari panel melayang; volume mic juga bisa dinaik-turunkan dari tombol -/+ pada notifikasi perekaman yang diperluas
- **Logo aplikasi baru**: gradasi coral ke violet dengan bingkai viewfinder dan titik rekam, termasuk ikon adaptif dan ikon tematik (monochrome)

### Perbaikan
- **Bola melayang ditulis ulang**: kini 46dp, selalu menempel di tepi kiri/kanan dengan animasi snap, posisinya diingat antar sesi, meredup saat diam, dan bisa dihilangkan dengan menyeretnya ke lingkaran hapus di bagian bawah-tengah layar seperti XRecorder; panelnya kini berupa deck dengan tombol aksi bulat
- **Notifikasi perekaman custom**: kartu Light Clean sendiri berisi status, timer, tombol jeda/stop, dan pengatur volume mic
- **Auto Subtitle jauh lebih ringan**: model diganti Whisper Tiny Q5_1 (APK Full turun ke sekitar 33MB dan transkripsi jauh lebih cepat), potongan audio dipangkas menjadi maks 30 detik dengan progres per potongan dan timer berjalan, file potongan langsung dihapus setelah ditranskrip, serta salinan model berpindah ke cache agar data aplikasi tidak membengkak
- **Tidak ada lagi garis hitam di bagian atas saat merekam**: zona sensor bawaan lama di area status dihapus; zona sensor kini kosong sampai pengguna membuatnya sendiri
- Mesin DSP mic diperbarui: rantai volume, high-pass, noise gate adaptif, EQ (low-shelf ngebas dan presence), kompresor, efek karakter (delay, reverb Schroeder, ring modulator, pitch shifter), dan soft limiter

## 1.1.1 — 2026-10-25

### Baru
- **APK Full khusus `armeabi-v7a`**: perangkat ARM 32-bit kini mendapat edisi Full sendiri yang setara dengan `arm64-v8a`, termasuk Auto Subtitle offline
- Library Whisper kini dibuild lokal dari source MIT Whisper Android 1.0.0 dan whisper.cpp v1.7.5 agar native library tersedia untuk `arm64-v8a` dan `armeabi-v7a`
- Rilis kini menyediakan Full ARM64, Full armeabi-v7a, dan Lite Universal; Lite tetap menjadi pilihan kecil tanpa model subtitle untuk x86 atau pengguna yang tidak memerlukan Auto Subtitle

### Catatan
- Transkripsi pada perangkat ARM 32-bit lama akan lebih lambat dan bergantung pada RAM/CPU perangkat, tetapi perekaman tetap tidak dibebani karena subtitle dibuat setelah rekaman selesai

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
- **Auto Subtitle offline bahasa Indonesia**: membuat subtitle SRT ber-timestamp dari suara mikrofon setelah perekaman selesai, tanpa membebani proses perekaman
  - Model Whisper Base Q5_1 dibundel di APK/AAB edisi Full dan transkripsi berjalan sepenuhnya di perangkat; tidak ada audio yang diunggah
  - Subtitle dapat ditampilkan di player, disembunyikan, dibagikan sebagai file SRT, atau dibuat ulang
  - Akses cepat tersedia dari player dan menu tekan-lama di galeri
  - Ditujukan untuk rekaman mikrofon/internal + mikrofon berisi suara manusia; rekaman audio internal murni dan tanpa suara dilewati
  - Memerlukan prosesor 64-bit ARM dan tersedia di APK Full ARM64; APK Lite Universal tetap kecil dan dapat dipasang di perangkat 32-bit/x86, tetapi tanpa Auto Subtitle

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
