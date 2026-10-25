# Reflection Windows Host

Windows 宿主端使用原生 C++17、WinSock、BCrypt 和 Windows Synthetic Pointer
API，将平板笔事件作为 `PT_PEN` 输入送入当前桌面。核心库不依赖窗口或 UI
框架；CLI 是当前的完整控制入口，后续桌面 UI 可以复用相同核心。

## 系统要求与兼容范围

- Windows 10 1809 或更新版本、Windows 11；在交互式用户会话中运行。
- USB 模式需要 Android Platform Tools 和已授权的 USB 调试。
- Wi-Fi 模式需要平板可以访问宿主机的监听 IPv4 地址及端口。
- 蓝牙模式需要支持 Bluetooth Classic RFCOMM 的适配器、驱动和已配对设备。
- 目标绘画程序需要支持 Windows Ink / Pointer 输入。只使用 WinTab 的应用
  不在当前后端的支持范围内。部分程序需要在自身设置中启用 Windows Ink。

输入保留平板提供的归一化压力；Windows 的 `POINTER_PEN_INFO.pressure` 范围
是 **0..1024**，因此这一后端不能承诺将 4096 个压力档位原样呈现给应用。
倾斜映射为 Windows 的 -90..90 整数角度，支持悬停、笔尖、橡皮擦和主侧键。
协议中的第二侧键和悬停距离会接收并验证，但当前系统后端没有对应输出。
不支持触摸手势、双指缩放、多笔同时输入或硬件笔旋转轴。

管理员权限、受保护窗口、安全桌面、远程会话和各个应用的输入策略会影响
注入结果。`doctor` 检查 API 可用性，并不等于目标程序已经通过实际绘图测试。
此实现不是系统服务，也不在登录界面提供输入。

## 构建

安装 Visual Studio 的“使用 C++ 的桌面开发”、Windows SDK 和 CMake。
在仓库根目录的 Developer PowerShell 中运行：

```powershell
cmake -S server/windows -B server/windows/build -A x64
cmake --build server/windows/build --config Release
ctest --test-dir server/windows/build -C Release --output-on-failure
```

程序位于 `server/windows/build/Release/reflection-host.exe`。也可使用 CMake
的安装功能安装到自己的工具目录：

```powershell
cmake --install server/windows/build --config Release --prefix "$env:LOCALAPPDATA\Reflection"
```

安装目录的 `bin` 加入 `PATH` 后，可以直接执行 `reflection-host`。不需要额外
运行时包、内核驱动或后台安装器。蓝牙使用 Windows 自带协议栈。

## CLI

以下示例假设 `reflection-host` 已在 `PATH` 中：

```powershell
reflection-host init
reflection-host doctor
reflection-host displays
reflection-host config show
reflection-host config validate
reflection-host serve --dry-run
```

`init` 生成随机配对密钥，已有配置不会被覆盖。默认配置路径为
`%LOCALAPPDATA%\Reflection\server.conf`，也可在任何命令中使用
`--config PATH` 指定独立配置。配置文件是 `key=value` 文本。

`serve --dry-run` 接收、认证、验证、映射事件并输出笔划边界诊断，不操作
系统指针。去掉 `--dry-run` 后启用真实笔输入。`start` 是 `serve` 的别名，
两者都在前台运行，按 Ctrl+C 停止。当前不包含服务安装或后台守护进程。

```powershell
reflection-host config show --secrets
reflection-host serve
```

只有显式使用 `--secrets` 才会显示配对密钥，用于配置平板。不要把这份输出
写入公共日志。普通诊断和连接日志不会包含密钥。配置命令成功返回 0，错误
返回非零；设置、诊断和事件日志使用 JSON，便于脚本和 Agent 解析。
配置修改在下次启动服务时生效。

### USB

保持默认监听地址 `127.0.0.1`，运行：

```powershell
adb reverse tcp:24819 tcp:24819
reflection-host serve
```

平板端使用 TCP 地址 `127.0.0.1:24819` 和相同配对密钥。USB 是通过 ADB
reverse 转发有序字节流，不要求 Android 支持 USB HID gadget。
首次连接需要在平板上确认 USB 调试授权；部分设备也需要厂商 USB 驱动。
拔线后宿主端会在断开或两秒超时后释放笔状态。

### Wi-Fi

将 `bind` 改为宿主机局域网 IPv4 地址，或按需绑定所有 IPv4 接口：

```powershell
reflection-host config set bind 0.0.0.0
reflection-host serve
```

平板配置宿主机局域网地址和端口。Windows 防火墙需要允许该可执行程序或
指定端口的入站连接；建议只允许专用网络和所需设备地址。Reflection 不会
自动修改防火墙规则。默认端口为 `24819`，通过 `config set port 24819` 修改。

TCP 具有双向认证和逐帧完整性检查，但**没有内容加密**；坐标和时间仍可能
被同网络上的观察者看到。使用可信网络、USB 或加密 VPN，不要向公网开放。

### 蓝牙 Classic

先在 Windows 设置和 Android 设置中完成系统配对，然后运行：

```powershell
reflection-host serve --transport bluetooth
```

也可持久配置：

```powershell
reflection-host config set transport bluetooth
reflection-host serve
```

服务使用 WinSock RFCOMM 自动分配信道，并通过 SDP 公布固定 UUID：
`7b011e5a-8b31-4c40-8d7a-3e6c6dcbb758`。Android 使用已配对电脑的蓝牙地址
和同一 UUID 连接，无需手动输入信道。此模式直接使用 RFCOMM，**不依赖 PAN
或网络共享**；同样执行 Reflection 配对密钥认证和帧验证。

服务端同时要求 Windows 蓝牙栈启用链路认证和加密。适配器或驱动不支持这些
要求时，启动会明确失败。进程退出会移除 SDP 广播。`bind`、`port` 设置仅对
TCP 生效，蓝牙日志的 `port` 是动态 RFCOMM 信道号。

## 映射和笔配置

所有矩形使用 `x,y,width,height`。平板源矩形使用 0..1 的归一化坐标；宿主
目标矩形使用物理桌面像素，支持左侧显示器的负坐标。`displays` 输出每台
显示器的准确坐标。目标默认是整个虚拟桌面；这里没有自动保持纵横比，
如需等比例映射，请配置对应长宽比的源或目标区域。

```powershell
reflection-host config set mapping.mode absolute
reflection-host config set mapping.source 0,0,1,1
reflection-host config set mapping.target -1920,0,1920,1080
reflection-host config set mapping.rotation 90
reflection-host config set mapping.pressure_gamma 0.8
reflection-host config set mapping.sensitivity 1.0
```

| 配置 | 支持值 |
| --- | --- |
| `transport` | `tcp` / `bluetooth` |
| `bind` | TCP 的数字 IPv4 地址；默认 `127.0.0.1` |
| `port` | TCP 端口 1..65535；默认 `24819` |
| `token` | 64 个十六进制字符；修改后旧配对密钥失效 |
| `mapping.mode` | `absolute` / `relative` |
| `mapping.source` | 源区域，必须完全位于单位矩形内 |
| `mapping.target` | `desktop` 或物理像素矩形 |
| `mapping.rotation` | 0、90、180、270；倾斜轴随之旋转 |
| `mapping.pressure_gamma` | 0.1..10；1 为线性，低于 1 更轻压易出粗线 |
| `mapping.sensitivity` | 大于 0 且不超过 10；只影响相对模式 |

相对模式从当前系统指针位置开始，以平板移动量改变位置；离开感应区域后
重新进入不会跳回绝对坐标。目标矩形限制指针移动范围。没有人为增加延迟
的平滑或预测定时器，笔压力与位置直接按采样数据映射。

## 结构和扩展

| 组件 | 职责 |
| --- | --- |
| `reflection_core` | 数据验证、序列/接触状态检查、坐标/压力映射、配置持久化 |
| `reflection_windows` | 可被 CLI 或 UI 复用的 Windows 主机静态库 |
| `InputBackend` | 将映射后事件提交系统，或释放所有笔状态 |
| `pen_backend.cpp` | Windows Synthetic Pointer 适配器及 dry-run 后端 |
| `server.cpp` | TCP / RFCOMM 有序流、认证、心跳和单客户端会话 |
| `crypto.cpp` | Windows BCrypt 随机数及 HMAC-SHA256 |
| `main.cpp` | CLI 参数、JSON 命令输出和进程生命周期 |

UI 通过相同配置模型和会话入口工作，不需要重新实现协议、映射或系统调用。
主机核心通过结构化 `EventSink` 发出事件，JSON 和终端输出只存在于 CLI。
事件回调同步执行，应快速返回；UI 可以将事件转发到自己的线程或消息队列。
将来可以添加 VHF 虚拟 HID 或 WinTab 适配器，而无需修改 Android 采集代码。
VHF 涉及内核驱动、签名、安装和更严格的兼容性验证，不属于当前交付后端。

协议参见 [协议规范](../../protocol/README.md)。实现使用 TCP_NODELAY、有限
帧长度、五秒握手 deadline、两秒会话 deadline、严格递增心跳与笔序列，
并在异常路径释放输入。客户端与宿主机时钟没有同步，不能用两端时间戳
相减宣称单程延迟。

## 验证状态

可移植核心已在 macOS 上通过 38 项检查，覆盖压力曲线、负屏幕坐标、旋转、
相对模式重新进入、非法接触状态、非法输入、协议向量、重放和配置验证。
可使用 `scripts/test-portable.sh` 在有 C++17 编译器的系统上重新运行。
完整 Windows x64 宿主与密码学测试程序均已通过严格警告检查下的 MinGW
交叉编译。Windows 的 BCrypt
测试包含统一协议中的认证向量，需在 Windows 的 CTest 中运行。

Windows 桌面注入、应用实际压力/橡皮擦行为、不同 DPI/显示器组合、USB 与
蓝牙连接、断线恢复和端到端延迟仍需要 Windows 实机验证。源码或交叉编译
通过不能替代这些测试。建议先运行 `serve --dry-run` 确认数据，再在支持
Windows Ink 的绘画程序里测试逐渐加压、悬停、翻转橡皮擦、侧键和拔线。

## 官方接口资料

- [CreateSyntheticPointerDevice](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-createsyntheticpointerdevice)
- [InjectSyntheticPointerInput](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-injectsyntheticpointerinput)
- [POINTER_PEN_INFO 压力和倾斜范围](https://learn.microsoft.com/en-us/windows/win32/api/winuser/ns-winuser-pointer_pen_info)
- [Bluetooth bind 与 RFCOMM 信道](https://learn.microsoft.com/en-us/windows/win32/bluetooth/bluetooth-and-bind)
- [Bluetooth SDP 服务注册](https://learn.microsoft.com/en-us/windows/win32/bluetooth/bluetooth-and-wsasetservice)
- [Virtual HID Framework](https://learn.microsoft.com/en-us/windows-hardware/drivers/hid/virtual-hid-framework--vhf-)
