# 发布构建记录

## v0.4.0

版本号为 `0.4.0`，Android `versionCode` 为 `4`。源码以 Git 标签 `v0.4.0` 对应；发布附件包含 arm64 APK、源码归档与 `SHA256SUMS.txt`。

本轮为 Material 3 Expressive 界面重构，沿用既有发布流程：使用 JDK 21、SDK 37、Build Tools 36.0.0、NDK 27.0.12077973、Gradle 9.8.0、AGP 9.4.1 构建 Release APK，通过 Android SDK 的 `zipalign` 与 `apksigner` 完成对齐、签名与验证。签名身份沿用 v0.2.0/v0.3.0 的本地 Android debug keystore；发布的是 `debuggable=false` 的 Release 构建，不是 Debug APK。

发布前验证：34 项 JVM 单元测试通过，Lint 0 errors，Debug/Release 构建通过；在 Android 17 模拟器与实体 Android 17 arm64 手机上验证浅色/深色、动态取色、主题切换、设备分组、底部弹层等界面状态。本轮未重新执行 Android instrumented 测试与真实 Sunshine 多机串流的端到端听感测试，相关限制见[发布说明](releases/v0.4.0.md)。

签名、版本号、arm64 ABI、不可调试标志与 ZIP 对齐核验记录保存在本地 `echolink-app/app/build/reports/release-v0.4.0/`，不进入仓库或发布附件。

## v0.3.0

版本号为 `0.3.0`，Android `versionCode` 为 `3`。源码以 Git 标签 `v0.3.0` 对应；发布附件包含 arm64 APK、源码归档与 `SHA256SUMS.txt`。

构建使用 JDK 21、SDK 37、Build Tools 36.0.0、NDK 27.0.12077973、Gradle 9.8.0、AGP 9.4.1，参数来自仓库已有配置。本地生成 Release APK 后通过 Android SDK 的 `zipalign` 和 `apksigner` 完成对齐、签名与验证。

为保持覆盖升级能力，沿用已发布 v0.2.0 的签名身份（现有本地 Android debug keystore）。实际发布的是 `debuggable=false` 的 Release 构建，不是 Debug APK；此签名延续不代表引入了新的生产签名密钥。签名文件、密码和 GitHub 令牌均不进入仓库或发布附件。

对应源码归档由 `git archive` 从发布标签生成，包含仓库内 Java/Kotlin、JNI/Moonlight/ENet/Reed-Solomon 源码、构建脚本，以及原有 Opus/OpenSSL 静态库与头文件。原生依赖的原始构建提交未由早期导入记录完整保留；已确认的版本、上游来源与许可证见[第三方说明](../THIRD_PARTY_NOTICES.md)。不宣称这些静态库已从上游源码重新构建或具有逐字节可复现性。

发布前验证：34 项 JVM 单元测试、16 项 Android 17 arm64 运行测试、10 项 Python 接收器测试全部通过，0 failures / 0 errors / 0 skipped；Debug 和 Release 构建通过；Lint 为 0 errors、30 warnings。

GitHub CI 显式初始化 Android SDK 命令行工具，再安装指定 SDK/NDK，避免依赖运行器预装 `sdkmanager`。[远端检查记录](https://github.com/Halo0sama/MoonTone/actions/workflows/android.yml?query=branch%3Amain)。

专用模拟器先安装 GitHub 已发布 v0.2.0 APK，写入测试设备及旧格式主机声音偏好，再原位安装签名后的 v0.3.0 Release。保存的设备、设备声音例外、设置页入口和唯一全局声音开关均核验通过。APK 签名与旧发布包一致；版本号、arm64 ABI、不可调试标志和 16 KB ZIP 对齐均已验证。ZIP 对齐检查不等同于对所有设备页大小或真实耳机输出的验证。

日志位于本地 `echolink-app/app/build/reports/release-v0.3.0/`；不将本地报告目录、凭据辅助脚本、旧安装包或签名密钥加入发布附件。手机此前已安装设置页构建，本次发布检查没有再次安装或修改实体手机。
