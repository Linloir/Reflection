# Reflection for macOS

Reflection 把平板的笔输入映射到 Mac 桌面。此子项目使用 Swift、Network.framework、
CryptoKit 和 CoreGraphics，提供独立的 `ReflectionCore` 库和 `reflection` 命令行。
当前最低系统版本为 macOS 12，不依赖第三方运行库。

## 桌面应用

`Reflection.app` 使用原生 SwiftUI 界面，包含连接状态、实时输入诊断、压感曲线、
倾斜设置、映射区域、坐标精度和多套 profile。界面使用与 CLI 相同的配置验证和映射核心，
通过随应用提供的主机程序启动、停止及重启服务，配置也与 CLI 共用。

使用包含 macOS 26 或更新 SDK 的 Apple 工具链构建：

```sh
server/macos/scripts/build-app.sh
open server/macos/build/Reflection.app
```

脚本在 `server/macos/build/Reflection.app` 生成完整应用，默认执行本地 ad-hoc 签名。
可传入其他以 `.app` 结尾的输出路径。用于公开分发时应使用 Developer ID 签名及公证。
可以先把应用移动到「应用程序」文件夹，再授予辅助功能权限，避免移动后重新授权。

macOS 26 及更新系统使用 Liquid Glass 控件表面；macOS 12–25 使用系统材质作为回退，
并使用相应系统版本提供的原生导航和控件。构建使用新 SDK，部署下限仍为 macOS 12。
界面随系统切换明暗外观，不需要单独主题配置。

界面的操作分为编辑和应用两个步骤：

- **Save** 保存配置；运行中的主机继续使用启动时的配置。
- **Apply & restart** 保存配置并安全重启主机，释放当前笔状态后应用新设置。
- **Pen response** 提供 gamma、软/硬预设、可拖动控制点及精确数字输入。预览调用实际
  映射核心；自定义曲线优先于 gamma。实时压力标记显示输入在当前编辑曲线上的结果。
- **Profiles** 保存当前映射和笔响应；加载后先进入编辑器，再自行应用。profile 不包含
  配对密钥或监听地址。删除 profile 不改变当前配置。
- **Mapping** 支持绝对/相对模式、显示器/桌面区域、平板有效区域、旋转、等比映射及
  软件坐标网格；网格不会改变硬件采样率或 LPI。
- **Connection** 可设置地址、端口和密钥，以及下次从界面启动主机时使用的蓝牙、
  dry-run 和实验 tablet-point 选项。密钥默认隐藏。

应用启动后不会自动注入输入。关闭应用会停止由它启动的主机；从 CLI 启动的独立主机
不会因关闭窗口而停止。界面每秒刷新一次状态，笔输入处理仍在独立的主机进程内完成。
如果 CLI 或其他程序修改了配置，界面会提示重新加载，避免覆盖外部更改。
自定义配置路径可通过 `open .../Reflection.app --args --config PATH` 传入。

首次使用桌面应用时，需要为 Reflection 自身授予辅助功能权限；终端已有的授权不会
自动授予桌面应用。非 dry-run 的 **Start host** 和 **Apply & restart** 会先使用随包
主机检查当前启动身份的授权。权限缺失或无法确认时，界面显示操作提示，不停止正在运行的
主机，也不保存该次应用操作的配置草稿。可在 **Diagnostics → Open Accessibility
Settings** 中打开系统设置，授权后重试。dry-run 不需要此权限。

## 构建与运行

安装 Apple Command Line Tools 或 Xcode 后，在仓库根目录执行：

```sh
swift build --package-path server/macos -c release
swift run --package-path server/macos reflection config init
swift run --package-path server/macos reflection doctor
swift run --package-path server/macos reflection start --dry-run
```

`--dry-run` 完整执行连接、身份认证、协议检查和坐标映射，使用记录后端，不向系统注入
鼠标或笔输入。`--verbose` 会逐样本输出 JSON，仅用于诊断；它会增加日志开销并包含笔迹
坐标，因此不宜在日常使用中开启。

`swift build --package-path server/macos -c release --show-bin-path` 输出编译产物目录。
该目录中的 `reflection` 可放入自己的 `PATH`，之后无需通过 `swift run` 启动。
配置默认保存在 `$XDG_CONFIG_HOME/reflection/server.json`，没有设置该变量时使用
`~/.config/reflection/server.json`。任意命令支持 `--config PATH`。

正式注入模式需要在「系统设置 → 隐私与安全性 → 辅助功能」中授权实际运行的终端或
Reflection 程序。授权后重新启动程序：

```sh
reflection start
```

程序不自行开启系统权限。`doctor` 只读取辅助功能授权状态；运行 `start --bluetooth`
才会初始化蓝牙服务，系统可能要求蓝牙权限。

## 连接

### USB

当前 USB 使用 Android 调试桥的有线 TCP 转发，需要 Android 开启 USB 调试并信任主机。
主机默认监听 `127.0.0.1:24819`，不对局域网开放：

```sh
adb reverse tcp:24819 tcp:24819
reflection pair
reflection start
```

客户端填写 `127.0.0.1`、端口 `24819` 和 `pair` 显示的配对密钥。配对密钥是敏感数据，
不要放入截图、公开日志或版本库。配置文件按当前用户可读写的权限保存。

### WiFi

```sh
reflection config set bind '"0.0.0.0"'
reflection start
```

在客户端填写 Mac 的局域网地址。也可以把 `bind` 设为指定网卡地址。防火墙需要允许所选
端口的入站连接。协议提供双向 HMAC 身份认证及每帧完整性验证，**不加密笔迹内容**；
请使用可信网络，或在 VPN 内使用。它不用于直接暴露到公网。

### 原生蓝牙 RFCOMM

先通过系统蓝牙界面配对平板，然后执行：

```sh
reflection start --bluetooth
```

客户端选择蓝牙传输及已配对的 Mac。服务 UUID 固定为
`7b011e5a-8b31-4c40-8d7a-3e6c6dcbb758`。程序通过 IOBluetooth 发布非持久 SDP 服务，
接收 RFCOMM 字节流，并经本地 TCP 适配器交给同一认证及输入引擎。无线侧不要求网络、
热点或蓝牙 PAN；额外的一跳仅发生在主机本地。退出时移除 SDP 服务。

蓝牙适配器按 RFCOMM MTU 分块，限制积压数据，断开时关闭对应会话。此实现仍需要在实际
Mac/平板组合上完成配对、权限、吞吐和断线恢复验证。`doctor` 中列出的蓝牙能力表示
编译时具有此接口，不表示当前硬件、权限或连接已通过运行验证。

输入缓冲在复制和排队前预留预算，上限为 64 KiB；本地 TCP 连接进入等待状态时关闭该
蓝牙会话。每次 RFCOMM 写入设置独立的 2 秒进度期限，阻塞时关闭通道，避免长时间占用
唯一的会话位置。

## 配置与 CLI

所有控制能力都由 CLI 提供，桌面界面使用相同核心库和主机程序。运行中的服务使用启动时的
配置快照，修改配置后需重新启动。

| 命令 | 作用 |
| --- | --- |
| `config init` | 创建配置和随机 256 位密钥；已有配置时拒绝覆盖 |
| `config show` | 输出配置 JSON，默认隐藏密钥 |
| `config show --reveal-token` | 显示完整配置，适合受控自动化 |
| `config set KEY JSON_VALUE` | 修改并验证指定字段 |
| `config apply KEY JSON_VALUE ...` | 整体验证并原子保存多个字段 |
| `config validate` | 验证配置，不启动输入服务 |
| `profiles list` | 列出保存的映射 profile |
| `profiles save NAME [--replace]` | 保存当前笔响应和映射；覆盖需显式指定 `--replace` |
| `profiles show NAME` | 输出 profile JSON，不含配对信息 |
| `profiles load NAME` | 将 profile 写入当前配置，重启后生效 |
| `profiles delete NAME` | 删除指定 profile，保持当前配置 |
| `pair` | 显示配对所需协议版本、端口和密钥 |
| `displays` | 列出显示器 ID、桌面区域及物理像素尺寸 |
| `doctor` | 输出授权、配置、后端和显示器诊断 |
| `start` | 前台运行，使用 Ctrl-C 或 SIGTERM 安全退出 |
| `start --dry-run` | 认证、解码和映射，使用记录后端 |
| `start --bluetooth` | 同时提供原生 RFCOMM 服务 |
| `start --tablet-point-events` | 额外发送独立 tabletPoint 事件；用于应用精度兼容实验 |
| `status` | 输出最近状态快照及进程存活检查 |
| `stop` | 通过当前配置的本机控制通道请求安全停止 |

`status` 的快照文件与配置同目录，名称为 `server.status.json`，或随自定义配置文件名
变化。快照通常每秒更新，包含样本数、会话数、最后样本、最后映射坐标和断连原因；
记录后端另外提供 `contactDown`、`backendReleaseCount`，便于验证断线自动抬笔。
它是上次运行的快照，需结合 `processAlive`、`updatedAt` 和 `state` 判断当前状态。
`processAlive` 通过同用户本机控制通道确认，停止请求不依赖状态文件中可能过期的 PID。
`stop` 返回后可查询 `status`，确认 `processAlive` 为 `false` 后启动下一实例。

### 压感、倾斜与 profile

默认使用 gamma 曲线。自定义曲线由 2–32 个归一化控制点组成，首尾固定为 `(0, 0)` 和
`(1, 1)`，输入坐标必须严格递增，输出不得递减。各点间使用线性插值；非空自定义曲线
优先于 gamma，设为空数组即可恢复 gamma 模式。

```sh
reflection config set mapping.pressureCurve \
  '[{"input":0,"output":0},{"input":0.25,"output":0.45},{"input":0.5,"output":0.72},{"input":0.75,"output":0.9},{"input":1,"output":1}]'
reflection profiles save "Soft pencil"
reflection config apply mapping.pressureCurve '[]' mapping.pressureGamma 1.4
reflection profiles save "Firm ink"
reflection profiles list
reflection profiles load "Soft pencil"
```

`mapping.tiltEnabled` 控制是否输出倾斜；`mapping.tiltScale` 范围为 0–2，默认 1，
表示保留采集到的倾角。缩放后限制在每轴 -90–90 度。此设置不会创造硬件不支持的倾斜数据。

界面中的 **Received tilt X / Y** 与 `status.lastSample` 显示客户端处理后发送的角度，
尚未经过宿主的旋转、开关或比例映射。它们不是 Android 原始轴值，也不表示目标应用
实际使用了这些角度。零值可能表示直立、输入不可用或客户端关闭倾斜；协议 v1 不传输
逐样本的可靠性状态，需要结合平板诊断判断。状态界面每秒刷新一次，最后一条悬停样本
可能覆盖之前的接触笔画。

Android 的 Auto / Standard / Off 控制采集侧的倾斜处理，与宿主开关和 profile 独立。
Auto 仅对匹配已验证特征的 Note2 输入启用一维兼容恢复，不需要校准；它不能补回当前
输入链路缺少的第二轴。已测竖屏方向恢复的是屏幕上下方向的倾斜，左右方向仍缺失。
对应已测固件的设备驱动解析错误已确认，完整修复需要驱动正确上报原始报告，
宿主参数无法补回驱动丢弃的数据。未匹配设备继续使用标准 Android 路径。
详细边界与排查步骤见
[笔设置与排障](../../docs/pen-settings.md)。

```sh
reflection config apply mapping.tiltEnabled true mapping.tiltScale 1.0
reflection config apply mapping.positionStepsX 4096 mapping.positionStepsY 4096
```

`positionStepsX/Y` 分别定义映射后的 X/Y 软件坐标网格。`0` 保留原有浮点精度；
`2–65536` 限制该轴的可用位置数，包含两端。它调整的是**输出位置量化**，不改变平板硬件
LPI、采样率、压感级数或 Retina 分辨率。通常保持 `0`；需要特定位置网格时才设置。
位置网格不应作为消除硬件抖动的替代手段。

Profile 与配置并列保存在 `server.profiles` 目录，自定义配置名对应自己的 profile 目录。
内容只有映射和笔响应，不含 token、监听地址或启动参数。修改、加载 profile 后需重启主机。

### 映射

默认将客户端有效输入区完整映射到主显示器。`displays` 的 `bounds` 是 Quartz 全局桌面
坐标，支持位于主显示器左侧、上方的负坐标。映射使用这个坐标空间，不使用 Retina 的
物理像素数量。

```sh
reflection config set mapping.mode '"absolute"'
reflection config set mapping.displayID 1
reflection config set mapping.source '{"x":0.1,"y":0.1,"width":0.8,"height":0.8}'
reflection config set mapping.target '{"x":0,"y":0,"width":1440,"height":900}'
reflection config set mapping.rotation 90
reflection config set mapping.pressureGamma 1.3
```

`source` 是客户端归一化坐标中的有效矩形；`target` 是主机桌面的目标矩形，优先于
`displayID`。将 `mapping.target` 设为 `null` 可恢复显示器映射。显示器 ID 应使用
`displays` 的实际输出。
映射旋转同时旋转坐标和倾斜方向；状态中的原始样本保留客户端上报的倾斜值。

`pressureGamma` 使用 `输出压力 = 输入压力 ^ gamma`，默认 1；小于 1 更易达到高压力，
大于 1 则需要更用力。映射计算保留客户端归一化浮点精度；系统事件路径可能进一步量化，
见下面的兼容边界。

```sh
reflection config set mapping.mode '"relative"'
reflection config set mapping.relativeSensitivity 1.0
reflection config set mapping.preserveAspect true
reflection config set mapping.sourceAspect 0.75
```

相对模式使用连续笔位置的变化量，离开感应范围后重新进入不会跳到绝对位置。
`sourceAspect` 为客户端完整有效输入区的宽高比，启用等比映射时使用；旋转和裁剪也会
参与比例计算。当前不会根据客户端能力描述自动修改配置，需要用户设置。

## 系统输入与兼容边界

Quartz 后端发送带 `tabletPoint` 子类型的鼠标事件，以及笔进入/离开感应范围的
`tabletProximity` 事件。包括压力、两个方向倾斜、笔/橡皮擦工具类型和按钮字段：

- 笔尖产生左键按下、拖动和抬起；悬停移动光标。
- 移动事件同时包含桌面位置与实际 X/Y 移动增量，供绘画应用及时识别拖动。
  增量按相邻量化位置之差计算，保留连续亚像素位移的累计量。
- 第一侧键映射为右键，第二侧键映射为中键，同时保留 tablet button 元数据。
- 橡皮擦通过 proximity 的工具类型传递，是否切换工具由目标应用决定。
- 失去连接、心跳超时、协议错误或服务正常退出时释放按键和 proximity。
- 每个服务实例最多允许一个连接，避免多台平板争夺同一系统笔状态。

压力精度取决于应用读取的事件路径。自动检查显示，Quartz 的
`tabletEventPointPressure` 字段约为 16 位精度，但嵌入鼠标事件转换为 NSEvent 后，
其通用 `pressure` 属性走约 8 位的鼠标压力路径；不能据此宣称目标应用获得了完整 4096 级
压感。默认只发嵌入式鼠标笔事件，保持单一事件路径。实验选项
`--tablet-point-events` 额外发送独立的 tabletPoint 事件，其 NSEvent 压力读取保留约
16 位精度；同时处理两类事件的应用可能重复笔画，应逐应用测试后决定是否使用。
没有任何自动检查能够替代端到端画笔压力验证。

它是用户态 Quartz 事件后端，**不是注册到系统的虚拟 HID 数位板**。能够读取标准
AppKit/Quartz tablet 事件的应用有机会得到压力和工具信息，但仍需逐个应用验证。
只读取原始 HID、专有数位板驱动接口或过滤合成事件的应用可能无法使用。屏幕锁定、
安全输入场景和其他系统限制也可能阻止注入。项目不承诺兼容所有数位板软件。

宿主按序投递每个有效样本，但目标应用仍可能合并移动事件，或在繁忙时降低笔迹
保真度。事件构造检查不能证明每个采样点都被目标应用绘制；快速转折和起笔必须
在实际应用中验收。

侧键动作当前固定；没有快捷键编辑器、触控手势、滚轮、虚拟 HID 驱动或独立手写识别。
Android 端不支持的轴不会由服务端补造。配置变化必须重新启动服务，不会在笔接触时热更新。

## 核心组织

| 文件 | 职责 |
| --- | --- |
| `Configuration.swift` | 配置、验证、私有文件保存、显示器信息 |
| `Pen.swift` | 平台无关笔样本、坐标和压力映射、后端协议 |
| `WireProtocol.swift` | 有界分帧、HMAC、定长笔数据验证 |
| `TabletServer.swift` | TCP 生命周期、认证、超时、单会话和状态 |
| `QuartzBackend.swift` | 可单独检查的事件构造与系统注入 |
| `BluetoothBridge.swift` | IOBluetooth SDP/RFCOMM 有序传输适配 |
| `ReflectionCLI` | 参数解析和核心能力的命令行入口 |

输入核心和系统后端位于串行队列，避免按下/抬起重排；状态文件写入使用独立低优先级队列。
TCP 启用 `TCP_NODELAY`。异步发送中尚未完成的数据限制为 64 KiB，达到上限后以
`send_backpressure` 原因断开并抬笔，避免慢速接收端或堵塞的蓝牙回传使 pong 无限积累。
旧会话的发送回调不会释放新会话的发送预算。状态包含 `pendingSendBytes` 和
`sendQueueLimit`。认证过程限制为 5 秒，会话在 2 秒内没有有效认证帧则断开并抬笔。
首个笔序号必须为 1，随后严格递增，设备时间戳不能倒退，心跳值严格递增。没有先收到 DOWN
的 MOVE/UP、重复 DOWN、接触中的 HOVER 或工具切换都会被拒绝，断开会话并释放已有输入，
不会补造点击。未知帧、非法值和认证失败同样断开。
详见仓库的 `protocol/README.md`。

新 UI 可以复用 `ServerConfiguration`、`CoordinateMapper`、`TabletServer` 和
`TabletBackend`；未来虚拟 HID 后端可实现相同后端协议，无需重写客户端或网络协议。

## 验证

```sh
swift run --package-path server/macos reflection-core-checks
swift test --package-path server/macos
```

第一条无需 XCTest，验证共享协议向量、身份认证、碎片/合并帧、非法输入、坐标和压力映射、
记录后端释放，以及 Quartz 事件被转换为 NSEvent 后的压力、倾斜和橡皮擦信息。它不会
向系统投递任何事件。第二条需要工具链包含 XCTest，完整 Xcode 可提供该运行环境。

上述自动检查不代替实际应用绘画测试、蓝牙实机测试、触笔至光标延迟测量或长时间稳定性测试。

## Apple API 资料

- [CGEventField](https://developer.apple.com/documentation/coregraphics/cgeventfield)
- [CGEvent](https://developer.apple.com/documentation/coregraphics/cgevent)
- [NSEvent](https://developer.apple.com/documentation/appkit/nsevent)
- [IOBluetoothRFCOMMChannel](https://developer.apple.com/documentation/iobluetooth/iobluetoothrfcommchannel)
- [IOBluetoothSDPServiceRecord](https://developer.apple.com/documentation/iobluetooth/iobluetoothsdpservicerecord)
