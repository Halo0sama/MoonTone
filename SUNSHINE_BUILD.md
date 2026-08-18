# 构建带托盘修复的 Sunshine（macOS）

官方预编译版 Sunshine 在 macOS 27 上存在托盘线程安全问题：会话启动时在非主线程调用 `NSStatusItem setMenu:`，会导致崩溃。

本项目的 Android 客户端不依赖这个修复；但如果你在 macOS 上想要菜单栏图标，需要从源码构建并应用一个很小的补丁。

## 补丁内容

文件：`third-party/tray/src/tray_darwin.m`

在 `tray_update()` 中，把直接操作 `NSStatusItem` 的代码派发到主线程：

```objc
dispatch_async(dispatch_get_main_queue(), ^{
    // 原有 tray_update 的 UI 操作
});
```

## 构建

参考 Sunshine 官方构建流程：

```bash
git clone --recursive https://github.com/LizardByte/Sunshine.git
cd Sunshine
# 应用上面的 tray_darwin.m 补丁
cmake -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build
```

构建产物即带托盘修复的 Sunshine。

## 配置

```ini
# ~/.config/sunshine/sunshine.conf
system_tray = true
```

如果不需要托盘图标，官方预编译版也可以直接使用：

```ini
system_tray = false
```
