# MoonTone

把多台电脑的 Sunshine 音频同时串流到手机，并在手机端混音输出。

> 纯音频体验：使用虚拟视频帧保持会话存活，画面不显示。
> 多设备同时串流：每路连接运行在独立 Android 进程，主进程统一混音。

## 功能

- 连接任意 Sunshine 主机，播放 PC 音频到手机
- 多设备同时连接（当前 32 路 slot，可扩展）
- 多路 PCM 混音输出
- 每路独立：连接 / 断开 / 静音 / 音量
- 自动重连（Worker 意外断开后重试 3 次）
- 自适应音质 / 延迟模式
- 实时音质规格显示
- 麦克风上行（UDP 48100 回传 PC）
- Material You 动态取色

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

需要 Android SDK / NDK。

```bash
cd echolink-app
./gradlew :app:assembleDebug
```

APK 输出：
```
app/build/outputs/apk/debug/app-debug.apk
```

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

## License

MIT
