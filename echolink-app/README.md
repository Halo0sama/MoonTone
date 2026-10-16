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
- `ui/MainActivity.kt`：Compose 界面、运行时权限和设备列表。

单路播放和多路混音是两条现有管线，自动延迟控制目前接在单路播放器上。不要把源码中的缓冲参数直接当作实测端到端延迟。
