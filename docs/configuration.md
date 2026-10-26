# 配置与 CLI

仓库根目录的 `reflection` 提供构建、安装、ADB 配对和参考协议诊断。
实际宿主具有独立原生 CLI，可以直接被脚本或 AI Agent 使用。

## 配置生命周期

- macOS 默认配置：`~/.config/reflection/server.json`，支持 `XDG_CONFIG_HOME`。
- Windows 默认配置：`%LOCALAPPDATA%\Reflection\server.conf`。
- 两端均可用 `--config PATH` 指定独立配置。
- 修改配置前执行校验，保存后重启宿主生效。
- 普通 `config show` 隐去配对密钥；显式配对命令才读取密钥。
- `pair-android` 通过已授权 ADB 把密钥写入应用私有配置，不打印密钥。

macOS 可以先用独立配置试验映射：

```bash
python3 reflection server --config .local/demo.json config init
python3 reflection server --config .local/demo.json config set mapping.mode '"relative"'
python3 reflection server --config .local/demo.json config validate
python3 reflection server --config .local/demo.json start --dry-run
```

`.local/` 已被 Git 忽略。请勿把实际密钥提交到示例配置、问题报告或日志。

## 映射

默认是完整输入区域的绝对映射：macOS 默认目标为主显示器，Windows 默认
目标为整个虚拟桌面。先运行 `server displays` 取得系统坐标，再选择目标
矩形，避免猜测屏幕缩放比例。

macOS 示例：

```bash
python3 reflection server config set mapping.mode '"absolute"'
python3 reflection server config set mapping.source '{"x":0.1,"y":0.1,"width":0.8,"height":0.8}'
python3 reflection server config set mapping.target '{"x":0,"y":0,"width":1920,"height":1080}'
python3 reflection server config set mapping.rotation 90
python3 reflection server config set mapping.pressureGamma 1.3
python3 reflection server config set mapping.preserveAspect true
python3 reflection server config set mapping.sourceAspect 0.75
```

`sourceAspect` 是实际笔输入区域的宽高比，不能总用整块屏幕的宽高比代替。
演示 UI 的控制区会占据部分屏幕。

相对模式使用连续样本的位移，适合类似鼠标的移动方式。压力曲线的 gamma 为
1 时不变；大于 1 会压低中间压力，小于 1 会提高中间压力。它不能恢复设备
或系统已经丢失的有效精度。

Windows 的配置字段是扁平的 `key=value`，区域用逗号分隔。其完整键名、
显示器选择和示例以 [Windows README](../server/windows/README.md) 为准。
两端共享协议与概念，但不直接互换配置文件。

## UI 与 CLI 的对应关系

| 平板与输入操作 | 根 CLI |
| --- | --- |
| 配置宿主和密钥 | `pair-android`，或 Android 子项目的 `configure` 命令 |
| 打开界面 | `android launch` |
| 连接 / 断开 | `android connect` / `android disconnect` |
| 开关本地预览 | `android preview-on` / `android preview-off` |
| 开关电脑端防抖 | `server config set stabilization.enabled true` / `false` |
| 查看防抖状态 | `server status` |
| 开始 / 完成校准 | `server calibration start` / `server calibration finish` |
| 取消 / 查看校准 | `server calibration cancel` / `server calibration status` |
| 清除预览 | `android clear` |
| 查看状态 | `android status` |
| 查看输入轴 | `android devices` |
| 查看蓝牙配对设备 | `android paired` |
| 合成诊断笔画 | `android selftest`，仅用于诊断 |

Android 控制 Receiver 受系统 `DUMP` 权限保护：普通第三方应用不能调用。
它不是暴露到网络的远程管理接口。ADB 控制依赖设备已授权的调试通道。

防抖开关与校准保存在电脑端。普通参数保存后重启宿主生效，校准成功后噪声值
立即生效。默认无需校准即可使用；步骤、参数与算法边界见 [防抖说明](stabilization.md)。

macOS 提供 SwiftUI 桌面界面，Windows 提供 WinUI 3 桌面界面。
两端均支持连接状态、启动/停止、曲线、区域、倾斜、输出坐标级数和 profiles。
macOS 界面直接复用 Swift 配置库；Windows 界面调用原生 CLI 的 JSON 控制接口。
输入接收与注入运行在独立宿主中，绘制曲线和刷新面板不会进入笔事件队列。

| 桌面操作 | 原生 CLI |
| --- | --- |
| 查看状态 / 停止 | `status` / `stop` |
| 启动 / 无输入诊断模式 | `start` / `start --dry-run` |
| 查看配置 / 校验 | `config show` / `config validate` |
| 保存多个配置字段 | `config apply KEY VALUE ...` |
| 保存 / 覆盖 profile | `profiles save NAME` / `profiles save NAME --replace` |
| 列出 / 查看 profile | `profiles list` / `profiles show NAME` |
| 加载 / 删除 profile | `profiles load NAME` / `profiles delete NAME` |
| 显示器 / 权限诊断 | `displays` / `doctor` |

## 压感曲线与配置档案

Gamma 模式保留原行为；自定义曲线使用 2–32 个控制点，输入必须严格递增，
输出不能下降，首尾固定为 `(0,0)`、`(1,1)`。控制点之间可选折线或单调平滑插值。
非空自定义曲线取代 Gamma，不会再叠加一次 Gamma。抬笔、悬停和取消始终输出零压力。

macOS：

```bash
python3 reflection server config set mapping.pressureCurve '[{"input":0,"output":0},{"input":0.25,"output":0.45},{"input":0.75,"output":0.9},{"input":1,"output":1}]'
python3 reflection server config set mapping.pressureInterpolation '"smooth"'
python3 reflection server profiles save "Soft pencil"
python3 reflection server profiles list
python3 reflection server profiles load "Soft pencil"
python3 reflection server config set mapping.pressureCurve '[]'
```

Windows（PowerShell）：

```powershell
python reflection server config set mapping.pressure_curve "0,0;0.25,0.45;0.75,0.9;1,1"
python reflection server config set mapping.pressure_interpolation smooth
python reflection server profiles save "Soft pencil"
python reflection server profiles load "Soft pencil"
python reflection server config set mapping.pressure_curve ""
```

profile 包含映射、压力、倾斜和坐标级数，不包含配对密钥、监听地址或端口。
两端的 profile 文件格式不同，不能直接互换。保存同名 profile 默认拒绝覆盖；
显式 `--replace` 才会替换。修改或加载 profile 后重新启动宿主生效。
防抖参数与噪声校准属于电脑的输入设置，独立于映射 profile。

## 倾斜与输出坐标级数

macOS 的 `mapping.tiltEnabled` / `mapping.tiltScale`，Windows 的
`mapping.tilt_enabled` / `mapping.tilt_scale` 分别控制倾斜开关和 0–2 倍比例。
这是对已收到角度的转换，不能修复设备本身的异常读数。

`mapping.positionStepsX/Y`（macOS）或 `mapping.position_steps_x/y`（Windows）
控制整个目标矩形每轴的输出坐标级数：`0` 保留原生精度，或设置为 `2..65536`。
例如某轴设为 1024，位置会落在包含两端的 1024 个格点上。默认建议保持 `0`。
相对模式在内部累积完整精度的位置，只在输出时量化，避免小位移反复被舍去。
这不是设备的 LPI 或采样率设置，详细区别见 [笔设置说明](pen-settings.md)。

```bash
# macOS：同时配置两个轴，校验全部成功后才保存
python3 reflection server config apply mapping.positionStepsX 4096 mapping.positionStepsY 4096
python3 reflection server config set mapping.tiltEnabled false
python3 reflection server stop
python3 reflection server start
```

## 安装与退出

```bash
python3 reflection install server
```

默认复制宿主可执行文件到当前用户目录；可用 `--destination` 指定目录。
工具会输出需要加入 `PATH` 的位置，不修改 shell 配置或系统服务。
安装位置稳定后再授予 macOS 辅助功能权限，可以减少因移动二进制而重新授权
的情况。

CLI 启动的宿主以前台进程运行，`Ctrl-C` 或同一配置的 `server stop`
触发输入释放和退出。`stop` 通过当前用户的专用控制通道识别宿主，不按状态文件
中可能过期的 PID 杀进程。桌面 UI 同样使用此控制能力。当前没有自动安装
LaunchAgent 或 Windows Service。需要自启动时，应由自己的进程管理工具
运行同一个原生命令，并保留退出时释放输入的行为。
