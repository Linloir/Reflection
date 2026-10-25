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

| Android 操作 | 根 CLI |
| --- | --- |
| 配置宿主和密钥 | `pair-android`，或 Android 子项目的 `configure` 命令 |
| 打开界面 | `android launch` |
| 连接 / 断开 | `android connect` / `android disconnect` |
| 开关本地预览 | `android preview-on` / `android preview-off` |
| 开关防抖 | `android stabilization-on` / `android stabilization-off` |
| 查看防抖状态 | `android stabilization` |
| 开始 / 完成校准 | `android calibration-start` / `android calibration-finish` |
| 取消采集 / 删除校准 | `android calibration-cancel` / `android calibration-reset` |
| 清除预览 | `android clear` |
| 查看状态 | `android status` |
| 查看输入轴 | `android devices` |
| 查看蓝牙配对设备 | `android paired` |
| 合成诊断笔画 | `android selftest`，仅用于诊断 |

Android 控制 Receiver 受系统 `DUMP` 权限保护：普通第三方应用不能调用。
它不是暴露到网络的远程管理接口。ADB 控制依赖设备已授权的调试通道。

防抖开关与校准保存在客户端，立即生效，不需要重启宿主。默认无需校准即可
使用；校准步骤、质量检查与算法边界见 [防抖说明](stabilization.md)。

宿主目前没有图形界面，因此所有已实现能力直接由 CLI 提供。后续 UI 应调用
相同配置模型和控制核心；不应添加只有 UI 可用的隐藏配置。

## 安装与退出

```bash
python3 reflection install server
```

默认复制宿主可执行文件到当前用户目录；可用 `--destination` 指定目录。
工具会输出需要加入 `PATH` 的位置，不修改 shell 配置或系统服务。
安装位置稳定后再授予 macOS 辅助功能权限，可以减少因移动二进制而重新授权
的情况。

宿主以前台进程运行，`Ctrl-C` 触发输入释放和退出。当前没有自动安装
LaunchAgent 或 Windows Service。需要自启动时，应由自己的进程管理工具
运行同一个原生命令，并保留退出时释放输入的行为。
