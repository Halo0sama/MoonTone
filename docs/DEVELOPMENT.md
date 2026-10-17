# MoonTone 开发接管记录

更新：2026-10-16。开发基线为 GitHub `main` 的 `659e3d5`，本地原有 `c21ae6d` 已包含在该历史中。首轮以修复已有功能为目标；新增产品功能留待确认。

后续改进：2026-10-18 的[延迟优化](LATENCY.md)及用户请求的[主机同时播放声音开关](HOST_AUDIO.md)。

设置页重构：[页面结构、设置归属与兼容规则](SETTINGS.md)。主机声音不再重复出现在首页，其他零散选项已集中到设置页。

## 本轮改进

| 范围 | 原有问题 | 当前行为 |
| --- | --- | --- |
| Android 17 | targetSdk 已到 37，但没有局域网权限，发现设备会意外弹出 NSD 选择器 | 声明并请求局域网权限；拒绝后显示操作提示；无权限时不启动扫描 |
| 多路会话 | 重连过程调用 disconnect，重试计数被清空；短暂连上也重置计数 | 最多三次自动重试；稳定连接 30 秒后恢复重试预算；手动重连可重新开始 |
| 取消与快速切换 | 准备会话、延迟重连、旧回调可能在槽位复用后继续运行 | 会话与尝试身份校验；取消任务及待确认 PIN 的 HTTP socket；忽略旧尝试的回调 |
| Worker 资源 | accept 成功前槽位尚未登记，取消时找不到监听器；仅 close 无法可靠唤醒阻塞 accept | 启动前预留槽位；先 shutdown 再 close；同时释放连接、源和监听器 |
| 连接状态 | Worker 连接本地 socket 就显示已连接 | 收到第一帧合法 PCM 才显示已连接；立即 EOF 进入恢复流程 |
| Native 生命周期 | JNI 启停可能重叠；同一可变监听器使代数守卫失效；主线程等待关闭 | 每进程串行执行启停；每次连接独立监听器；主线程仅发送取消和排队操作；在进入 JNI 前检查密钥长度和非零编码能力，避免 C 断言崩溃 |
| 界面重建 | Compose remember 中的多路控制器丢失，后台 Worker 仍然运行 | Application 持有控制器，界面重建继续观察同一状态 |
| 音量/静音 | 取消静音恢复到 100%；静音中拖动音量可能出声；重连丢失设置 | 音量与静音独立保存，静音时有效增益始终为零，重连前设置好初始增益 |
| 立体声 | 漂移补偿丢弃或复制一个 short，破坏左右声道交错排列 | 每次补偿一对 L/R 采样；欠载保持最后一对；先宽精度累加再归一化 |
| 混音启停 | 旧混音线程可能写入新 AudioTrack；短写丢掉后半帧 | 每线程只持有自己的 AudioTrack；处理短写；关闭时唤醒并回收线程 |
| 前台播放 | 多路主进程缺少播放服务保护；通知按钮只关闭通知 | 单/多路共享播放服务所有权；通知“断开全部”确实停止串流；多路持有保活锁 |
| 麦克风 | 未申请运行时权限；无 microphone 类型前台服务；停止时可能释放仍在 native 使用的 encoder | 授权后由麦克风前台服务采集；采集线程拥有自己的资源并最终释放；正确累积短读 |
| 设备列表 | 离线探测串行且只查一次；过期发现回调修改已停止页面；连接把名字写成 Mac | 并行周期探测；扫描跟随页面 STARTED 生命周期，解析串行且回调在主线程；保留已有名称 |
| 配对安全 | HTTPS 接受任意服务端证书；日志写入完整查询中的会话密钥 | 新配对保存 PIN 验证过的服务端证书并校验后续连接；日志去掉查询/响应秘密 |
| 备份与日志 | 私钥进入系统备份；多个进程共享日志文件和不安全日期格式器 | 备份排除私钥和日志；Worker 分文件；日期与写入同步 |
| 更新检查 | 把 beta1 的数字当作版本段，大数字可能溢出；Activity 结束后仍可能弹窗 | 比较版本核心与预发布标识，忽略构建元数据；更新检查跟随生命周期 |
| PC 麦克风接收 | 乱序/重复序号按 unsigned 差值产生巨量 PLC；音频全部积存在内存 | 有界排序、去重、有限 PLC、异常大跳重同步；WAV 流式写入并补齐尾包 |
| 工程 | 无回归测试和统一入口，Android 子目录 README 与当前项目不符 | 增加 JVM、Android、Python 回归测试与 CI 配置；更新实际构建文档 |

## 构建与验证

```bash
cd echolink-app
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
ANDROID_SERIAL=emulator-5556 ./gradlew :app:connectedDebugAndroidTest
./gradlew :app:assembleRelease
cd ..
python3 -m unittest discover -s tools/tests -v
```

工具链：JDK 21；SDK 37；Build Tools 36.0.0；NDK 27.0.12077973；Gradle 9.8.0；AGP 9.4.1。保留 GitHub 基线已经采用的 Material 3 Expressive 依赖版本，本轮未额外升级产品依赖。

CI 配置在 `.github/workflows/android.yml`，执行 Python 测试、JVM 测试、Lint、Debug 构建；Android 实际运行测试需要 arm64 模拟器/设备，按上述命令单独运行。CI 文件尚未在 GitHub 上执行，本地执行结果记录在本文件末尾。

本轮使用独立 `moontone37` 模拟器（Android 17 / API 37 / arm64），不对已有实体手机安装应用。Android 测试用真实 LocalSocket、真实 AudioRecord/Opus/UDP、Activity 重建，以及自签名 mTLS 本地测试主机，Sunshine Worker 的 socket 测试通过测试 Context 拦截 Service 启动，避免访问真实 PC。

报告路径：

- JVM：`echolink-app/app/build/reports/tests/testDebugUnitTest/index.html`
- Android：`echolink-app/app/build/reports/androidTests/connected/debug/index.html`
- Lint：`echolink-app/app/build/reports/lint-results-debug.html`
- Debug APK：`echolink-app/app/build/outputs/apk/debug/app-debug.apk`
- 未签名 Release APK：`echolink-app/app/build/outputs/apk/release/app-release-unsigned.apk`

## 关键维护约束

多路控制器的可变状态只在 Main dispatcher 上更新；配对与启动网络请求使用可取消的 OkHttp 回调；证书和协议准备在 IO 上执行。不要在重试内部调用面向用户的 disconnect，否则会破坏会话身份和重试预算。释放槽位之前先使旧回调失效，释放监听器时必须唤醒阻塞 accept。

moonlight-common-c 使用进程全局状态。每个 Worker 独占一套 native 状态；同一进程内 start/stop 必须串行，不能在连接尚未退出时启动另一条。取消先调用 interrupt，实际 stop 在专用线程执行。监听器必须绑定不可变的单次连接身份。

PCM 为 48kHz、双声道、16bit little-endian；包头 magic/seq/rate/channels/byteLength 为 big-endian。读取端拒绝错误格式、不完整包和超过 120ms Opus 包上限的负载。任何丢弃/插入采样操作都必须保持完整双声道帧。

2026-10-16 首轮仅修复声道和生命周期。2026-10-18 已将单路和多路改为共用自适应 PCM 缓冲，区分 AudioTrack 的分配容量与有效写入上限，详见[延迟优化记录](LATENCY.md)。这些参数仍不能作为实测端到端延迟。

Sunshine 使用自签名证书，不能直接切换成系统 CA 校验。新配对先用 PIN challenge 验证服务端证书后固定。旧版本没有保存服务端证书，因此首次成功 mTLS 请求采用一次 TOFU 迁移，之后拒绝证书变化；重装 Sunshine 或证书轮换时需重新配对。首次迁移仍需要可信局域网环境。

私钥备份排除意味着换机后需重新配对。普通 APK 原位更新仍保留应用私有目录。麦克风上行仍是原有未加密 UDP，不应当作为公网传输方案。

Android 17 权限依据：[Android 局域网权限](https://developer.android.com/privacy-and-security/local-network-permission)；麦克风服务依据：[前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types#microphone)。

## 尚需实际设备验证

- Windows + macOS 两台 Sunshine 同时串流、Wi-Fi 拥塞、断网/恢复、手机锁屏长时间运行。
- 蓝牙耳机/音箱、USB 输出切换和听感。
- 多小时稳定性、实际端到端延迟、耗电和满 32 路压力。
- 新配对与旧安装证书迁移在真实 Sunshine 上的完整流程。

这些项目不能由模拟器测试代替。本轮不会声称已经完成真实多机听感或延迟改善验证。`STATUS.md` 原有实测描述是历史记录，不代表本轮重新执行。

## 值得考虑的新增功能（本轮未实现）

1. 每台设备的实时缓冲、丢包、欠载和延迟诊断，帮助判断卡顿来自 Wi-Fi、主机还是输出设备。
2. 多设备混音预设与音量记忆，一键恢复常用设备组合。
3. 音频输出设备选择与断连后的回退规则，覆盖蓝牙耳机、音箱和 USB 声卡。
4. 带认证与加密的麦克风上行，以及 PC 端接收器的设备选择界面。

## 授权与发布记录

顶层现有 `LICENSE` 为 MIT；嵌入的 `moonlight-common-c/LICENSE.txt` 为 GPLv3，ENet 有独立 LICENSE，Java/JNI 和预编译 Opus/OpenSSL 也有上游来源。原许可证保留，顶层“MIT”不足以描述全部 APK 组件。v0.3.0 发布时已补充[第三方组件及许可说明](../THIRD_PARTY_NOTICES.md)、APK 内许可证文本、原生依赖版本与源码归档；继承的预编译库尚不具备完整原始构建溯源，边界见[发布构建记录](RELEASE.md)。

## 本轮验证记录

- JVM：23 项测试通过，覆盖会话取消、重连上限、超时、槽位复用、静音/音量、协议分片和异常包、立体声漂移、证书匹配、版本比较。
- Python：10 项测试通过，覆盖乱序、去重、序号回绕、有限 PLC、大跳重同步和内存上限；另执行实际 UDP 接收冒烟测试，4 个有效包、1 个重复包、1 次重同步，正常退出；关闭后重启麦克风的 SSRC/端口变化也已验证。
- Android：9 项实际运行测试，覆盖 LocalSocket 取消/复用/首帧/EOF、Activity 重建、后台麦克风、native 快速启停、PIN HTTP 取消和自签名 mTLS 证书迁移及连续握手。9 项全部通过，0 failures / 0 errors；后台麦克风测试会先排空旧 UDP 包，再等待背景中产生的新包。
- Debug APK、未签名 Release APK 构建通过；Lint 为 0 errors、30 warnings。保留的警告主要为依赖版本、兼容 API、图标及调试 TLS 探针等，未用 baseline 隐藏错误。
- 系统 Python 的无 Opus 模式验证通过；另复用本地既有虚拟环境运行真实 Opus 编码 → UDP → 解码 → WAV 测试：两次 SSRC 会话共 40 包、0 丢包、1 次重同步，输出 48kHz/mono/16bit、19200 帧，样本非静音。未开启扬声器播放，未做听感验证。

回归验证中实际发现并修复的路径：Android 17 无局域网授权时弹出 NSD 系统选择器；监听 socket 仅 close 时仍有阻塞 accept 引用；无效 codec 能力值触发 native 断言；自签名证书的 OkHttp 清理后证书链为空，导致旧安装证书迁移未保存。证书迁移现在保存 TrustManager 实际验证过的 TLS 叶证书，本地自签名 mTLS 连续两次握手测试已通过。

界面与调试冒烟：在 API 37 模拟器实际拒绝局域网权限后，应用保持运行、显示重新授权入口，未弹出 NSD 选择器；恢复授权后可正常进入主界面。复查后同步修正无授权时的扫描提示，并在扫描停止时清空过期发现结果。调试 CLI 的 `ping`、`status` 成功；Release APK 的 manifest `debuggable=false`。已移除不再由服务端支持的旧 `audiomode` CLI 子命令，当前版本使用自动延迟调节。

最终构建日志保存于 `echolink-app/app/build/reports/final-verification.log`；Android 最终运行报告保存于 `echolink-app/app/build/reports/final-android-verification.log`。合计 42 项回归测试（23 JVM + 9 Android + 10 Python），没有在本轮执行 GitHub CI，也没有向实体设备安装或对 GitHub 推送。
