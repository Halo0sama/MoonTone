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
  - 监听 Worker 状态与 PCM
- `AudioMixer`：
  - 每路一个 `PcmSource`（LocalSocket 读取线程）
  - 统一 48kHz / 2ch / 16bit
  - 混音：`sum(clip(a+b))`
  - 写 `AudioTrack`

## 控制协议（主进程 ↔ Worker）
- 启动 Intent extras：
  - `host`, `rtspUrl`, `appVersion`, `gfeVersion`, `codecModeSupport`, `audioConfig`, `riKey`, `riKeyId`, `localSocketName`
- Worker 启动后连接 `LocalSocket`，然后开始 `MoonToneConnection`。
- Worker → 主进程 PCM 帧格式：
  - `[magic 4B "MTPC"][seq 4B BE][sampleRate 4B BE][channels 4B BE][pcmLength 4B BE][pcm bytes]`
- Worker → 主进程状态消息（通过同一个 socket 或单独控制 socket）：
  - JSON 行：`{"type":"state","state":"CONNECTED"}`

## 里程碑
1. ✅ 架构文档
2. 多进程 Worker Service 骨架（2 路先验证）
3. PCM LocalSocket 传输 + AudioMixer
4. MultiConnectionManager 接入现有配对/启动逻辑
5. UI 多设备同时控制面板
6. 稳定性/保活/异常恢复
