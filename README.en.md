# HandLink

<p align="center">
  <img src="logo.svg" width="128" height="128" alt="HandLink" />
</p>

<h3 align="center">Two phones, one screen</h3>

<p align="center">
  See what each other is doing, no matter how far apart.
</p>

---

## What is this

A remote screen sharing app built for two people.

**It does three things:**

1. **Let them see my screen** — flip a switch, they see your screen in real time
2. **See their screen** — tap a device, their phone appears on yours
3. **Control it directly** — not just watch, but tap, swipe, and type

## Features

| | |
|---|---|
| **Low latency** | scrcpy core + WebRTC, ~100ms |
| **HD quality** | up to 1920p / 60fps / 20Mbps, adaptive bitrate |
| **Works across networks** | NAT traversal and relay for long distance |
| **No root required** | system screen capture permission is enough |
| **Optional full control** | install Shizuku for complete remote operation |

## Usage

### Install on both phones

Download the APK and install on each.

### One phone watches

1. Open the app, enter the server address
2. Log in
3. Tap the device in the list
4. Watch and interact

### One phone shares

1. Open the app
2. Go to **Me** → **Let them see my screen**
3. Tap **Let them connect**
4. Accept the system screen capture prompt → **Start now**

### Want full control? (optional)

By default it uses system screen capture (view-only, limited input).

For full control, the shared phone needs **Shizuku**:

1. Install Shizuku
2. Enable Wireless debugging in Developer options
3. Tap **Pair** in Shizuku and enter the 6-digit code
4. In HandLink → **Me** → **Let them see my screen** → engine: **Shizuku**
5. Tap **Request Shizuku permission** → Allow

> Note: Shizuku must be re-activated after each reboot (~30 seconds)

## Server

Self-hosted signaling + TURN relay:

```
Signaling: http://your-server:8443
```

Uses the [HandLink](https://github.com/hhoneypot0-ui/HandLink) Docker image:

```bash
docker run -d --name cp-aio --network host --restart unless-stopped \
  -e PUBLIC_IP=your_public_ip \
  -e USE_TLS=false \
  -e SIGNALING_PORT=8443 \
  -e TURN_USER=user \
  -e TURN_PASSWORD=pass \
  buutuu/scrcpy-over-webrtc:latest
```

Firewall rules required:

| Protocol | Port | Purpose |
|---|---|---|
| TCP | 8443 | Signaling |
| UDP | 3478 | TURN relay |

## Build

```bash
# Requires JDK 17 and Android SDK 35
./gradlew :app:assembleDebug
```

Output in `app/build/outputs/apk/debug/`

## Tech stack

- Kotlin 2.0.21 + Jetpack Compose
- WebRTC (H.264 hardware codec)
- scrcpy core
- OkHttp + WebSocket

## Credits

Based on [WebrtcPhone-Android](https://github.com/hqw700/WebrtcPhone-Android).

## License

Apache License 2.0

---

<p align="center">
  <sub>Made for us</sub>
</p>