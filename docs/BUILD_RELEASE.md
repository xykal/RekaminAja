# Build, Signing & Rilis — RekaminAja

Dokumen ini untuk maintainer. Rilisan resmi dibangun **GitHub Actions** — komputer lokal
hanya untuk pengembangan/debug.

## 1. Alur rilis

```bash
git tag v1.0.0
git push origin v1.0.0
```

Workflow [`Release`](../.github/workflows/release.yml) akan:
1. Setup JDK 17 + Android SDK API 37
2. Membaca keystore dari GitHub Secrets (jika ada)
3. Build `:app:assembleRelease` + `:app:bundleRelease`
4. Menerbitkan **GitHub Release** berisi `RekaminAja-vX.Y.Z.apk`, `.aab`, dan `SHA256SUMS.txt`

Push biasa ke `main` menjalankan workflow [`CI`](../.github/workflows/ci.yml)
(debug + release sebagai artifact).

## 2. Keystore produksi (WAJIB sebelum distribusi publik)

> ⚠️ **Sekali aplikasi terinstall user dengan satu keystore, semua update selamanya
> harus memakai keystore yang sama.** Hilang keystore = tidak bisa update aplikasi.
> Simpan backup terenkripsi di minimal 2 tempat terpisah.

Buat keystore (sekali saja, di mesin lokal yang aman):

```bash
keytool -genkeypair -v \
  -keystore rekaminaja-release.jks \
  -alias rekaminaja \
  -keyalg RSA -keysize 2048 -validity 10000
```

Pasang 4 GitHub Secrets (repo → Settings → Secrets and variables → Actions):

| Secret | Isi |
|---|---|
| `KEYSTORE_BASE64` | Output dari `base64 -w0 rekaminaja-release.jks` |
| `KEYSTORE_PASSWORD` | Password keystore |
| `KEY_ALIAS` | `rekaminaja` |
| `KEY_PASSWORD` | Password key |

Workflow mengubah secret itu menjadi `keystore.properties` sementara;
`app/build.gradle.kts` membacanya secara otomatis.

**Tanpa secret**, build release ditandatangani **debug key** (fallback untuk CI hijau) —
jangan distribusikan APK debug-signed ke publik/store.

**Jangan pernah commit** file `.jks/.keystore/.p12`, `keystore.properties`, atau file env —
sudah dicakup `.gitignore`.

Build lokal release (opsional):

```bash
cp keystore.properties.example keystore.properties   # isi path & password
./gradlew assembleRelease bundleRelease
```

## 3. Checklist publikasi APKPure

APKPure menerima upload APK langsung (gratis) via Developer Console — cocok untuk
rilisan awal sebelum Google Play ($25 sekali bayar).

- [ ] Keystore produksi sudah dipasang sebagai GitHub Secrets (lihat §2)
- [ ] `versionCode` naik & `versionName` benar di `app/build.gradle.kts`
- [ ] CHANGELOG terisi
- [ ] Tag `vX.Y.Z` dipush → GitHub Release hijau → unduh APK dari Release
- [ ] Uji APK Release di perangkat beneran:
  - [ ] Rekam mode mic / internal / internal+mic / senyap
  - [ ] Pause/resume, rotasi layar saat merekam, rekaman > 10 menit
  - [ ] Bola melayang, facecam, zona sensor, auto-pause app sensitif
  - [ ] Screenshot, galeri, trim, share
  - [ ] Android 14+ (dialog "satu app vs seluruh layar") & Android lama (7–9) bila ada
- [ ] Siapkan aset listing:
  - Ikon 512×512 PNG (ekspor dari `ic_launcher_foreground` + latar `#B3261E`)
  - Screenshot minimal 3 (portrait) — rekam/test dengan RekaminAja sendiri 😉
  - Deskripsi singkat & lengkap (ambil dari README), kategori: Video Players & Editors / Tools
  - URL Privacy Policy: pakai tautan `PRIVACY_POLICY.md` di repo GitHub
- [ ] Upload APK Release yang sama persis dari GitHub Release (jangan rebuild lokal)

## 4. Naik versi

1. Naikkan `versionCode` (+1) & `versionName` di `app/build.gradle.kts`
2. Tambah entri `CHANGELOG.md`
3. Commit → tag `vX.Y.Z` → push tag

## 5. Catatan target Google Play (nanti)

- Play mewajibkan `targetSdk 36` (Android 16) per 31 Agustus 2026 — sudah dipenuhi.
- Upload format **AAB** (sudah dihasilkan workflow).
- Deklarasi Data Safety: "No data collected/shared" (aplikasi tanpa izin INTERNET).
- Izin sensitif yang perlu dijelaskan di Play Console: `SYSTEM_ALERT_WINDOW`,
  `PACKAGE_USAGE_STATS` (opsional), foreground service `mediaProjection`.
