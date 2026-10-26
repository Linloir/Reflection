# Reflection Windows Host

Windows 宿主端使用原生 C++17、WinSock、BCrypt 和 Windows Synthetic Pointer
API，将平板笔事件作为 `PT_PEN` 输入送入当前桌面。桌面应用使用 WinUI 3，
通过 CLI 的结构化接口控制同一个宿主进程。采样接收、映射和系统输入不依赖
窗口或 .NET UI 线程，关闭或替换界面不会改变笔输入算法。

默认 API Mode 保持上述行为。可选 Driver Mode 使用 KMDF/VHF 虚拟 HID 驱动，
UI 和 CLI 提供安装、卸载及即时切换；切换至 Driver Mode 会自动尝试安装，
卸载前会切回 API Mode。需要已签名驱动包，构建与验证边界见
[输入模式与驱动](../../docs/input-modes.md)。
仓库目前不包含正式签名驱动，普通 UI 构建默认只提供 API Mode；
界面会显示缺包状态并禁用不可用的驱动操作。`scripts/build-driver.ps1`
生成用于后续签名的完整未签名包，正式签名完成后用
`scripts/build-ui.ps1 -DriverPackage PATH` 校验并打包。

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

### WinUI 桌面应用

另外安装 .NET 10 LTS SDK。桌面界面固定使用 Windows App SDK **2.5.1**，
编译目标 Windows SDK 10.0.19041，最低运行系统声明为 10.0.17763
（Windows 10 1809）。在 Windows 的 Developer PowerShell 中运行：

```powershell
./server/windows/scripts/build-ui.ps1
./server/windows/build/ui/Reflection.Desktop.exe
```

脚本构建 C++ 宿主及 WinUI 项目，并将宿主、.NET 运行时和 Windows App SDK
一起发布到 `server/windows/build/ui/`。分发和安装时保留**整个目录**，
不要只复制界面的 `.exe`。支持 `-Architecture ARM64`；构建 ARM64 原生宿主
需要相应的 Visual Studio C++ ARM64 工具。安装到用户目录的示例：

```powershell
./server/windows/scripts/build-ui.ps1 -InstallDirectory "$env:LOCALAPPDATA\Reflection\Desktop"
& "$env:LOCALAPPDATA\Reflection\Desktop\Reflection.Desktop.exe"
```

首次打开界面会在没有配置时调用 `init`。界面包含启动、停止、dry-run、状态、
重新启动、连接配置、显示器/映射区域、倾斜开关/比例、压力曲线、坐标步数和
配置档管理。压力曲线可使用软/线性/硬预设、Gamma、自定义控制点文本，或拖动
图中的内部控制点。所有保存动作由原生 CLI 验证；多字段设置在全部通过验证后
一次写入，运行中的配置不会被部分更新。保存后使用“Save and restart”应用。
启动界面时同样可以传入 `--config PATH`；初始化、所有配置命令和宿主进程都会
使用该路径，支持包含空格的路径。

Windows 11 在 `MicaController.IsSupported()` 返回真时使用系统 Mica 背景；
Windows 10 使用当前系统主题的实色背景和 WinUI 控件。没有直接调用未公开的
DWM API。系统材质会尊重系统设置与回退条件。UI 可替换，后台输入保持 C++。
关闭窗口会停止该窗口启动的宿主；通过终端启动的宿主继续运行。

配对字段默认隐藏。只有点击“Copy pairing key”时才显式读取密钥并复制到剪贴板，
日志不会显示密钥。普通 UI 读取配置也不会取回密钥。

## CLI

以下示例假设 `reflection-host` 已在 `PATH` 中：

```powershell
reflection-host init
reflection-host doctor
reflection-host displays
reflection-host config show
reflection-host config validate
reflection-host config snapshot
reflection-host status
reflection-host serve --dry-run
```

`init` 生成随机配对密钥，已有配置不会被覆盖。默认配置路径为
`%LOCALAPPDATA%\Reflection\server.conf`，也可在任何命令中使用
`--config PATH` 指定独立配置。配置文件是 `key=value` 文本。

`serve --dry-run` 接收、认证、验证、映射事件并输出笔划边界诊断，不操作
系统指针。去掉 `--dry-run` 后启用真实笔输入。`start` 是 `serve` 的别名，
两者都在前台运行，按 Ctrl+C 或从另一个终端运行 `reflection-host stop` 停止。
`stop` 使用当前配置对应的本地命名事件进行协作停止，不会终止任意 PID。
同一配置只允许一个宿主。当前不包含系统服务安装或登录时自动启动。

`status` 输出 `running`、`processAlive`、`controlAvailable` 和
`restartRequired`；运行时还包含 PID、监听/连接状态、端口、会话采样计数、
dry-run 和运行时间。采样计数以低频更新，不阻塞输入路径。`restartRequired`
比较磁盘配置与启动时的配置；UI 和终端修改都能被发现。

```powershell
reflection-host config show --secrets
reflection-host serve
```

只有显式使用 `--secrets` 才会显示配对密钥，用于配置平板。不要把这份输出
写入公共日志。普通诊断和连接日志不会包含密钥。配置命令成功返回 0，错误
返回非零；设置、诊断和事件日志使用 JSON，便于脚本和 Agent 解析。
配置修改在下次启动服务时生效。

UI 使用 `config snapshot` 读取隐藏密钥的配置及不透明 `revision`，保存时调用
`config apply --if-current REVISION KEY VALUE ...`。配置锁内比较版本并写入，
如果终端或 Agent 已修改配置，保存会报冲突，要求刷新；不会用旧 UI 数据静默
覆盖外部修改。普通 `config apply` 仍适用于不需要条件更新的脚本。

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

桌面界面的 **Screens and mapping** 提供按 Windows 布局排列的屏幕图、
全部屏幕/单屏实时切换、Ctrl+Alt+PageUp/PageDown/0 快捷键和目标屏幕提示。
新版 Android 的 **Screens** 面板与宿主双向同步；落笔时切换会等到抬笔生效。
详见 [多屏映射与切换](../../docs/mapping.md)。

`mapping.target` 现在也接受 `display:DISPLAY_ID`，按当前显示设备解析矩形；
原有 `desktop` 和自定义矩形保持兼容。运行中的
`mapping status|next|previous|all|select ID` 控制当前会话，不覆盖启动配置。
`mapping.show_cursor` 默认为 `true`，启用可见的系统笔反馈；设为 `false`
后遵循 Windows 自身的笔反馈设置，修改后重启生效。

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
reflection-host config apply mapping.pressure_gamma 0.8 mapping.tilt_scale 1.0
```

| 配置 | 支持值 |
| --- | --- |
| `transport` | `tcp` / `bluetooth` |
| `bind` | TCP 的数字 IPv4 地址；默认 `127.0.0.1` |
| `port` | TCP 端口 1..65535；默认 `24819` |
| `token` | 64 个十六进制字符；修改后旧配对密钥失效 |
| `mapping.mode` | `absolute` / `relative` |
| `mapping.show_cursor` | `true` 强制显示系统笔反馈；`false` 遵循 Windows 原生设置 |
| `mapping.source` | 源区域，必须完全位于单位矩形内 |
| `mapping.target` | `desktop`、`display:DISPLAY_ID` 或物理像素矩形 |
| `mapping.rotation` | 0、90、180、270；倾斜轴随之旋转 |
| `mapping.pressure_gamma` | 0.1..10；1 为线性，低于 1 更轻压易出粗线 |
| `mapping.pressure_curve` | 空字符串使用 Gamma；否则为 `input,output;...` 的单调分段线性曲线 |
| `mapping.position_steps_x` / `mapping.position_steps_y` | 0 为原生坐标；2..65536 为映射范围内的离散位置数 |
| `mapping.tilt_enabled` | `true` / `false`；关闭后不声明倾斜轴，输出倾斜值为 0 |
| `mapping.tilt_scale` | 0..2；旋转后的倾斜角按比例缩放，并限制在 -90..90 度 |
| `mapping.sensitivity` | 大于 0 且不超过 10；只影响相对模式 |

相对模式从当前系统指针位置开始，以平板移动量改变位置；离开感应区域后
重新进入不会跳回绝对坐标。目标矩形限制指针移动范围。没有人为增加延迟
的平滑或预测定时器，笔压力与位置直接按采样数据映射。

### 压力曲线和分辨率

自定义曲线有 2..32 个控制点，以 `0,0` 开始、`1,1` 结束。输入严格递增，
输出单调不减，所有坐标在 0..1；无效曲线会被拒绝。存在自定义曲线时覆盖
Gamma，抬笔始终强制压力为零。例如：

```powershell
reflection-host config set mapping.pressure_curve "0,0;0.25,0.1;0.75,0.9;1,1"
reflection-host config set mapping.pressure_curve ""
reflection-host config apply mapping.position_steps_x 4096 mapping.position_steps_y 4096
reflection-host config apply mapping.tilt_enabled true mapping.tilt_scale 0.9
```

`config apply KEY VALUE [KEY VALUE ...]` 与多个 `set` 的区别是整体校验、一次提交。
Windows PowerShell 5.1 可能在原生命令参数传递时丢弃空字符串；这种情况下使用
PowerShell 7、桌面界面切换 Gamma，或在配置文件中将 `mapping.pressure_curve=`
保留为空，然后执行 `config validate`。

这里的坐标步数是**输出坐标量化**，并不改变平板硬件的 LPI 或采样频率，也不
增加 Windows 压力 API 的 1025 个数值。默认 0 保留协议中的连续坐标，到系统
输入边界才转换为物理像素；设置较低步数会形成可见阶梯。相对模式内部始终保留
未量化的位移累积，因此小幅移动不会因每次舍入而永久丢失。

### 多套配置档

```powershell
reflection-host profiles save Drawing
reflection-host profiles list
reflection-host profiles show Drawing
reflection-host profiles load Drawing
reflection-host profiles save Drawing --replace
reflection-host profiles delete Drawing
```

`profile` 是 `profiles` 的别名。名称允许 1..64 个 ASCII 字母、数字、
下划线或连字符。重复保存需要显式 `--replace`。配置档位于配置文件旁边的
`<配置文件名>.profiles/`，仅保存 `mapping.*`，不会保存配对密钥、监听地址
或传输方式。加载后保留当前连接配置；运行中的宿主在重新启动后使用新映射。
`show` 同样只输出映射字段。删除配置档不会修改当前活动配置。

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
| `runtime.cpp` | 单实例所有权、协作停止和内存中的状态快照 |
| `ui/HostClient.cs` | CLI 进程适配、异步输出、超时和启动/停止 |
| `ui/MainWindow.xaml` | 可替换的原生 WinUI 界面 |

UI 通过 CLI 工作，不需要重新实现协议、配置持久化、映射或系统调用。
主机核心通过结构化 `EventSink` 发出事件，JSON 和终端输出只存在于 CLI。
事件回调同步执行，应快速返回；UI 可以将事件转发到自己的线程或消息队列。
将来可以添加 VHF 虚拟 HID 或 WinTab 适配器，而无需修改 Android 采集代码。
VHF 涉及内核驱动、签名、安装和更严格的兼容性验证，不属于当前交付后端。

协议参见 [协议规范](../../protocol/README.md)。实现使用 TCP_NODELAY、有限
帧长度、五秒握手 deadline、两秒会话 deadline、严格递增心跳与笔序列，
并在异常路径释放输入。客户端与宿主机时钟没有同步，不能用两端时间戳
相减宣称单程延迟。

## 验证状态

可移植核心检查覆盖自定义压力曲线、负屏幕坐标、旋转、倾斜能力开关、
相对模式重新进入和细小位移累积、坐标量化、配置档凭据隔离/路径校验、
非法接触状态、非法输入、协议向量、重放和配置验证。
可使用 `scripts/test-portable.sh` 在有 C++17 编译器的系统上重新运行。
完整 Windows x64 宿主与密码学测试程序均已通过严格警告检查下的 MinGW
交叉编译。Windows 的 BCrypt
测试包含统一协议中的认证向量，需在 Windows 的 CTest 中运行。

WinUI 的 XAML 编译和运行需要 Windows 工具链；macOS 上的 XML/源码检查不能
证明窗口已经正确呈现。Windows CI 包含桌面应用的实际构建步骤。
桌面进程控制的便携回归检查使用真实测试子进程，覆盖初始查询期间关闭窗口、
宿主尚未创建停止通道时关闭窗口，以及正常启动/停止；可以独立运行：

```powershell
dotnet run --project server/windows/tests/desktop/Reflection.Desktop.Tests.csproj -c Release
```

Windows CTest 另外检查配置所有权、内存状态、配置改变后的重启提示和协作停止。
Windows 桌面注入、应用实际压力/橡皮擦行为、不同 DPI/显示器组合、USB 与
蓝牙连接、断线恢复和端到端延迟仍需要 Windows 实机验证。源码或交叉编译
通过不能替代这些测试。建议先运行 `serve --dry-run` 确认数据，再在支持
Windows Ink 的绘画程序里测试逐渐加压、悬停、翻转橡皮擦、侧键和拔线。

## 电脑端防抖与压感编辑

桌面 **Pen stability** 管理开关、强度和高级参数，显示突跳保护计数。
**Start calibration / Finish and apply / Cancel** 控制主机采集；校准期间不注入
系统输入，成功只保存噪声参数并立即应用。命令为
`calibration start|finish|cancel|status --json`。旧 Android 客户端继续原有滤波，
新版原始坐标客户端使用主机滤波，避免叠加。详见 [防抖与校准](../../docs/stabilization.md)。

**Pressure response** 保留 Gamma 和折线，新增 **Smooth curve**：点击加点、
拖动调整、选中点精确编辑输入/输出百分比。平滑插值单调且无过冲。
普通参数用 **Save and restart** 应用。

`tools/check_stability.py` 提供真实宿主 dry-run 集成验证；Windows 本机 MSVC
原生构建、CTest 和 WinUI 加点/拖动验收通过。实际 Inkodo 书写仍须使用物理笔检查。

## 官方接口资料

橡皮擦的工具身份与接触状态分别报告：翻转悬浮只设置 `PEN_FLAG_INVERTED`，
真正接触时才同时设置 `PEN_FLAG_ERASER`。抬起、取消、退出和悬浮保活都清除
擦除接触位及压力，保留需要的倒置身份，防止悬浮被应用解释为擦除或拖动。
回归检查覆盖笔尖/橡皮、侧键、落笔/抬笔及保活；Inkodo 等应用的实际表现需用物理笔复测。

- [CreateSyntheticPointerDevice](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-createsyntheticpointerdevice)
- [InjectSyntheticPointerInput](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-injectsyntheticpointerinput)
- [POINTER_PEN_INFO 压力和倾斜范围](https://learn.microsoft.com/en-us/windows/win32/api/winuser/ns-winuser-pointer_pen_info)
- [Pen Flags 倒置与擦除标记](https://learn.microsoft.com/en-us/windows/win32/inputmsg/pen-flags-constants)
- [Windows Pen 擦除状态要求](https://learn.microsoft.com/en-us/windows-hardware/design/component-guidelines/required-hid-top-level-collections#eraser)
- [Bluetooth bind 与 RFCOMM 信道](https://learn.microsoft.com/en-us/windows/win32/bluetooth/bluetooth-and-bind)
- [Bluetooth SDP 服务注册](https://learn.microsoft.com/en-us/windows/win32/bluetooth/bluetooth-and-wsasetservice)
- [Virtual HID Framework](https://learn.microsoft.com/en-us/windows-hardware/drivers/hid/virtual-hid-framework--vhf-)
- [Windows App SDK 稳定版本](https://learn.microsoft.com/en-us/windows/apps/windows-app-sdk/stable-channel)
- [WinUI 3 系统背景](https://learn.microsoft.com/en-us/windows/apps/windows-app-sdk/system-backdrop-controller)
- [Windows App SDK 自包含部署](https://learn.microsoft.com/en-us/windows/apps/package-and-deploy/self-contained-deploy/deploy-self-contained-apps)
