# MoonTone 恢复/开发状态

> 更新：2026-08-17 12:22（托盘版 Sunshine + 中文 App + 双向音频均验证通过）

## 已实现

### ✅ 自建 Sunshine：原生开箱即用 + 托盘
- `dist/Sunshine-audioonly.app`（v2026.516 + audioOnly 补丁 + **菜单栏图标已恢复**）
- 修复：`tray_update` 自动切回主线程，配对不再崩溃
- 验证：`System tray created`、`Starting main loop`、配对触发托盘更新不崩、音频会话稳定

### ✅ 真·纯音频（PC→手机）
- audioOnly：不建视频线程/不传视频
- Opus：48kHz / 2ch / 512 kbps / LOWDELAY / 5ms，CONNECTED 稳定

### ✅ 双向麦克风上行（手机→PC）
- 协议 `[MTUP][seq][timestamp][ssrc][Opus]`，UDP 48100
- PC 接收器：3 包预缓冲 + opuslib 解码 + sounddevice 实时播放 + WAV
- 实测：609 包 / 0 丢包 / 实时播放成功

### ✅ App 界面中文 + Material You
- 全部 UI 中文化（连接/断开/PIN/日志/证书/麦克风开关等）
- Material You（Monet）动态主题 + 品牌 fallback
- 多设备混音提示（输出设备检测）

## 产物
- `dist/moontone-0.1.0-zh-v2.apk`（最新中文版）
- `dist/Sunshine-audioonly.app`（托盘版自建 Sunshine）
- `dist/moontone_mic_realtime.wav`
- `tools/start_sunshine.sh`（一键启动）
- `research/mic-uplink-design.md`、`research/material-you-ui-design.md`

## 使用
```bash
# 启动 Sunshine（带托盘）
bash tools/start_sunshine.sh
# 管理页
open https://localhost:47990
# 手机连接（无线 adb）
export ANDROID_SERIAL="adb-bb5ab72d-PpYWOC._adb-tls-connect._tcp"
adb forward tcp:47748 tcp:47748
python3 EchoLink/tools/moontone_cli.py connect 192.168.31.174
# 麦克风上行
python3 EchoLink/tools/moontone_cli.py micstart 192.168.31.174
```

## 新功能：自适应音质/延迟模式（环境实时调参）
- UI：连接状态下可切换“延迟优先 / 均衡 / 音质优先”
- 不再只靠写死参数，改为实时检测环境后计算目标阈值：
  - 音频帧送达抖动（`AndroidAudioRenderer.getJitterMs`）
  - 迟到/突发帧间隔（gap events）
  - AudioTrack underrun 次数
  - MoonBridge 待播积压（pending）
  - WiFi RSSI / 链路速率（`EnvironmentMonitor`，0–100 网络质量）
- 计算公式：`目标阈值 = pending 均值 + 安全余量`；安全余量由模式偏好 + 抖动 + 网络质量 + 欠载/丢帧信号组成，并做每秒最多 ±20ms 平滑
- 模式只表达偏好（延迟/均衡/音质），具体阈值由环境决定
- CLI：`audiomode LATENCY|BALANCED|QUALITY`；status 显示 `audioMode` / `audioThresholdMs` / `audioJitterMs` / `networkQuality` / `audioUnderruns`
- 实测（WiFi 良好）：延迟优先时阈值自动稳定在 89–100ms，抖动约 1.7–3.8ms，网络质量 100，无持续欠载
- 注意：连接 `192.168.1.3`（不同子网）时抖动飙高并自动断开，建议检查该设备 Sunshine 日志/网络路由；同网段 `192.168.31.174` 稳定
- 最新 APK：`dist/moontone-0.1.0-zh-v31.apk`

## 新功能：实时音质规格显示
- 连接状态卡片显示当前实际规格：下行 Opus 编码/采样率/声道、码率、包长
- 显示缓冲/阈值/待播积压，以及上行麦克风运行状态与码率
- 每秒由 `MoonToneController.audioStats` 刷新；断开后停止
- 实测截图：下行 Opus · 48000 Hz · 2 ch / 512 kbps · 5 ms/包 / 40 ms / 120 ms · 待播 80 ms
- 最新 APK：`dist/moontone-0.1.0-zh-v31.apk`

## 新功能：播放控制（静音）
- 连接状态新增“播放控制”卡片：`静音 / 取消静音`
- 静音仅关闭手机端 AudioTrack 输出（不中断串流、不暂停电脑）
- 静音状态由 `MoonToneController.muted` 驱动，断开连接自动复位
- 已移除 `PC 播放/暂停`：验证 macOS Sunshine 键盘映射中媒体键（VK_MEDIA_PLAY_PAUSE 0xB3 等）全部为 `-1`（不支持），无法可靠控制电脑播放
- 实测：静音/取消静音切换正常
- 最新 APK：`dist/moontone-0.1.0-zh-v31.apk`

## 新功能：返回设备列表 + 底部小白条适配
- 连接后左上角新增“← 返回设备”按钮，点击断开并回到主界面选择其他设备
- 底部小白条重新启用 edge-to-edge：主题 XML 透明系统栏 + `enableEdgeToEdge()` + 关闭对比度背景，内容可穿过底部手势条
- 已走通“官方 Sunshine + 仅音频传输”：`audioOnly=1` + `x-ml-audio-only:1`，官方 Sunshine 接受并只跑音频会话
- Sunshine 图标：官方预编译版在 macOS 27 会因托盘线程安全问题崩溃，因此使用源码构建的 `dist/Sunshine-audioonly.app`（官方代码 + 托盘线程修复），`system_tray = true`，菜单栏/状态栏图标正常且不崩溃
- Android 端采用兼容模式（不透明系统栏，状态栏图标可见）
- 实测：官方 Sunshine + 仅音频连接成功，返回按钮可断开并回到设备列表，FAB 正常悬浮
- 最新 APK：`dist/moontone-0.1.0-zh-v31.apk`

## 修复：快速切换设备/重连导致的自动断连与崩溃
- 找到根因：旧连接的 `connectionTerminated` 回调会串到新连接上，导致状态/错误错乱
- 增加连接代数（generation）守卫：旧连接回调全部忽略，只处理当前连接的回调
- `connectionStarted` 时清除旧错误，避免“已连接但显示 error -1”
- 修复 `AndroidAudioRenderer` 在重连期间 `track==null` 时 `playDecodedAudio/cleanup/start` 的空指针崩溃
- 重连等待从 200ms 提高到 500ms，降低旧连接收尾干扰
- 实测：快速从 `192.168.1.3` 切到 Mac，不再崩溃，状态正常 `CONNECTED` 且无错误
- 最新 APK：`dist/moontone-0.1.0-zh-v31.apk`

## App 图标整体下移（v58）
- 蓝色圆盘和白色音频柱作为一个整体向下移动，相对位置不变
- 月牙位置不变
- 产物：`dist/moontone-0.1.0-zh-v58-icondown.apk`

## App 图标仅调换前后关系（v57）
- 完全回退到 v54 几何：柱子尺寸/位置、月牙位置均不变
- 仅调换绘制顺序：蓝色圆盘在前，白色音频柱在后（柱子被圆盘遮挡）
- 不加长、不改其他元素
- 产物：`dist/moontone-0.1.0-zh-v57-iconpure.apk`

## App 图标前后关系修正（v56）
- 回退到 v54 的几何布局（柱子宽度/位置、月牙位置不变）
- 仅调换前后关系：蓝色圆盘在前，白色音频柱在后，柱子略微上下露出
- 产物：`dist/moontone-0.1.0-zh-v56-iconlayer2.apk`

## App 图标前后关系（v55）
- 白色音频柱移到蓝色圆盘后面，圆盘挡住柱子中间，柱子上下两端露出
- 保持 v54 的对齐与月牙位置
- 视觉模型确认层次关系正确、整体协调
- 产物：`dist/moontone-0.1.0-zh-v55-iconlayer.apk`

## App 图标微调（v54）
- 保持 v52 整体偏左布局，不整体居中
- 仅将白色音频柱组的中轴对齐到蓝色圆盘中轴（x=40）
- 黄色月牙稍微向左下移动
- 产物：`dist/moontone-0.1.0-zh-v54-iconalign2.apk`

## App 图标回退（v52）
- 按用户要求回退到 v52 图标（蓝色圆盘偏左、白色音频柱、右上角独立月牙）
- 手机已安装 v52 APK，桌面同步为 v52
- 代码中图标资源已恢复为 v52 版本
- 产物：`dist/moontone-0.1.0-zh-v52-iconfix.apk`

## App 图标微调（v53）
- 蓝色圆盘与白色音频柱中轴对齐（整体居中）
- 黄色月牙稍微向左下移动
- 重新生成全套 mipmap 与自适应 foreground
- 产物：`dist/moontone-0.1.0-zh-v53-iconalign.apk`

## App 图标修正（v52）
- 根据视觉模型反馈修正图标：黄色月牙不再与蓝色圆盘生硬重叠，改为右上方独立月牙；蓝色圆盘 + 白色音频柱保持居中协调
- 重新生成全套 mipmap PNG 并更新自适应 foreground
- 产物：`dist/moontone-0.1.0-zh-v52-iconfix.apk`

## App 图标更换（v51）
- 重新设计 launcher 图标：深色底 + 蓝色声波圆盘 + 白色音频柱 + 黄色月牙
- 生成全套 mipmap PNG（mdpi~xxxhdpi）并更新自适应图标 foreground
- 产物：`dist/moontone-0.1.0-zh-v51-newicon.apk`

## UI：IP 与连接之间的内容收进“更多”（v50）
- IP 输入框和“连接”按钮之间新增“更多 / 收起更多”
- 手动模式（RTSP + 密钥）、导出证书等统一折叠在“更多”里
- 产物：`dist/moontone-0.1.0-zh-v50-more.apk`

## ✅ 多设备同时串流（定版 v49）
- 用户确认：两路同时播放无嘶啦，延迟可接受
- 方案：多进程 Worker + AudioMixer + DriftSource 采样点级时钟漂移补偿
- 当前 APK：`dist/moontone-0.1.0-zh-v49-driftfix.apk`（桌面同步）
- 后续如需扩展更多路数，按既有 slot 模式继续增加 Worker/Manifest 即可

## 多设备同时串流（可用版本 v49 时钟漂移补偿）
- 实现逐路 DriftSource：根据队列水位，在采样点级别偶尔跳过/重复 1 个 sample，平滑补偿两路 48kHz 时钟漂移
- 保留 v45 大缓冲作为底层稳定，配合采样点级补偿
- 目标：完全消除间隔性细微嘶啦，同时延迟保持 v45 水平
- 产物：`dist/moontone-0.1.0-zh-v49-driftfix.apk`

## 多设备同时串流（可用版本 v48 回退到 v45 稳定点）
- v47 自适应缓冲导致严重可听问题，已回退到 v45 参数：队列 240ms + AudioTrack 120ms
- 这是目前“几乎无嘶啦但延迟稍大”的已知稳定点
- 下一步若要彻底无嘶啦且低延迟，需要做真正的逐路重采样/时钟漂移补偿，而不是简单增大缓冲或暂停写入
- 产物：`dist/moontone-0.1.0-zh-v48-revert45.apk`

## 多设备同时串流（可用版本 v47 自适应抖动缓冲）
- 实现自适应缓冲：检测各路队列水位，低于安全水位时自动增加缓冲/短暂等待，高于水位时自动降低延迟
- 目标：在较低平均延迟下，自动消除两路时钟漂移导致的嘶啦
- 产物：`dist/moontone-0.1.0-zh-v47-adaptive.apk`

## 多设备同时串流（可用版本 v46 平衡版）
- 在 v45 基础上降低延迟：每路队列 32 帧（160ms），AudioTrack buffer 100ms
- 目标：嘶啦几乎消失的同时，延迟比 v45 更小
- 请测试：延迟是否明显下降、嘶啦是否仍可接受
- 产物：`dist/moontone-0.1.0-zh-v46-balanced.apk`

## 多设备同时串流（可用版本 v45 增大缓冲实验）
- 为缓解两路细微嘶啦：每路队列提高到 48 帧（240ms），AudioTrack buffer 提高到 120ms
- 代价是延迟会有所增加；请确认延迟是否仍可接受、嘶啦是否消失
- 产物：`dist/moontone-0.1.0-zh-v45-morebuffer.apk`

## 多设备同时串流（可用版本 v44 回退 gap-fill）
- v43 的“回放上一帧”会放大问题，已回退
- 保留 v42 的：120ms 队列、80ms AudioTrack buffer、1/N 混音归一化
- 单路应恢复无杂音；多路细微嘶啦问题回到 v42 状态，继续从时钟漂移方向排查
- 产物：`dist/moontone-0.1.0-zh-v44-revertgap.apk`

## 多设备同时串流（可用版本 v43 多路细微嘶啦修复）
- 针对“单路正常、两路有细微嘶啦”的问题：PcmSource 在队列瞬时为空时回放上一帧，避免静音缺口造成的爆音
- 结合 120ms 队列 + 80ms AudioTrack buffer + 1/N 混音归一化
- 请测试两路同时播放是否还有细微嘶啦
- 产物：`dist/moontone-0.1.0-zh-v43-mixgapfix.apk`

## 多设备同时串流（可用版本 v42 杂音继续修复）
- 针对嘶啦声：每路队列提高到 24 帧（120ms），AudioTrack buffer 提高到 80ms，减少因丢帧/欠载造成的爆音
- 多路混音增加归一化（1/N 缩放），避免多路叠加削波失真
- 请测试单路是否还有嘶啦声；如果单路也有，问题更可能在传输/播放线程
- 产物：`dist/moontone-0.1.0-zh-v42-cracklefix.apk`

## 多设备同时串流（可用版本 v41 延迟/杂音修复）
- 修复巨大延迟：AudioTrack buffer 之前被设成约 4 秒，现改为约 40ms
- 修复/缓解“嘶啦嘶啦”杂音：每路 PCM 队列从 128 帧（约 640ms）降到 16 帧（约 80ms），避免陈旧数据堆积和播放抖动
- 请重点测试：单路音质、多路混音音质、延迟是否明显下降
- 产物：`dist/moontone-0.1.0-zh-v41-latencyfix.apk`

## 多设备同时串流（可用版本 v40 音频修复）
- 修复“声音很糊”的关键 bug：AudioMixer 之前按 10ms 帧写，但 Moonlight 送来的是 5ms/480 short 帧，导致一半数据补零，声音发糊
- 改为按 5ms/480 short 混音写入
- 实测连接逻辑不变，音质应恢复清晰
- 产物：`dist/moontone-0.1.0-zh-v40-audiofix.apk`

## 多设备同时串流（可用版本 v39）
- 扩展至 32 路 Worker（`:conn1`~`:conn32`），UI 显示“最多 32 路”
- 自动重连、独立音量/静音/断开/重连按钮均保留
- 实测 Mac + Windows 两台同时连接正常，两个 worker 进程运行中
- 产物：`dist/moontone-0.1.0-zh-v39-multi32.apk`

## 多设备同时串流（可用版本 v38）
- 扩展至 16 路 Worker（`:conn1`~`:conn16`），UI 显示“最多 16 路”
- 每路已连接设备支持独立音量滑块、静音、断开
- 错误状态支持“重连”按钮
- 自动重连：Worker 意外断开后自动重试 3 次（3 秒间隔），成功自动恢复
- 实测 Mac + Windows 同时连接正常
- 产物：`dist/moontone-0.1.0-zh-v38-multi16.apk`

## 多设备同时串流（可用版本 v35）
- 扩展至 8 路 Worker（`:conn1`~`:conn8`），UI 显示“最多 8 路”
- 状态机更准确：Worker PCM socket 真正连接后才显示“已连接”；10 秒未连上标记超时
- 增加断流回调：Worker 意外断开时 UI 标记“连接已断开”
- 实测 Mac + Windows 两台同时连接正常，各自可静音/断开
- 产物：`dist/moontone-0.1.0-zh-v35-multi8.apk`

## 多设备同时串流（可用版本 v34）
- 已实现真实多路同时连接：Mac + Windows 两台 Sunshine 同时连接成功
- 每路连接运行在独立进程 `:conn1`/`:conn2`，主进程 AudioMixer 混音
- UI 主界面“多设备同时串流”面板：每台设备独立 连接/断开/静音
- 修复 Worker 进程 DebugControlServer 端口冲突（`:conn` 进程不再启动主控制器/调试服务）
- 当前上限 4 路，Sunshine 端零改动
- 骨架 APK：`dist/moontone-0.1.0-zh-v34-multistable.apk`

## 施工中：多设备同时串流（多进程架构）
- 方案：每路连接跑独立 Android 进程（Worker Service），主进程统一混音输出，Sunshine 端零改动
- 已落地：
  - `docs/MULTI_CONNECTION.md` 架构文档
  - PCM LocalSocket 协议 + `SocketAudioRenderer`
  - 4 路 Worker Service（`:conn1`~`:conn4`）Manifest 声明
  - `AudioMixer` 多路 PCM 混音骨架
  - `MultiConnectionManager` 启动/停止 worker + 接收 PCM
  - `MultiSessionLauncher` 为每路准备独立 pairing/launch 参数
- 尚未接入 UI/实际多路连接，下一步：主进程 MultiController 接入面板
- 骨架 APK：`dist/moontone-0.1.0-zh-v32-multiskel.apk`

## 新功能：多设备统一控制面板
- 连接状态下新增“多设备控制”卡片
- 列出所有已保存/发现的 Sunshine 设备，显示在线状态
- 当前设备标记“当前”，其他在线设备可一键“切换”（断开当前并连接目标）
- 说明：Moonlight 内核当前只支持单路连接，所以暂不能多路同时混音；面板先实现统一管理与快速切换
- 实测：连接 Mac 后面板显示设备列表，可快速切换
- 最新 APK：`dist/moontone-0.1.0-zh-v31.apk`

## 修复：Windows 端连接后自动断开
- 根因：`32x32@1fps` 的虚拟视频在 Windows Sunshine 上不产生有效视频流，客户端收不到视频流量后以 `ML_ERROR_NO_VIDEO_TRAFFIC (-100)` 断开
- 解决：虚拟视频改为 `640x480@1fps`，Windows/Mac 都能持续输出视频流量，保持会话存活；画面仍由 `DummyVideoRenderer` 丢弃，用户无感知
- 同时保留之前的连接代数守卫、空指针修复、会话资源清理
- 实测：Windows `192.168.1.3` 持续 20 秒以上不掉线；Mac `192.168.31.174` 也正常
- 最新 APK：`dist/moontone-0.1.0-zh-v31.apk`

## 新功能：串流保活（防自动断连）
- 新增 `StreamKeepAlive`：串流期间持有 `PARTIAL_WAKE_LOCK` + WiFi 全性能锁
- 防止手机锁屏/后台后 WiFi 休眠导致 UDP 音频流自动断开
- 连接时自动获取，断开时自动释放
- 实测：同网段 Mac 连接持续 60 秒以上不掉线；跨子网 `192.168.1.3` 仍可能因网络路由不稳定断开
- 最新 APK：`dist/moontone-0.1.0-zh-v31.apk`

## 新功能：悬浮暂停/静音按钮 + 串流规格卡片
- 静音/取消静音改为右下角悬浮按钮：未静音显示 `❚❚`（点击暂停/静音），静音后显示 `▶`（点击恢复）
- 移除原“播放控制”卡片，避免重复
- 原“音频串流中…”占位框不是命令行 UI，已移除
- 新增“串流规格”卡片，放在“当前音质规格”上方，使用相同的 Card + Row UI
- 串流规格显示：状态、主机、协议、会话
- 实测：FAB 可切换 `❚❚ ↔ ▶`；串流规格在音质规格上方显示正常
- 最新 APK：`dist/moontone-0.1.0-zh-v31.apk`

## 新功能：设备列表去重合并（Moonlight 式）
- mDNS 发现与已保存设备按 IP 合并，同一台机器只显示一个卡片
- 卡片状态：`在线/离线` + `已保存` 标记
- 未保存的发现设备显示“添加”，已保存设备显示“删除”
- 实测：`Mac 192.168.31.174` 只出现一次，显示“在线 · 已保存”
- 最新 APK：`dist/moontone-0.1.0-zh-v31.apk`

## 新功能：自动扫描设备（mDNS）
- 使用 NsdManager 发现 `_nvstream._tcp`（Sunshine 同款服务）
- 首页统一“可连接设备”：自动扫描、在线状态、一键连接、添加/删除
- 实测：自动发现 `halo 的 MacBook Air @ 192.168.31.174`
- 最新 APK：`dist/moontone-0.1.0-zh-v6.apk`

## 新功能：已保存设备（Moonlight 式设备列表）
- 连接成功后自动保存设备（名称 + IP）
- 首页显示“已保存设备”：在线/离线状态、一键连接、删除
- 持久化：SharedPreferences `moontone_hosts`
- 最新 APK：`dist/moontone-0.1.0-zh-v5.apk`

## 稳定性修复（2026-08-17 13:37）
- 重连前强制断开，避免 native `stage` 断言崩溃
- 遇到“App already running”自动 `/cancel` 后重试
- 音频缓冲增大到 40ms/80ms、丢帧阈值放宽到 120ms，解决“声音一卡一卡”
- 最新 APK：`dist/moontone-0.1.0-zh-v4.apk`

## 下一步
1. 专用麦克风前台服务（`FOREGROUND_SERVICE_MICROPHONE`）与动态权限申请
2. 上行 FEC / 更强抖动缓冲
3. Material You 完整组件拆分（Home/Settings/Logs 页）
4. 蓝牙音响实测与设备切换优化
