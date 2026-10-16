# MoonTone 多设备同时串流架构

## 目标
手机可以同时连接任意数量电脑的 Sunshine，在同一个面板统一控制；多路音频在手机端混音后输出到同一扬声器/蓝牙设备。**Sunshine 服务端不需要任何改动。**

## 为什么不能直接在一个进程里开多路 Moonlight
`moonlight-common-c` 是全局单连接实现：
- 全局 `StreamConfig`、连接回调、socket、音视频队列等都是 static。
- `MoonBridge` JNI 也是单实例。
- 同一进程内无法同时跑两路 `LiStartConnection`。

## 方案：每路连接一个独立进程（多进程 Worker）
```
┌─────────────────────────── 主进程 ───────────────────────────┐
│ UI / MultiConnectionManager / AudioMixer / AudioTrack        │
│                                                              │
│  设备面板：设备A 连接中/音量/静音/断开                        │
│            设备B 连接中/音量/静音/断开                        │
└───────────────┬───────────────────────────────┬──────────────┘
                │ LocalSocket (PCM 48k/2ch/16bit) │
┌───────────────▼──────────────┐  ┌───────────────▼──────────────┐
│ Worker 进程 :conn1           │  │ Worker 进程 :conn2           │
│ MoonToneConnection (MoonBridge)│  │ MoonToneConnection (MoonBridge)│
│ Sunshine A                   │  │ Sunshine B                   │
└──────────────────────────────┘  └──────────────────────────────┘
```

- 每个 Worker 进程有独立的 MoonBridge 全局状态，所以可以各自跑一路连接。
- 主进程只负责 UI、混音、输出，不直接跑 Moonlight。
- 每路 PCM 通过 `LocalSocket` 从 Worker 流式送回主进程。
- 主进程 `AudioMixer` 把多路 PCM 叠加后写入一个 `AudioTrack`。

## 进程/组件设计
- `ConnectionWorkerService`：一个 Worker 的基类，在各自进程中运行。
- 为了支持 N 路，声明 N 个 Service 子类 + N 个 `android:process`：
  - `:conn1`, `:conn2`, ..., `:connN`
- 主进程 `MultiConnectionManager`：
  - 为每台设备分配一个空闲 slot
  - 在主进程完成 HTTP pairing/launch，拿到 RTSP/密钥
  - 启动对应 Worker Service 并传入连接参数
  - 启动前预留槽位；取消时 shutdown/close 监听器和连接
  - 收到第一帧合法 PCM 后确认连接；立即 EOF/超时进入有界重试
- `AudioMixer`：
  - 每路一个 `PcmSource`（LocalSocket 读取线程）
  - 统一 48kHz / 2ch / 16bit
  - 混音：宽精度累加后按当前有效音源数归一化，再裁剪至 16bit
  - 时钟漂移修正以完整左右声道采样对为单位
  - 写 `AudioTrack`

## 控制协议（主进程 ↔ Worker）
- 启动 Intent extras：
  - `host`, `rtspUrl`, `appVersion`, `gfeVersion`, `codecModeSupport`, `audioConfig`, `riKey`, `riKeyId`, `localSocketName`
- Worker 启动后连接 `LocalSocket`，然后开始 `MoonToneConnection`。
- Worker → 主进程 PCM 帧格式：
  - `[magic 4B "MTPC"][seq 4B BE][sampleRate 4B BE][channels 4B BE][pcmLength 4B BE][pcm bytes]`
- 当前 socket 只传二进制 PCM 帧，不混入 JSON 状态行；连接状态由首帧、EOF 与超时驱动。

## 当前状态

32 路 Worker、应用级控制器、混音、独立静音/音量和自动重连已接入界面。详见 [开发记录与验证边界](DEVELOPMENT.md)。本轮的 Android 测试验证 LocalSocket 与生命周期；32 路同时播放和真实端到端延迟仍需多主机实测。
