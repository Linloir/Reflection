# Reflection

把 Android 平板或电子纸设备用作电脑的手写输入设备：在线会议时画图，
在白板和绘画软件中书写，也可以把草图保存后交给开发工具。

Reflection 优先实现笔输入、低开销传输、坐标映射和系统集成。
客户端界面目前是用于验证核心能力的简单面板；采集、传输、配置和宿主输入
均独立于界面。项目使用 MIT 许可证。

**当前是可构建、可调试的早期实现，不是完整的虚拟数位板驱动。**
macOS 使用 Quartz，Windows 使用 Windows Ink / Synthetic Pointer。
仅接受 WinTab、原始 HID 或特定厂商驱动的应用，不在当前兼容保证之内。
具体能力和验证边界见 [兼容性说明](docs/compatibility.md)。

## 平台与能力

| 项目 | 当前实现 |
| --- | --- |
| Android 客户端 | 原生 Java，最低 Android 5.0 / API 21；已在 Note2 / Android 9 安装验证 |
| macOS 宿主 | Swift 核心 + SwiftUI，macOS 12+；桌面 UI、原生 CLI、Quartz 输入 |
| Windows 宿主 | C++17 核心 + WinUI 3，Windows 10 1809+；桌面 UI、原生 CLI、Windows Ink 输入 |
| iPad | 规划中的原生客户端；见 [iOS 接口规划](client/ios/README.md) |
| 笔数据 | 坐标、压感、倾斜、悬停、橡皮擦、侧键；依赖设备与目标应用 |
| 映射 | 绝对 / 相对模式、源区域、目标区域、旋转、输出坐标级数；macOS 另有自动等比映射 |
| 笔设置 | Gamma / 折线控制点 / 平滑压力曲线、倾斜开关与比例、多套 profile；UI/CLI 均可操作 |
| 防抖 | 电脑端自适应坐标平滑、异常跳点保护；桌面 UI 管理开关、强度、参数与噪声校准 |
| USB | ADB reverse，通过 USB 传输同一笔协议，需要 USB 调试授权 |
| Wi-Fi | TCP，双向认证、逐帧校验、低延迟发送 |
| 蓝牙 | 原生 Bluetooth Classic RFCOMM，三端代码已实现；尚待蓝牙实机互通验证 |
| 自动化 | 配对、安装、连接、配置、诊断均提供 CLI；状态输出为 JSON |

传输中保留归一化压力值，不主动压成 8 位。但宿主系统和应用可能进一步量化，
不能把设备的“4096 级压感”直接等同于所有应用最终收到的精度。

## 从 USB 开始

准备 Python 3.9+、Android SDK Platform-Tools，以及各平台的构建工具。
Android 编译需要 JDK 和 Android SDK；macOS 需要 Swift 工具链；
Windows 需要 Visual Studio C++ 工具与 CMake，桌面 UI 另需 .NET 10 SDK。
macOS 桌面 UI 需要含 macOS 26 或更新 SDK 的 Xcode / Command Line Tools 构建；
产物仍支持 macOS 12 起的系统。
详细要求分别见 [Android](client/android/README.md)、
[macOS](server/macos/README.md)、[Windows](server/windows/README.md)。

以下命令在仓库根目录运行。Windows 使用 `python reflection`，macOS / Linux
可使用 `python3 reflection` 或 `./reflection`。

```bash
git clone git@github.com:Linloir/Reflection.git
cd Reflection

python3 reflection build all
python3 reflection install android
```

初始化宿主配置。它会生成独立的随机配对密钥：

```bash
# macOS
python3 reflection server config init

# Windows
python reflection server init
```

在一个终端启动诊断服务。此模式接收和校验笔数据，**不控制电脑指针**：

```bash
python3 reflection server start --dry-run
```

在另一个终端配置设备。平板需要开启 USB 调试，并在设备上接受 ADB 授权。
配对命令直接读取宿主配置，不把密钥打印到终端：

```bash
python3 reflection usb connect
python3 reflection pair-android --connect
python3 reflection android status
```

打开客户端的笔输入区域即可采集手写数据。默认关闭平板本地预览，以减少电子纸
刷新造成的视觉干扰。可用 `python3 reflection android preview-on` 开启普通
Android Canvas 预览；这不等同于厂商原生笔记应用的快速墨迹。

确认链路正常后，停止诊断服务，再运行：

```bash
python3 reflection server doctor
python3 reflection server start
```

macOS 注入输入需要在“系统设置 → 隐私与安全性 → 辅助功能”授权实际运行的
宿主或终端；`doctor` 会报告是否具备权限。Windows 目标应用需要接受 Windows
Ink 输入，完整性级别不同的应用另有系统限制。诊断连接成功并不代表这些系统
权限或应用兼容性已经通过验证。

## 桌面界面

```bash
python3 reflection build ui
python3 reflection install ui
python3 reflection ui
```

macOS 安装到当前用户的 `Applications/Reflection.app`；Windows 安装完整
自包含应用到 `%LOCALAPPDATA%\Reflection\app`。构建产物可独立运行。
界面提供启动/停止、连接配置、可编辑压感曲线、映射、profiles 和诊断，
保存后可直接重启宿主应用新设置。macOS 26+ 使用系统 Liquid Glass，
旧版回退到原生材质；Windows 在支持的 Win11 系统使用 Mica，否则回退到主题背景。

界面与输入宿主是独立进程。CLI 与 UI 使用同一配置和控制接口，
可以在没有窗口的情况下完成全部设置。详见平台 README 的运行和验证范围。

## 常用 CLI

```bash
# 构建和安装宿主到当前用户的目录
python3 reflection build server
python3 reflection install server

# 查看配置、显示器、权限与能力
python3 reflection server config show
python3 reflection server displays
python3 reflection doctor

# Android 的所有主要 UI 操作都可通过 CLI 调用
python3 reflection android launch
python3 reflection android connect
python3 reflection android disconnect
python3 reflection android devices
python3 reflection android clear

# 测试用合成笔事件；请在 --dry-run 宿主上执行
python3 reflection android selftest

# 宿主与参考客户端的协议 RTT；先断开占用会话的平板
python3 reflection probe

# 运行协议和核心检查
python3 reflection test
```

宿主只允许一个活跃客户端。`probe` 不注入输入，测量的是调用该命令的机器与
宿主之间的网络往返耗时，不是平板笔尖到屏幕墨迹的完整延迟。

安装后的 `reflection-host` 可独立使用，不要求保留仓库或安装 Python。
仓库内的 `reflection` 是构建和跨设备操作工具；两个原生宿主的具体配置语法
在各自 README 中列出。

## 文档

- [连接方式](docs/connectivity.md)：USB、Wi-Fi、蓝牙和排障。
- [配置与 CLI](docs/configuration.md)：映射、配对、自动化和权限。
- [多屏映射与切换](docs/mapping.md)：全部屏幕、单屏、快捷键、平板联动和 Windows 笔位置提示。
- [笔设置与排障](docs/pen-settings.md)：压感曲线、profiles、分辨率含义、倾斜与橡皮擦。
- [笔输入防抖与校准](docs/stabilization.md)：默认防抖、细节保留、可选校准与控制命令。
- [兼容性与验证范围](docs/compatibility.md)：设备轴、宿主精度、应用边界。
- [架构](docs/architecture.md)：模块职责、数据流和原生选型。
- [协议](protocol/README.md)：版本、二进制布局、认证、心跳和错误处理。
- [测试](docs/testing.md)：单元检查、原生进程互通、实机验收和性能测量。
- [开发与贡献](CONTRIBUTING.md)：构建、目录约定和提交要求。
- [后续工作](docs/roadmap.md)：驱动、iPad、低延迟电子纸渲染和发布。
- [安全说明](SECURITY.md)：配对密钥、网络边界和漏洞报告。

## 仓库结构

```text
client/
  android/       Android 应用、采集核心和 CLI
  ios/           后续 iPad 客户端的接口规划
server/
  macos/         Swift 核心、SwiftUI、CLI、Quartz 和 RFCOMM
  windows/       C++ 核心、WinUI 3、CLI、Windows Ink 和 RFCOMM
protocol/        共享协议规范与跨语言测试向量
tools/           配对、安装、诊断和参考协议工具
tests/           跨平台协议与工具测试
docs/            面向使用者与贡献者的项目文档
```

所有子项目由根 Git 仓库统一管理，不使用子仓库。
本地密钥、捕获数据、构建产物和临时调试记录不应提交。

作者：Linloir · `3145078758@qq.com`

仓库：`git@github.com:Linloir/Reflection.git`

许可证：[MIT](LICENSE)
