# MoonTone Android 工程

项目功能与使用说明见[根目录 README](../README.md)，当前开发入口见[开发指南](../docs/DEVELOPMENT.md)。目录名 `echolink-app` 是历史名称，应用包名为 `com.halo.moontone`。

## 构建

使用 JDK 21、Android SDK 37、Build Tools 36.0.0、NDK 27.0.12077973。Gradle Wrapper 和原生依赖已随仓库保存。

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest :app:lintDebug
# 连接 arm64 Android 模拟器或设备后，运行实际 LocalSocket/生命周期测试
ANDROID_SERIAL=emulator-5556 ./gradlew :app:connectedDebugAndroidTest
```

`local.properties` 可配置 `sdk.dir`，也可以使用 `ANDROID_HOME`。当前只构建 arm64-v8a，最低 Android 7.0 / API 24。

Debug APK：`app/build/outputs/apk/debug/app-debug.apk`。Release 构建命令为 `./gradlew :app:assembleRelease`，签名由发布者配置；仓库不包含发布密钥。

## 代码入口

- `MoonToneApp.kt`：应用级控制器、播放服务所有权、Worker 进程隔离。
- `control/MoonToneController.kt`：单路配对与播放、麦克风服务、调试状态。
- `connection/MoonTonePairing.kt`：PIN 配对、mTLS、Sunshine 会话启动。
- `connection/MoonToneConnection.kt`：串行管理进程内唯一的 native 连接。
- `multi/MultiController.kt`：多路会话监督、取消、超时、三次重连。
- `multi/MultiConnectionManager.kt`：Worker 槽位、LocalSocket 生命周期。
- `multi/AudioMixer.kt` / `StereoDriftBuffer.kt`：PCM 接收、立体声漂移补偿、混音。
- `audio/MoonToneMicrophoneService.kt`：麦克风前台服务。
- `audio/AdaptivePcmBuffer.kt` / `PlaybackBufferTuner.kt`：单路和混音共用的抖动缓冲、平滑时间校正及 AudioTrack 有效缓冲调节。
- `data/AppPreferences.kt` / `HostAudioPreferences.kt`：全局默认、启动更新检查与兼容旧格式的设备声音例外。
- `ui/MainActivity.kt`：Activity、运行时权限和更新检查。
- `ui/MoonToneScreen.kt`：页面导航、设备发现、单设备播放/多设备混音首页。
- `ui/SettingsScreen.kt`：集中设置页与设备声音例外。
- `ui/AdvancedScreens.kt`：手动 RTSP 连接、日志和音频信息。

单路播放和多路混音共用自适应延迟策略；`AdaptiveLatencyController` 仅采集单路调试状态。不要把源码中的缓冲参数直接当作实测端到端延迟。详见[延迟优化记录](../docs/LATENCY.md)。
