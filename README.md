# Old Phone CCTV

Satu APK, dua mod:

- **Streamer** = telefon lama (OPPO A37fw, Android 5.1.1) yang menghantar video kamera.
- **Viewer** = telefon utama yang menonton live feed.

Boleh juga tonton terus dalam browser (Chrome dsb.) pada alamat `http://IP-telefon-lama:8080`.

## Keserasian Android 5.1.1 (API 22)

- `minSdk 22`. Tiada AndroidX, tiada library luar (dependency kosong).
- Kamera guna **Camera API 1** (`android.hardware.Camera`), bukan Camera2.
- Streaming **MJPEG melalui HTTP**, pelayan ditulis sendiri (ringan, RAM rendah).
- Semua API Android 6+ (kebenaran runtime, dsb.) dilindungi dengan semakan `SDK_INT`.
- `targetSdk 34` supaya APK boleh dipasang pada telefon utama yang baharu.

## Cara bina APK (GitHub Actions)

1. Cipta repo baharu di GitHub (cth. `old-phone-cctv`).
2. Muat naik **semua fail dalam folder ini** (termasuk folder `.github` dan `app/debug.keystore`).
3. Buka tab **Actions** -> workflow **Build APK** akan jalan sendiri (3-6 minit).
   Kalau tak jalan: Actions -> Build APK -> **Run workflow**.
4. Bila siap, buka run itu -> bahagian **Artifacts** -> muat turun `OldPhoneCCTV-apk` (zip, dalamnya `app-release.apk`).

APK ditandatangani dengan kunci tetap (`app/debug.keystore`), jadi versi baharu boleh dipasang atas yang lama tanpa uninstall.

## Cara guna

1. Pasang APK pada **kedua-dua** telefon (benarkan "Install unknown apps").
2. **Telefon lama**: buka app -> pilih **STREAMER** -> benarkan kamera. Streaming mula sendiri.
   Skrin tunjuk alamat (`http://192.168.x.x:8080`), pengguna `admin` dan kata laluan rawak.
3. **Telefon utama**: buka app -> pilih **VIEWER** -> tekan **Imbas rangkaian** (atau taip IP) -> masukkan kata laluan -> **SAMBUNG**.
4. Dalam paparan live ada kawalan: kamera depan/belakang, resolusi, FPS, kualiti, flash, pusing, cermin, gambar.

## Supaya sentiasa hidup (telefon lama)

- Biarkan **dicas** sepanjang masa.
- Dalam app, "Mula automatik" dihidupkan (default) -> servis bermula selepas telefon restart.
- Pada ColorOS/OPPO: Tetapan -> Pengurus Keselamatan/Privasi -> kebenaran **Auto-start** untuk Old Phone CCTV, dan matikan penjimatan bateri untuknya. Kunci app dalam senarai Recent (tahan kad app -> Lock) jika ada pilihan itu.
- Kalau gambar terbalik/senget: tekan **Pusing** dalam halaman live.

## Keselamatan

- Pelayan hanya untuk rangkaian Wi-Fi rumah. **Jangan** buka port di router (port forwarding).
- Semua laluan perlukan login (Basic auth). Kata laluan dijana rawak; boleh tukar dalam app.
- Sambungan ialah HTTP biasa (tidak disulitkan) -> sesuai untuk LAN peribadi sahaja.
- Akses dari luar rumah: Tailscale memerlukan Android 8.0+, jadi **tidak boleh dipasang pada OPPO A37fw (5.1.1)**.
  Tanpa hardware tambahan, kekal LAN sahaja.
