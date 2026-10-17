# MoonTone

Stream audio from multiple PCs to your Android phone at the same time, mix it on the phone, and control every device from one panel.

> Pure-audio experience: a tiny dummy video stream keeps the session alive; nothing is shown on screen.
> Multi-device simultaneous streaming: each connection runs in its own Android process, the main process mixes PCM.

[Download v0.3.0](https://github.com/Halo0sama/MoonTone/releases/tag/v0.3.0) · [Release notes](docs/releases/v0.3.0.md) · [Third-party notices](THIRD_PARTY_NOTICES.md)

## Screenshots

<p align="center">
  <img src="screenshots/home-v0.3.0.png" width="40%" />
  <img src="screenshots/settings-v0.3.0.png" width="40%" />
</p>

## Features

- Connect to any Sunshine host and play PC audio on your phone
- Connect multiple Sunshine hosts at the same time (currently 32 slots, extendable)
- Mix multiple PCM streams and output through one AudioTrack
- Per-device controls: connect / disconnect / mute / volume
- Auto-reconnect (up to 3 retries; manual disconnect cancels pending retries)
- Adaptive audio buffering for single-device playback and multi-device mixing
- Audio format and system output information
- Microphone uplink (UDP 48100 back to PC)
- Material You dynamic colors
- In-app update check (GitHub Releases, prompts on new version)

## Architecture

```
Main process: UI / MultiController / AudioMixer / AudioTrack
  ├─ LocalSocket PCM ← Worker :conn1 (PC A)
  ├─ LocalSocket PCM ← Worker :conn2 (PC B)
  └─ ...
```

- Each connection uses an independent `ConnectionWorkerService` process running a Moonlight instance
- The main process receives PCM and mixes it; the Sunshine server needs no changes

See [`docs/MULTI_CONNECTION.md`](docs/MULTI_CONNECTION.md) for details.

## Build

Requirements: JDK 21, Android SDK 37, Build Tools 36.0.0, NDK 27.0.12077973. Current APKs support arm64 Android 7.0 (API 24) and later. See [development notes](docs/DEVELOPMENT.md) for tests and current limitations.

```bash
cd echolink-app
./gradlew :app:assembleDebug
```

APK output:
```
app/build/outputs/apk/debug/app-debug.apk
```

## Usage

1. Install the APK and open MoonTone. On Android 17+, grant local-network access when prompted.
2. Make sure your phone and PCs are on the same LAN.
3. The app automatically discovers Sunshine hosts via mDNS (`_nvstream._tcp`).
4. Choose **Single-device playback** or **Multi-device mixing**, then connect the PCs you want to hear. Open the top-right **Settings → Playback** to choose whether hosts also play locally; device exceptions are managed on a separate settings page.
5. Once connected, use the per-device controls:
   - **Phone mute** to silence that stream on the phone
   - **Volume** slider to adjust its level
   - **Disconnect** to stop that stream
6. Optional: while a single-device connection is active, enable **Settings → Playback → Microphone uplink** to send the phone microphone back to that PC (UDP 48100). Logs, public-certificate export, advanced RTSP connection and update checks are also in Settings.

### Manual connection

If a host is not discovered (e.g. different subnet), enter its IP in the **Sunshine host IP** field and tap **Connect**.

## Tools

```bash
# Connect to a host
python3 tools/moontone_cli.py connect 192.168.31.174

# Status
python3 tools/moontone_cli.py status

# Receive microphone uplink on PC
python3 tools/mic_receiver.py
```

## Sunshine Compatibility

- The client uses `audioOnly=1` + `x-ml-audio-only:1`
- For Sunshine builds that do not support audio-only, a dummy video (`640x480@1fps`) keeps the session alive
- The official macOS prebuilt has a tray thread-safety crash; if you need the tray icon, build from source with the fix (see `SUNSHINE_BUILD.md`)

## Known Issues

- **Latency depends on network jitter and the output route.** Direct and mixed playback share adaptive buffering; recurring Wi-Fi gaps retain more protection, while stable streams gradually catch up. Bluetooth output adds its own buffering. See [latency tuning and verification](docs/LATENCY.md).
- mDNS discovery does not cross subnets. A host on a different subnet must be added manually by IP.
- The official macOS Sunshine prebuilt crashes on session start due to a tray thread-safety bug; use `system_tray = false` or the source-built version with the tray fix.
- Windows Sunshine may not send video for very small dummy resolutions; the app uses `640x480@1fps` as a compatible dummy stream.

## License

[MIT](LICENSE) for original MoonTone code. Bundled components retain their upstream licenses; see [release notes](docs/DEVELOPMENT.md#授权与发布记录).

---

# MoonTone

把多台电脑的 Sunshine 音频同时串流到手机，并在手机端混音输出，所有设备在一个面板统一控制。

> 纯音频体验：使用虚拟视频帧保持会话存活，画面不显示。
> 多设备同时串流：每路连接运行在独立 Android 进程，主进程统一混音。

## 截图

<p align="center">
  <img src="screenshots/home-v0.3.0.png" width="40%" />
  <img src="screenshots/settings-v0.3.0.png" width="40%" />
</p>

## 功能

- 连接任意 Sunshine 主机，播放 PC 音频到手机
- 多设备同时连接（当前 32 路 slot，可扩展）
- 多路 PCM 混音输出
- 每路独立：连接 / 断开 / 静音 / 音量
- 自动重连（最多重试 3 次，手动断开会取消待执行的重连）
- 单设备播放自动调节延迟缓冲
- 实时音质规格显示
- 麦克风上行（UDP 48100 回传 PC）
- Material You 动态取色
- 应用内自动检查更新（GitHub Releases，新版本弹窗提示）

## 架构

```
主进程：UI / MultiController / AudioMixer / AudioTrack
  ├─ LocalSocket PCM ← Worker :conn1（电脑 A）
  ├─ LocalSocket PCM ← Worker :conn2（电脑 B）
  └─ ...
```

- 每路连接使用独立 `ConnectionWorkerService` 进程运行 Moonlight 实例
- 主进程接收 PCM 并混音，Sunshine 服务端零改动

详细设计见 [`docs/MULTI_CONNECTION.md`](docs/MULTI_CONNECTION.md)。

## 构建

需要 JDK 21、Android SDK 37、Build Tools 36.0.0、NDK 27.0.12077973。当前 APK 支持 arm64、Android 7.0（API 24）及以上。开发和验证见[开发接管记录](docs/DEVELOPMENT.md)。

```bash
cd echolink-app
./gradlew :app:assembleDebug
```

APK 输出：
```
app/build/outputs/apk/debug/app-debug.apk
```

## 使用说明

1. 安装 APK 并打开 MoonTone；Android 17 及以上请允许局域网访问。
2. 确保手机和电脑在同一个局域网。
3. App 会自动通过 mDNS（`_nvstream._tcp`）发现 Sunshine 主机。
4. 首页选择“单设备播放”或“多设备混音”，对想听的电脑点击“连接”。主机是否同时出声统一在右上角“设置 → 播放”中修改，也可设置设备例外；下次连接或重连生效。详见[设置页面](docs/SETTINGS.md)与[主机声音选项](docs/HOST_AUDIO.md)。
5. 连接后可用每台设备的独立控制：
   - “手机静音”：关闭手机端该路声音
   - “音量”滑块：调节该路音量
   - “断开”：停止该路串流
6. 可选：单设备连接后，在“设置 → 播放 → 麦克风上行”中把手机麦克风回传到当前 PC（UDP 48100）。日志、证书导出、高级 RTSP 连接和更新检查也统一放在设置页。

### 手动连接

如果主机没有被自动发现（例如不同子网），在“Sunshine 主机 IP”里输入 IP，然后点击“连接”。

## 调试工具

```bash
# 连接设备
python3 tools/moontone_cli.py connect 192.168.31.174

# 状态
python3 tools/moontone_cli.py status

# 麦克风上行接收（PC 端）
python3 tools/mic_receiver.py
```

## Sunshine 兼容性

- 客户端使用 `audioOnly=1` + `x-ml-audio-only:1`
- 对不支持 audio-only 的 Sunshine，可使用虚拟视频（`640x480@1fps`）保持会话
- macOS 官方预编译版存在托盘线程崩溃问题，如需托盘请使用源码构建版（见 `SUNSHINE_BUILD.md`）

## 已知问题

- **延迟取决于网络抖动和输出链路**：单路和多路共用自适应缓冲，反复出现的 Wi-Fi 尖峰保留保护余量，稳定后逐步追近实时；蓝牙输出另有自身缓冲。见[延迟优化与验证](docs/LATENCY.md)。
- mDNS 发现不跨子网：不同子网的主机需要手动填 IP 连接。
- macOS 官方预编译版 Sunshine 会因托盘线程安全问题在会话启动时崩溃；可设置 `system_tray = false`，或使用带托盘修复的源码构建版。
- Windows Sunshine 对极小虚拟分辨率可能不发送视频；App 使用 `640x480@1fps` 作为兼容虚拟流。

## License

MoonTone 原创代码使用 [MIT](LICENSE)；嵌入组件保留各自上游许可，见[授权与发布记录](docs/DEVELOPMENT.md#授权与发布记录)。
