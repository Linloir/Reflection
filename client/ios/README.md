# iPad 客户端规划

此目录保留给后续原生 iPad 客户端。**当前没有可安装的 iOS 应用。**
Android 和宿主的工作不依赖此目录，也不借一个空工程表示 iPad 已受支持。

计划使用 Swift / UIKit，直接采集 Pencil：

- `UITouch` 的位置、force、altitudeAngle、azimuthAngle。
- coalesced touches 保留实际历史样本；预测触点只用于本地预览，不作为已发生的
  笔输入注入宿主。
- 正确处理 estimated properties 的后续更新；协议需要明确区分修订与新样本，
  不能伪造历史重放。
- 本地渲染与采集分离，可以替换为 Metal、PencilKit 或其他实现。
- 连接、配置与诊断由独立控制核心提供，便于 UI、自动化和测试复用。

Wi-Fi 可通过 Network.framework 复用 [协议 v1](../../protocol/README.md)。
桌面不需要为 iPad 更换坐标映射或输入后端。

USB 不存在 Android 的 ADB reverse，需要单独评估受支持的 USB 网络或配套
转发方案。iPad 也不能直接假设能连接任意经典蓝牙 RFCOMM 服务；如果选择
BLE，需要增加 GATT 分片、MTU、流控、认证、掉线恢复和桌面适配器。

在上述采集与连接路径实现并经实物验证之前，不宣称支持 iPad。
