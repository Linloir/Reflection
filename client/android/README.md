# Reflection Android

将 Android 平板上的笔事件传给 Reflection 宿主机。客户端采用原生 Java / Android API，最低 Android 5.0（API 21）。界面只是演示和诊断入口；采集、协议、连接和配置分别位于独立模块，后续可以替换界面。

## 能力与边界

| 能力 | 实现 |
| --- | --- |
| 笔位置 | 在实际书写区归一化为 `[0,1]`；显示器、区域、旋转和坐标模式由宿主机映射 |
| 压感 | 使用设备声明的 pressure range 归一化；不将连续值量化为 4096 档 |
| 倾斜 | Auto / Standard / Off；标准轴转换、异常后有界恢复，以及限定 Note2 输入的一维兼容恢复 |
| 橡皮擦 | `TOOL_TYPE_ERASER`；侧键映射为两个独立按钮位 |
| 悬停 | hover enter / move / exit；支持设备才会产生相应事件 |
| 历史采样 | 逐点读取 `MotionEvent` 历史；Android 11+ 仅在笔/橡皮擦落笔时请求 unbuffered dispatch，旧系统保留正常批处理 |
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
| `tilt [auto\|standard\|off\|status]` | 设置并保存倾斜模式；省略参数时读取状态 |
| `tilt reset` | 重新识别当前输入格式，不改变模式、累计计数或防抖校准 |
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

`status` 中的 `rttMicros` 是网络心跳往返耗时，不能当作笔尖到显示的端到端延迟。

### 倾斜可靠性

倾斜模式通过 Android UI 或 CLI 设置并持久保存，不属于宿主机 profile：

| 模式 | 行为 |
| --- | --- |
| `auto`（默认） | 普通设备使用标准 Android 倾斜；仅对匹配已验证设备及输入特征的 Note2 启用一维恢复 |
| `standard` | 仅使用标准 `AXIS_TILT` / `AXIS_ORIENTATION`，可用于对照兼容恢复前的输入 |
| `off` | 保留坐标、压力和按钮，倾斜输出为零 |

点击界面顶部的 **Tilt** 可选择模式并查看当前采样、可用轴和恢复诊断；设置界面不改变
书写区的位置。CLI 调用相同控制器：

```sh
./scripts/reflection-android tilt auto
./scripts/reflection-android tilt status
```

**Retry detection** 或 `./scripts/reflection-android tilt reset` 仅重新开始输入格式
识别；模式、累计计数和防抖校准保持不变。计数应比较操作前后的差值。

`lastRawTilt`、`lastRawOrientation` 保留 Android 交给应用的弧度值，尚未经过兼容处理。
Android 规定 tilt 为 `0..π/2`，orientation 为 `-π..π`。标准路径检查数据范围；
异常后采用有界恢复，后续输入持续正常时可在同一接近周期内恢复，不再因一次错误一直
锁定为零。坐标、压力、按钮和历史采样继续发送，不等待倾斜恢复。

已测 Note2 固件 3.5（构建 11030）的对应内核已确认存在报告解析错误：驱动收到完整
笔报告后，把两个单字节分别作为倾斜轴上报，没有解析末尾四个字节。实机采样表明
这两个上报值实际组成一条角度通道，Android 按两个独立倾角处理后会得到异常结果。
Auto 使用限定设备的兼容解码恢复这条一维通道，并结合系统输入方向
表达为传输角度，无需用户先校准。**这不等于恢复了完整二维倾斜**：当前输入链路没有
提供第二条独立角度，不能根据笔迹方向、压力或悬停距离补造。实机定向检查确认，在已测
竖屏方向，这条通道对应屏幕上下方向的倾斜，屏幕左右方向仍缺失。其他旋转的转换已做
单元测试，仍需分别完成实机验收。不能把该兼容方案推广为所有 Note2 固件或其他 BOOX
型号都适用；不匹配的设备保持标准路径。
当前测试固件暴露的可恢复角度以 1° 步进；这不代表笔传感器的原始分辨率，
软件不能增加该输入链路实际提供的角度细节。

Auto 需要足够的输入格式证据和明确的方向判断，识别过程中可能暂时显示等待；
不能仅凭设备名称或一个异常值启用恢复。已识别结果在笔离开、重新进入后保留，
需要重新检查时使用 Retry detection。

诊断应同时查看原始值、`tiltStatus`、`tiltReliable`、异常/降级计数和最终
`lastSample.tiltX/tiltY`，区分标准有效输入、一维恢复、恢复等待、不可用和关闭。
`tiltReliable` 不能单独证明具有完整二维信息。不要仅因输出为零就判断没有硬件倾斜；
一维恢复缺少的方向、直立姿态或关闭输出也会出现零。宿主协议 v1 不携带逐样本可靠性
状态，因此完整诊断保留在平板端。

已检查的 Onyx Pen SDK 虽有带 `tiltX/tiltY` 的回调，但仍读取同一组内核事件，
无法取得驱动未上报的报告字节。完整修复需要设备驱动正确解析并上报原始报告；
被丢弃字节的实际内容尚未直接验证，不能据此断言硬件缺少或已经提供完整第二轴。
需要稳定的直立笔行为时可选 `off`，或在宿主 profile 中关闭倾斜；这两项不会关闭压感。
详细边界见 [兼容性说明](../../docs/compatibility.md)。

### 橡皮擦与侧键诊断

`lastRawToolType` 保留 Android 工具类型，`4` 是 `TOOL_TYPE_ERASER`，`2` 是普通 stylus；`eraserSamples` 统计接收到的橡皮擦样本。`lastRawButtons` 和 `barrelButtonSamples` 单独记录侧键；侧键不会自动伪装成笔尾橡皮擦。`inputDevice` 给出最近事件的设备名称、厂商/产品标识、设备 ID 和 source，便于确认采集来自哪支笔。

排查时比较操作前后的计数：翻转具有橡皮擦的笔后，如果 `eraserSamples` 增加且 `lastSample.tool` 为 `2`，客户端已发送橡皮擦工具；随后应检查宿主机输入后端与目标应用的工具支持。如果只增加侧键计数，硬件交给 Android 的是按钮，应按按钮映射排查，不能据此认定存在笔尾橡皮擦。输入设备声明支持 eraser 并不能证明实际使用的笔具备橡皮擦，目标应用也可能不支持系统笔工具切换。计数是当前应用进程内的采样数，包含悬停与历史采样，不代表笔画数。

Android 的标准输入层分别将 Linux `BTN_TOOL_RUBBER` 解释为 eraser，将 `BTN_TOOL_PEN` / `BTN_TOOL_BRUSH` 等解释为 stylus，并把 `BTN_STYLUS` / `BTN_STYLUS2` 解释为独立按钮。客户端沿用这些语义，不使用设备名称猜测笔的能力。

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

Android 10 及更早版本使用正常的系统输入批处理，避免旧版 `ViewRootImpl` 在切换
立即交付模式时留下待处理输入，进而触发“没有响应”。历史点仍按原始时间和顺序逐点
处理，不将一批输入合并为最后一个位置。批处理可能增加等待系统帧调度的延迟，
不会主动降低笔的采样精度；本地预览关闭时也保持相同采集路径。

这是前台采集工具：只有可见书写区接收笔输入，不进行后台屏幕捕获。应用进入后台后，Android 可能终止进程；宿主机的超时释放机制仍然是必要保障。

## 测试

```sh
./scripts/test-core.sh
./scripts/build-sdk.sh
```

纯 Java 测试验证 HMAC 标准向量、二进制字节序、工具/相位编码、认证篡改拒绝以及帧长度上限。独立 Python 测试服务还验证真实 TCP 握手、六个笔帧按序送达，以及已认证心跳的重放拒绝。输入适配器回归测试使用仅供 JVM 测试的 Android 事件替身，检查快速笔画历史点、逐历史点的倾斜、悬停侧键释放、异常倾斜的有界恢复、设备切换和橡皮擦工具透传；TCP 测试也检查新会话不会转发缺少落笔的移动或抬笔。倾斜验收还应覆盖模式持久化、Note2 一维恢复的匹配限制和未知设备的标准路径，不能用合法输出数值代替完整二维物理角度验证。构建和协议测试不替代实际 Android 版本、物理笔、蓝牙或宿主机绘画应用验证。

## 官方参考

- Android `MotionEvent`：https://developer.android.com/reference/android/view/MotionEvent
- AOSP Android 9 输入映射（`TouchButtonAccumulator`、倾斜换算）：https://android.googlesource.com/platform/frameworks/native/+/android-9.0.0_r1/services/inputflinger/InputReader.cpp
- Android stylus 输入：https://developer.android.com/develop/ui/views/touch-and-input/stylus-input
- Android Bluetooth 权限：https://developer.android.com/develop/connectivity/bluetooth/bt-permissions
- Android Bluetooth 连接：https://developer.android.com/develop/connectivity/bluetooth/connect-bluetooth-devices
- Onyx 官方 SDK 示例：https://github.com/onyx-intl/OnyxAndroidDemo
