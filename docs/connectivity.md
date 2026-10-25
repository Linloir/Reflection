# 连接方式

三个连接方式传输同一个带认证的有序笔协议。连接方式不会改变映射或压力配置。
宿主默认只监听回环地址，且只允许一个活跃客户端。

## USB

当前采用 Android USB 调试提供的 ADB reverse：

```text
Android 127.0.0.1:24819 -> USB/ADB -> 电脑 127.0.0.1:24819
```

```bash
python3 reflection usb connect
python3 reflection pair-android --connect
```

拔插 USB 或重新启动 ADB 后，必要时再次运行 `usb connect`。移除转发：

```bash
python3 reflection android disconnect
python3 reflection usb disconnect
```

这不是 USB HID gadget，也不会绕过设备的 USB 调试授权。它适用于无需 root
的调试部署；后续普通用户安装体验需要另行设计，不能把“插线即可当 HID”
作为当前功能。

多台设备连接时在 Android 操作中传入 `--serial`。未指定设备且检测到多台时，
工具会报错，避免把配置发送给另一台设备。

## Wi-Fi / 局域网

平板和宿主需要网络可达；路由器的访客隔离、不同 VLAN、防火墙都可能阻断连接。
先把宿主监听地址改为其局域网接口地址，或显式监听所有接口：

```bash
# macOS：配置值采用 JSON
python3 reflection server config set bind '"0.0.0.0"'

# Windows：配置值采用普通字符串
python reflection server config set bind 0.0.0.0
```

重新启动宿主后，通过 ADB 把实际宿主地址写入平板：

```bash
python3 reflection pair-android --host 192.168.1.10 --connect
```

示例地址需要替换为自己的宿主地址。初次配对可以通过 USB；保存配置后，日常
Wi-Fi 连接不依赖 USB。也可以在客户端面板输入地址和配对密钥。

建议先测试 USB，再比较 Wi-Fi，区分采集问题与网络抖动。TCP 开启 `TCP_NODELAY`，
不使用等待凑批的发送计时器；无线拥塞与重传仍可能造成尾部延迟。

## 蓝牙 Classic RFCOMM

当前实现的是经典蓝牙串口型有序流，服务 UUID：

```text
7b011e5a-8b31-4c40-8d7a-3e6c6dcbb758
```

先在操作系统设置中完成平板与电脑配对。应用只选择已配对设备，不自动打开
系统蓝牙、不扫描周围设备、不替用户确认配对。

```bash
# macOS：在现有服务上启用 RFCOMM 入口
python3 reflection server start --bluetooth --dry-run

# Windows：选择 RFCOMM transport
python reflection server start --transport bluetooth --dry-run

# 列出 Android 已配对设备，并选择电脑的 Bluetooth 地址
python3 reflection android paired
python3 reflection pair-android --mode bluetooth \
  --bluetooth AA:BB:CC:DD:EE:FF --connect
```

Android 12+ 需要“附近的设备”权限。无 UI 的已授权 ADB 部署可使用：

```bash
adb shell pm grant io.github.linloir.reflection android.permission.BLUETOOTH_CONNECT
```

macOS 需要系统蓝牙权限和可用的经典蓝牙适配器。Windows 使用系统
`AF_BTH` / RFCOMM 与 SDP，要求链路配对和加密。不同适配器与系统版本的
互通情况仍需实机验证；编译成功不代表完成蓝牙验证。

这不是 BLE GATT，也不是通过 PAN 转发 TCP。未来 iPad 的蓝牙传输不能直接
假设可以使用任意 Classic RFCOMM；参见 [iOS 规划](../client/ios/README.md)。

## 排查顺序

1. `python3 reflection doctor`：查看工具、宿主权限和 Android 设备数量。
2. `python3 reflection android status`：查看连接、队列溢出、RTT 和异常轴计数。
3. `python3 reflection android devices`：查看 Android 暴露的轴范围。
4. 以 `start --dry-run` 验证接收；macOS 可用 `server status` 查看快照。
5. 连接正常后再检查宿主系统权限、应用的 Windows Ink / tablet 设置和映射。

`probe` 会创建另一个客户端，所以先断开正在占用宿主的平板。它不能用来测量
另一台平板正在使用的连接延迟。Android `status.rttMicros` 才来自该设备连接
上的认证心跳。
