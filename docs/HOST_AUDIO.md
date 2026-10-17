# 主机同时播放声音

2026-10-18：按用户要求，将原先固定关闭的主机声音改为可选项。

设置页重构后，主机声音统一在右上角“设置 → 播放”管理，首页不再放置开关：

| 开关 | 连接时请求的行为 |
| --- | --- |
| 关闭（默认，保持旧行为） | 请求 Sunshine 不在主机本地播放声音，只向客户端串流 |
| 打开 | 请求主机继续播放，手机也接收声音 |

“主机同时播放声音”设置全局默认；“设备声音例外”可为每台电脑选择“跟随默认 / 主机与手机同时播放 / 只在手机播放”。旧版已保存的每个主机布尔值继续作为设备例外使用，不会被全局默认覆盖。设置退出或重建页面后保留；单路、多路、手动重新连接和自动重试均在启动会话时读取有效设置。

修改在**下一次连接或重连**生效，不会为了应用开关而打断当前串流。“手机静音”和音量仅控制手机端，与主机声音独立。

“设置 → 高级与诊断 → 高级连接”接入的是外部已启动的 RTSP 会话，MoonTone 不发送启动请求，需在发起该会话的客户端设置主机播放。

## Sunshine 是否需要调整

一般不需要改 Sunshine 配置。客户端在 `/launch` 请求中发送 `localAudioPlayMode=0/1`，Sunshine 据此处理主机播放。已有会话不能通过修改手机偏好实时改变该参数。

如果请求与实际主机声音不一致，检查 Sunshine 的 **Configuration → Audio/Video** 中的 `audio_sink`（捕获设备）和 `virtual_sink`（用于静音串流的虚拟设备），以及操作系统默认输出。强制指定虚拟设备、没有可用静音设备或特定平台/服务端实现，均可能使效果与预期不同。不要关闭 `stream_audio` 来控制主机静音，那会停止串流声音。

当同一 Sunshine 同时被其他客户端使用时，主机物理输出属于共享资源；实际设备切换可能由最先启动的会话决定。本选项控制本次启动请求，不强制覆盖其他会话和系统音量。

依据：[Sunshine 会话参数实现](https://github.com/LizardByte/Sunshine/blob/master/src/nvhttp.cpp)、[音频设备选择实现](https://github.com/LizardByte/Sunshine/blob/master/src/audio.cpp)、[官方音频配置说明](https://docs.lizardbyte.dev/projects/sunshine/latest/md_docs_2configuration.html#virtual_sink)。

## 初版开关验证（设置页重构前）

Android 测试覆盖默认值、按主机隔离、界面开关点击与 Activity 重建。真实自签名 mTLS 测试主机接收 `/launch`，验证默认关闭、偏好打开、偏好再次关闭依次发送 `0/1/0`，并走过实际多路会话准备代码。

这些测试确认请求与保存行为，不替代 Windows/macOS 主机扬声器听感验证；没有修改实体电脑的 Sunshine 配置。设置页重构后的测试及手机覆盖更新记录见[设置页验证](SETTINGS.md#验证)。

初版结果：31 项 JVM 测试、14 项 Android 测试全部通过，0 failures / 0 errors；Debug 与未签名 Release 构建通过，Lint 为 0 errors、30 warnings。日志为 `echolink-app/app/build/reports/host-audio-verification.log`；界面截图为同目录 `host-audio-ui.png`。
