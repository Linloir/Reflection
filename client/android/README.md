# Reflection Android

将 Android 平板上的笔事件传给 Reflection 宿主机。客户端采用原生 Java / Android API，最低 Android 5.0（API 21）。界面只是演示和诊断入口；采集、协议、连接和配置分别位于独立模块，后续可以替换界面。

## 能力与边界

| 能力 | 实现 |
| --- | --- |
| 笔位置 | 在实际书写区归一化为 `[0,1]`；显示器、区域、旋转和坐标模式由宿主机映射 |
| 压感 | 使用设备声明的 pressure range 归一化；不将连续值量化为 4096 档 |
| 倾斜 | 从 Android tilt / orientation 转成 X/Y 角度；仅在设备提供且读数有效时使用 |
| 橡皮擦 | `TOOL_TYPE_ERASER`；侧键映射为两个独立按钮位 |
| 悬停 | hover enter / move / exit；支持设备才会产生相应事件 |
| 历史采样 | 读取 `MotionEvent` 历史点；落笔请求 unbuffered dispatch |
| 防误触 | 只接受 stylus / eraser 工具，不把手指触摸当成笔 |
| 防抖 | 默认启用自适应坐标平滑；原样开关、可选静止噪声校准，预览与传输一致 |
| USB | 通过 ADB reverse 将本机 TCP 连接转发到宿主机 |
| Wi-Fi / 有线 IP / 蓝牙 PAN | 使用同一 TCP 通道；Wi-Fi 要求客户端可达宿主机监听地址 |
| 蓝牙 Classic | 连接已配对宿主机发布的 Reflection RFCOMM 服务；使用相同认证协议 |

最低版本是 API 合约和编译目标，并不代表每款 Android 5.0 设备都经过实机测试。设备是否上报压力、倾斜、悬停和橡皮擦，由硬件、固件和 Android 驱动共同决定。Android 9 文石 Note2 是当前开发验证设备；其他设备和 RFCOMM 硬件链路仍需要分别验证。

客户端本身不会让宿主机应用自动获得数位板支持；具体兼容性由宿主机输入后端和目标应用共同决定。请同时阅读根目录文档中的平台兼容性说明。

## 构建

### 只使用 Android SDK 工具

需要 JDK、Python 3、Android SDK Platform 35 或更高，以及 Android SDK Build-Tools。脚本通过 `ANDROID_SDK_ROOT` / `ANDROID_HOME` 查找 SDK；macOS 也支持 Android SDK 的常规安装位置。

```sh
./scripts/build-sdk.sh
```

产物为 `build/sdk/reflection-debug.apk`。脚本使用持久的标准 Android 调试签名 `~/.android/debug.keystore`，与同机 Gradle 默认配置一致；清理 `build/` 不会更换证书。也可通过 `REFLECTION_DEBUG_KEYSTORE` 指定已有调试密钥。构建产物和密钥均不应进入版本库。跨机器更新时需要使用同一签名密钥，否则 Android 会拒绝覆盖安装。该方式不需要 Gradle 下载依赖，适合先调试核心能力。

### Android Studio / Gradle

用 Android Studio 打开本目录。项目使用 Android Gradle Plugin 8.10.1、Gradle 8.11.1 或兼容版本、JDK 17 和 SDK Platform 35。仓库没有提交 Gradle 发行包；使用已安装的 Gradle：

```sh
gradle :app:assembleDebug
```

## 安装和 USB 连接

先在 Android 设置中启用 USB 调试，并在设备上确认宿主机授权。USB 调试依赖 Android 的 ADB，不是无需驱动的 USB HID 数位板模式。

```sh
./scripts/reflection-android install
./scripts/reflection-android open
adb reverse tcp:24819 tcp:24819
```

在宿主机启动 Reflection 服务并取得配对 token 后，将 token 保存到权限适当的本地文本文件。以下命令只传入 token 文件路径，辅助脚本不打印 token 内容：

```sh
./scripts/reflection-android configure --mode tcp --host 127.0.0.1 --port 24819 --token-file /path/to/token.txt
./scripts/reflection-android connect
./scripts/reflection-android status
```

多个 ADB 设备同时连接时，在子命令前加 `--serial DEVICE_SERIAL`。界面上的 Connect 使用同一套控制器和配置。

## Wi-Fi

将宿主机配置为监听可达的局域网地址，并仅开放必要的防火墙端口，然后使用宿主机 IP：

```sh
./scripts/reflection-android configure --mode tcp --host 192.168.1.10 --port 24819 --token-file /path/to/token.txt
./scripts/reflection-android connect
```

每个会话执行双向 HMAC challenge-response，之后每帧独立认证。token 不作为明文网络字段发送，但坐标等内容没有加密。这不是 TLS 或面向不可信公网的连接方式；需要保密性时使用可信的加密网络。不要将监听端口直接暴露到互联网。

## 蓝牙 RFCOMM

1. 在 Android 与宿主机的系统设置中完成蓝牙配对。
2. 在宿主机启用 Reflection RFCOMM 服务。服务 UUID 为 `7b011e5a-8b31-4c40-8d7a-3e6c6dcbb758`。
3. Android 12 或更高版本需要 Nearby devices / `BLUETOOTH_CONNECT` 权限。UI 选择 Bluetooth 后点击 Connect 会请求权限；授权后再点击 Connect。CLI 也可授权：

```sh
./scripts/reflection-android grant-bluetooth
./scripts/reflection-android paired
./scripts/reflection-android configure --mode bluetooth --bluetooth AA:BB:CC:DD:EE:FF --token-file /path/to/token.txt
./scripts/reflection-android connect
```

Android 11 及更低版本不需要 `grant-bluetooth`。客户端不扫描附近设备，也不尝试在后台开启蓝牙；它只连接已配对设备。RFCOMM 使用安全的系统蓝牙连接，应用层仍执行同样的 Reflection 认证。该通道已实现，但不能用 TCP 测试结果代替蓝牙实机验证。宿主机系统是否提供可用的 RFCOMM 服务，见对应平台 README。

## CLI 与诊断

| 命令 | 用途 |
| --- | --- |
| `install` / `open` | 安装调试 APK / 打开采集界面 |
| `configure` | 更新地址、端口、token 或传输模式；配置变化会断开当前连接 |
| `connect` / `disconnect` | 建立连接 / 释放连接 |
| `status` | JSON 连接状态、样本数、发送数、队列溢出数和心跳往返耗时 |
| `devices` | Android 输入设备的 axis range、分辨率等声明 |
| `paired` | 列出系统已经配对的蓝牙设备 |
| `preview on` / `preview off` | 开关普通 Android 本地笔迹预览 |
| `stabilization on` / `off` / `status` | 开关防抖或读取状态；关闭后坐标原样通过 |
| `calibration start` / `finish` / `cancel` / `reset` | 开始、完成、取消采集或删除校准数据 |
| `clear` | 清空演示界面的本地预览 |
| `selftest` | 明确生成六个 synthetic 笔样本，测试 Android 事件适配与网络路径 |

CLI 使用显式 `ControlReceiver`，接收器要求平台 `android.permission.DUMP`，普通第三方应用没有该权限。ADB shell 与系统可以调用；配置、状态和 UI 共用 `Controller`，没有另一套隐藏行为。

默认防抖不要求校准。界面提供 Reduce pen jitter、Calibrate、Finish、Cancel、
Reset，和 CLI 调用相同控制器。校准时的笔输入留在平板，旧宿主笔接触会释放；
有效的新结果保存成功后才替换旧结果。详细步骤与细节保留边界见
[防抖与校准](../../docs/stabilization.md)。

不使用辅助脚本时，也可以直接执行：

```sh
adb shell am broadcast -n io.github.linloir.reflection/.control.ControlReceiver --es command status
adb shell am broadcast -n io.github.linloir.reflection/.control.ControlReceiver --es command devices
adb shell am broadcast -n io.github.linloir.reflection/.control.ControlReceiver --es command selftest
```

`selftest` 会向当前连接发送落笔、移动、不同压力和抬笔。它可能使宿主机当前应用收到输入，因此仅在专门的测试画布或诊断后端中运行。返回的 `synthetic: true` 和 `hardwareVerified: false` 表示它不验证物理笔、实际延迟或设备压感。

`status` 中的 `rttMicros` 是网络心跳往返耗时，不能当作笔尖到显示的端到端延迟。`invalidTiltSamples`、`lastRawTilt`、`lastRawOrientation`、`tiltReliable` 用于识别设备上报的异常倾斜读数。超出 Android 约定范围或非有限的原始倾斜读数会使当前样本的倾斜退化为零并计数，不会将错误角度硬夹成有效笔刷倾斜，也不会因此断开整条笔输入链路。

## 电子墨水屏

默认关闭本地预览，以避免普通 Android 重绘给电子墨水屏带来的额外刷新。关闭预览不会关闭笔采集，宿主机仍然收到全部样本。打开预览后使用通用 Android Canvas，不承诺 BOOX 原生笔记应用的笔尖跟随速度，也没有强行切换设备刷新模式。

Onyx 的低延迟本地墨迹 SDK 是后续独立渲染适配层的候选。当前实现不依赖厂商 SDK，因此其他 Android 平板仍能使用标准笔输入路径。完整评估要区分本地墨迹显示延迟、Android 事件采集延迟、网络延迟、宿主机注入延迟和目标应用显示延迟。

## 模块与稳定性策略

- `core/`：不可变笔样本、自适应防抖与噪声校准、二进制协议、HMAC；不依赖 Android UI。
- `input/`：将 `MotionEvent` 和历史样本转换为标准笔样本，验证设备数值范围。
- `transport/`：TCP / RFCOMM 字节流、会话认证、心跳、有界队列和重连。
- `control/`：持久配置和公共控制接口，UI 与 CLI 都经此入口。
- `ui/`：可替换的演示界面与普通 Canvas 本地预览。

采集不执行网络 I/O。256 个样本的队列满时断开并清空，避免持续累积延迟或静默丢失关键落笔/抬笔边沿；宿主机必须在断开时释放笔状态。重连后不会续传缺少落笔的旧笔画。每 500ms 发送心跳；握手与读写均有截止时间，蓝牙阻塞 I/O 也会由 watchdog 关闭。离开采集界面或书写区域大小改变会发送取消，避免持续按下。

这是前台采集工具：只有可见书写区接收笔输入，不进行后台屏幕捕获。应用进入后台后，Android 可能终止进程；宿主机的超时释放机制仍然是必要保障。

## 测试

```sh
./scripts/test-core.sh
./scripts/build-sdk.sh
```

纯 Java 测试验证 HMAC 标准向量、二进制字节序、工具/相位编码、认证篡改拒绝以及帧长度上限。独立 Python 测试服务还验证真实 TCP 握手、六个笔帧按序送达，以及已认证心跳的重放拒绝。输入适配器回归测试使用仅供 JVM 测试的 Android 事件替身，检查悬停侧键在切后台时的释放、重复取消和异常厂商倾斜；TCP 测试也检查新会话不会转发缺少落笔的移动或抬笔。构建和协议测试不替代实际 Android 版本、物理笔、蓝牙或宿主机绘画应用验证。

## 官方参考

- Android `MotionEvent`：https://developer.android.com/reference/android/view/MotionEvent
- Android stylus 输入：https://developer.android.com/develop/ui/views/touch-and-input/stylus-input
- Android Bluetooth 权限：https://developer.android.com/develop/connectivity/bluetooth/bt-permissions
- Android Bluetooth 连接：https://developer.android.com/develop/connectivity/bluetooth/connect-bluetooth-devices
- Onyx 官方 SDK 示例：https://github.com/onyx-intl/OnyxAndroidDemo
