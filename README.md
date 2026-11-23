# Old Phone CCTV

One APK, two modes:

- **Streamer** = the old phone (OPPO A37fw, Android 5.1.1) that sends the camera video.
- **Viewer** = your main phone that watches the live feed and can talk through the old phone.

You can also watch from any browser at `http://<old-phone-ip>:8080` (video and controls only; push-to-talk needs the Viewer app).

## Android 5.1.1 (API 22) compatibility

- `minSdk 22`. No AndroidX, no external libraries (empty dependency list).
- Camera uses **Camera API 1** (`android.hardware.Camera`), not Camera2.
- **MJPEG over HTTP** with a tiny built-in server (light on RAM).
- Anything newer than Android 5.1 (runtime permissions, battery-optimization prompt) is guarded by `SDK_INT` checks.
- `targetSdk 34` so the APK installs on modern main phones.

## Build the APK (GitHub Actions)

1. Create a repo on GitHub and upload **everything in this folder** (including `.github` and `app/debug.keystore`).
2. Open the **Actions** tab. **Build APK** runs automatically (3-6 min). If not: Actions -> Build APK -> **Run workflow**.
3. When it is green, open the run -> **Artifacts** -> download `OldPhoneCCTV-apk` (zip containing `app-release.apk`).

The APK is signed with a fixed key (`app/debug.keystore`), so new versions install over old ones without uninstalling.

## How to use

1. Install the APK on **both** phones (allow "Install unknown apps").
2. **Old phone**: open the app -> **STREAMER** -> allow the camera. It shows the address (`http://192.168.x.x:8080`), user `admin` and a random password.
3. **Main phone**: open the app -> **VIEWER** -> **Scan network** (or type the IP) -> enter the password -> **CONNECT**.
4. Live view controls: front/rear camera, resolution, FPS, quality, flash, rotate, mirror, snapshot.
5. **Talk**: press and **hold** the **HOLD TO TALK** button (top-left of the live view) and speak. Your voice plays from the old phone's speaker. Release to stop. The first time, allow the microphone permission on the main phone.

The old phone's media volume is raised to maximum while you talk and restored afterwards.

## Battery saver (on by default)

- The service and HTTP server always run (almost no battery use).
- The **camera, CPU lock and high-performance Wi-Fi only run while someone is watching** (or the app is open) and stop ~20 seconds after the last viewer leaves.
- Turn it off in the Streamer screen if you want the camera on all the time.

## Keep it alive

- Keep the old phone **charging**.
- The streamer restarts itself after a reboot ("Start automatically" is on by default) and after you swipe the app away from Recents.
- On ColorOS/OPPO: Settings -> Security/Privacy -> allow **Auto-start** for Old Phone CCTV, and disable battery optimization for it. Lock the app in Recents if the option exists.
- Image sideways or upside down? Tap **Rotate** on the live page.

## Security

- LAN only. **Do not** port-forward the server on your router.
- Every route requires a login (Basic auth). The password is random and can be changed in the app.
- Traffic is plain HTTP (not encrypted), fine for a private home network.
- Remote access: Tailscale needs Android 8.0+, so it **cannot run on the OPPO A37fw (5.1.1)**. Without extra hardware, stay on LAN only.
