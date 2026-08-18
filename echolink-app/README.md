# MoonTone

**Wireless Windows system audio → Android phone**

MoonTone captures your PC's system audio and streams it to your Android phone over WiFi with ultra-low latency. Built on the battle-tested [Moonlight](https://github.com/moonlight-stream)/[Sunshine](https://github.com/LizardByte/Sunshine) audio pipeline.

## Architecture

```
Windows PC (Sunshine)              Android Phone (MoonTone)
─────────────────────              ───────────────────────
WASAPI Loopback Capture            Compose UI (Salt UI style)
    │                                  │
Opus Multistream Encoder           EchoConnectionManager
    │                                  │
RTP/UDP :48000 ──── WiFi ────→    moonlight-common-c (C)
(Opus audio packets)               │ Opus Decode → PCM
                                   │ ├─ AudioTrack playback
                                   │ └─ FFT visualizer (WIP)
                                   └─ MediaSession notification
```

## Prerequisites

### On Windows
- [Sunshine](https://github.com/LizardByte/Sunshine/releases) installed and running
- Sunshine configured to capture system audio (WASAPI)

### For Development
- Android Studio Hedgehog (2023.1) or later
- Android SDK 34
- NDK 27.0.12077973
- JDK 17

## Build

```bash
# Generate Gradle wrapper (if not present)
gradle wrapper --gradle-version 8.7

# Build APK
./gradlew assembleDebug

# Output: app/build/outputs/apk/debug/app-debug.apk
```

## Usage

1. Install Sunshine on your Windows PC
2. Start Sunshine and configure audio settings in its web UI (http://localhost:47990)
3. Build and install EchoLink APK on your Android phone
4. Open EchoLink, enter your PC's IP address, tap "Connect"
5. PC system audio will stream to your phone

## Project Structure

```
echolink-app/
├── app/
│   ├── src/main/
│   │   ├── java/com/
│   │   │   ├── halo/moontone/           # Our code
│   │   │   │   ├── MoonToneApp.kt         # Application
│   │   │   │   ├── audio/
│   │   │   │   │   └── MoonToneAudioService.kt # Foreground service
│   │   │   │   ├── connection/
│   │   │   │   │   ├── MoonToneConnection.kt    # MoonBridge wrapper
│   │   │   │   │   └── SunshineClient.kt        # Pairing/launch HTTP
│   │   │   │   └── ui/
│   │   │   │       └── MainActivity.kt          # Compose UI
│   │   │   └── limelight/               # Adapted Moonlight code
│   │   │       ├── LimeLog.java
│   │   │       ├── binding/audio/
│   │   │       │   └── AndroidAudioRenderer.java  # AudioTrack playback
│   │   │       └── nvstream/
│   │   │           ├── NvConnectionListener.java
│   │   │           ├── av/audio/AudioRenderer.java
│   │   │           ├── av/video/VideoDecoderRenderer.java
│   │   │           └── jni/MoonBridge.java
│   │   ├── jni/                         # Native C layer
│   │   │   └── moonlight-core/
│   │   │       ├── callbacks.c          # JNI callbacks (audio + video)
│   │   │       ├── moonlight-common-c/  # RTSP/RTP/Opus core
│   │   │       ├── libopus/             # Opus codec (prebuilt .a)
│   │   │       └── openssl/             # OpenSSL (prebuilt .a)
│   │   ├── res/
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

## Technical Notes

### Audio Pipeline
- **Server**: Sunshine captures WASAPI loopback → Opus 48kHz encode → RTP/UDP
- **Client**: moonlight-common-c receives RTP → FEC recovery → Opus decode → PCM
- **Playback**: Android `AudioTrack` with `PERFORMANCE_MODE_LOW_LATENCY`
- **Latency**: ~10ms Opus encode + ~10ms network + ~10ms AudioTrack buffer

### Why 1x1 Video?
The Moonlight protocol requires both video and audio streams. We request 1x1 video resolution to minimize encoding overhead while the protocol negotiates happily. The dummy `VideoDecoderRenderer` discards all video frames instantly.

### FFT Visualizer (Planned)
PCM data is available after Opus decode. A future update will run FFT on this data to drive a real-time spectrum visualizer in the Compose UI.

## License

This project incorporates code from:
- [Moonlight Android](https://github.com/moonlight-stream/moonlight-android) (GPL-3.0)
- [moonlight-common-c](https://github.com/moonlight-stream/moonlight-common-c) (GPL-3.0)

MoonTone is licensed under GPL-3.0.
