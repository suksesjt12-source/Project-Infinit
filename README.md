# Project Infinity

PROJECT INFINITY connects two Android phones over the same local Wi-Fi or hotspot.

## Current features

- QR pairing with a short-lived session token.
- Local TCP messaging with challenge-response authentication and AES-256-GCM.
- File transfer with AES-GCM protection, SHA-256 verification and an 8 MB file limit.
- One-way screen sharing from Client to Master. Android MediaProjection permission and a persistent notification are required.
- One-way camera sharing from Client to Master. Runtime camera permission and a persistent foreground-service notification are required.
- Screen and camera frames are encrypted with AES-256-GCM.
- Automatic LAN reconnect for the main messaging connection.
- Device status dashboard, in-app touchpad/gamepad event relay and text relay.

## Camera streaming setup

1. Install the same APK on both Android phones.
2. Connect both phones to the same Wi-Fi network or hotspot.
3. On the first phone, choose **Master** and select **Host / Buat QR**.
4. On the second phone, choose **Client**, scan the Master QR and press **Connect**.
5. Open **Tools** on the Client and select **Mulai camera stream**.
6. Grant the Android camera permission. The camera runs only while the foreground notification is visible; press **Hentikan camera stream** to stop it.
7. The Master receives the camera preview in the Camera Stream panel.

Camera frames are limited to approximately 640×480 at 5 FPS. Actual size and rate depend on the camera hardware and local Wi-Fi conditions.

## Important limitations

- Camera and screen streams are one-way: Client to Master.
- Camera and screen sharing require explicit user action and Android permission.
- Audio streaming is not included yet.
- Touchpad, gamepad and keyboard relays send events only inside PROJECT INFINITY; they do not inject input into unrelated Android apps or games.
- Automatic reconnect covers the main LAN messaging connection. Screen/camera streaming must be started again after a reconnect.
- Use a private network you trust. Pairing protects connections but does not make an untrusted Wi-Fi network safe.
- Two-device hardware testing is still required for camera compatibility, performance, temperature and reconnect behavior.

## Build

The repository includes a GitHub Actions workflow that builds a debug APK on pushes to `main` and on manual workflow dispatch.

Open the **Actions** tab and download the `Project-Infinity-debug` artifact from the latest successful run.
