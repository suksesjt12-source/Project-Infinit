# Project Infinity

PROJECT INFINITY connects two Android phones over the same local Wi-Fi or hotspot.

## Current features

- QR pairing with a short-lived session token.
- Local TCP messaging with challenge-response authentication and AES-256-GCM.
- File transfer with AES-GCM protection, SHA-256 verification and an 8 MB file limit.
- One-way screen sharing from Client to Master. Android MediaProjection permission and a persistent notification are required.
- One-way camera sharing from Client to Master. Runtime camera permission and a persistent foreground-service notification are required.
- One-way microphone audio from Client to Master. Runtime microphone permission and a persistent foreground-service notification are required.
- Screen, camera and audio streams use HMAC challenge-response and AES-256-GCM protected frames.
- Automatic LAN reconnect for the main messaging connection.
- Local system monitor and automatic paired-device status sync (model, Android version, battery, available/total RAM and free storage).
- Encrypted LAN round-trip latency (RTT) diagnostics, updated every five seconds while connected.
- In-app touchpad/gamepad event relay and text relay.

## Pairing

1. Install the same APK on both Android phones.
2. Connect both phones to the same Wi-Fi network or hotspot.
3. On the first phone, choose **Master** and select **Host / Buat QR**.
4. On the second phone, choose **Client**, scan the Master QR and press **Connect**.
5. Open **Tools** on either device to access the sharing panels.

## Link diagnostics

While connected, both devices exchange encrypted ping/pong messages every five seconds. The dashboard displays measured round-trip latency (RTT) in milliseconds. This measures app-to-app LAN response time, not internet speed.

## Paired-device status sync

When the two phones are connected, each device sends a small system-status snapshot to the other every five seconds. The Tools tab shows the paired phone's model, Android version, battery percentage, available and total RAM, and free storage. Status is read-only and is cleared from the dashboard when the connection is lost.

## Camera streaming

1. On the Client, open **Tools** and select **Mulai camera stream**.
2. Grant the Android camera permission when asked.
3. The camera runs only while the persistent foreground notification is visible.
4. The Master receives the camera preview in the Camera Stream panel.
5. Press **Hentikan camera stream** to stop capture.

Camera frames are limited to approximately 640×480 at 5 FPS. Actual size and rate depend on the camera hardware and local Wi-Fi conditions.

## Audio streaming

1. Keep the Client app visible and connected to the Master.
2. On the Client, open **Tools** and select **Mulai audio stream**.
3. Grant the Android microphone permission when asked.
4. The Client microphone audio is transmitted to the Master, which plays it through its audio output.
5. A persistent notification remains visible while the microphone is active. Press **Hentikan audio stream** to stop.

Audio uses mono PCM at 16 kHz in short frames to limit bandwidth. This feature shares the Client microphone only; it does not capture internal audio from other apps. The stream is one-way, Client to Master.

## Important limitations

- Screen, camera and audio streams are one-way: Client to Master.
- Camera and microphone sharing require explicit user action and Android runtime permission.
- Android restricts starting camera and microphone foreground services from the background. Start sharing from the visible app screen.
- Audio playback and camera performance depend on device hardware, Wi-Fi quality and current system load.
- Automatic reconnect covers the main LAN messaging connection. Paired-device status sync resumes automatically after reconnect. Screen, camera and audio streams must be started again after a reconnect.
- Touchpad, gamepad and keyboard relays send events only inside PROJECT INFINITY; they do not inject input into unrelated Android apps or games.
- Use a private network you trust. Pairing protects the connection but does not make an untrusted Wi-Fi network safe.
- Two-device hardware testing is still required for audio quality, microphone compatibility, camera performance, temperature and reconnect behavior.

## Build

The repository includes a GitHub Actions workflow that builds a debug APK on pushes to `main` and on manual workflow dispatch.

Open the **Actions** tab and download the `Project-Infinity-debug` artifact from the latest successful run.
