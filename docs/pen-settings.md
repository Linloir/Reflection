# 笔设置、分辨率与应用排障

## 哪一种“分辨率”

| 概念 | 含义 | Reflection 的处理 |
| --- | --- | --- |
| 数字化器空间分辨率 | 单位物理长度内能报告多少位置，常用 LPI | 由硬件及驱动决定，不伪造更高精度 |
| 报告率 / 采样率 | 每秒报告或采集多少样本，常用 Hz | 展开 Android 历史样本，按序发送；不提供通用硬件调频开关 |
| 压力级数 | 压力轴能区分多少值 | 保留归一化数据，再按系统事件能力转换 |
| 映射与灵敏度 | 平板移动对应多大桌面移动 | 绝对/相对模式、源/目标区域、相对灵敏度 |
| 输出坐标级数 | 目标区域中位置能落在哪些离散格点 | 每轴 0 原生，或 2–65536 级；默认 0 |

Wacom 的公开接口分别用 `AXIS.axResolution` 描述每物理单位的坐标增量数，
用 `LOGCONTEXT.lcPktRate` 描述 Hz 报告率。这两者不应混为一谈。
Reflection 的输出级数是映射后的量化选择，不是更改数字化器规格。
提高级数不能补回硬件没有采集的点；降低级数可能让斜线出现阶梯。
macOS 的目标区域使用桌面逻辑坐标，Windows 使用物理像素。

绘画时通常保持输出级数为 0，先用压力曲线调整手感，按需要设置目标显示器、
区域与比例，再决定是否校准防抖。避免同时降低输出级数并加重平滑，
否则难以判断线条变化来自哪里。

## 压感与 profiles

桌面 UI 可预览压力曲线并编辑控制点。CLI 使用同一宿主配置模型；
具体语法见 [配置与 CLI](configuration.md)。Gamma 小于 1 提升中段压力，
大于 1 压低中段压力；自定义曲线替代 Gamma。曲线不改变真实采样数量或系统压力位数。

控制点支持 **Linear points（折线）** 和 **Smooth curve（平滑曲线）**。
点击图中空白处增加点，拖动点调整输入/输出压力；选中后可删除内部点。
平滑模式使用单调三次 Hermite 插值穿过控制点，保持端点与平台段，不产生过冲。
两种模式都要求输入递增、输出不递减；切换模式保留同一组控制点。
Windows 配置键为 `mapping.pressure_interpolation`，macOS 为
`mapping.pressureInterpolation`，取值 `linear` 或 `smooth`；旧配置默认保持折线行为。

可以为会议书写、草图和绘画分别保存 profile。它保存压力、倾斜和映射等设置，
不会更改连接密钥。防抖与校准现在由电脑端管理，见 [防抖说明](stabilization.md)。应用 profile 后重启宿主，使一整段
笔画使用同一份参数。切换应用不会自动切换 profile。

## 倾斜忽强忽弱

Android 的 `AXIS_TILT` 表示离开屏幕法线的角度，正常从 0 到 π/2；
`AXIS_ORIENTATION` 给出朝向。客户端将它们表达为两个方向的倾斜，
宿主再应用映射旋转、倾斜开关及比例。
先运行：

```bash
python3 reflection android devices
python3 reflection android status
python3 reflection server status
```

重点比较 Android 原始值 `lastRawTilt/lastRawOrientation`、`tiltStatus`、
异常/降级计数与 `lastSample.tiltX/tiltY`。设备声明支持倾斜，并不代表它持续上报
可靠角度；`tiltReliable` 也不能单独证明提供完整二维信息。

Android UI 和 CLI 提供三个持久模式，通常保留默认的 `auto`：

- `auto`：普通设备走标准 Android 路径；仅对匹配已验证特征的 Note2 输入启用一维恢复。
- `standard`：关闭设备兼容解码，使用标准 Android 数据，方便对照诊断。
- `off`：只关闭客户端倾斜输出，继续发送坐标、压力和工具信息。

点击 Android 顶部 **Tilt** 可选择模式并查看诊断，或执行：

```bash
python3 reflection android tilt auto
python3 reflection android tilt status
```

需要重新识别时，使用 **Retry detection** 或 `python3 reflection android tilt reset`。
该操作不改变保存的模式、累计计数或防抖校准；它与防抖的 calibration reset 是两个操作。

标准路径出现异常时采用有界恢复，持续正常的后续输入可以在同一接近周期内重新启用
倾斜，不必因一次坏样本一直等待笔离开感应区。它不会把一个越界读数硬钳制到接近
90 度，也不会为等待倾斜恢复而延迟位置或压力。

已测 Note2 固件 3.5（构建 11030）已确认存在设备驱动解析错误：一条角度通道被拆成
两个单字节值上报，原始报告的后续字节没有被解析。Auto 可以从匹配格式中还原这一维
信息，并结合系统输入方向转换，无需先校准；当前链路缺少第二条独立角度，
因此**不提供完整二维恢复**。定向实机检查确认，在已测竖屏方向可恢复屏幕上下方向
的倾斜，屏幕左右方向仍缺失；其他旋转的转换仅通过单元测试，仍待实机验收。
当前测试固件暴露的可恢复角度以 1° 步进，这不代表笔传感器的原始分辨率；
软件平滑无法增加输入链路实际提供的角度细节。
其他 BOOX 型号、固件和数字化器需要单独验证，不按品牌或单个异常值猜测修复。
已检查的 Onyx SDK 在此设备上读取同一组内核事件，无法取回驱动已丢弃的字节。
完整修复需要设备驱动正确解析并上报原始报告；被丢弃字节是否包含有效的第二轴仍待
直接验证，不能通过调整宿主参数或更换 SDK 补齐。
方向尚不明确时 Auto 保持等待，不把尚未确认的值当作二维数据。笔离开再进入不会
自动丢弃已经完成的兼容识别。

Mac 的 **Received tilt X / Y** 显示客户端处理后、宿主映射前的数据。协议 v1 不传逐样本
可靠性状态，所以宿主上的 0 可能来自直立、缺失方向、不可用或关闭；应结合平板诊断，
不能仅看截图判断硬件能力。状态每秒刷新一次，抬笔后的悬停也可能覆盖之前的接触值。
需要所有笔画始终按直立笔处理时，可选客户端 `off` 或在宿主 profile 中关闭倾斜。

## 橡皮擦没有切换

橡皮擦工具类型和笔侧键是两个不同信号。Android `TOOL_TYPE_ERASER` 表示
橡皮擦或倒置笔；侧键不自动等价于橡皮擦。按以下顺序检查：

1. 用笔的实际橡皮擦端操作，检查 Android `eraserSamples` 是否增加、
   `lastRawToolType` 是否为 4。只有 `barrelButtonSamples` 增加说明收到的是侧键。
2. 检查宿主最后样本的 `tool`：协议中 1 为笔，2 为橡皮擦。
3. macOS 后端通过 proximity 工具类型通知应用；Windows 通过笔工具标志。
   数据正确到达不等于目标应用支持自动切换橡皮擦。
4. 在目标应用手动选择橡皮擦，区分“应用没有橡皮擦功能”与“没有按工具类型切换”。
   不同应用及画笔工具分别验收，不能由一个应用推广到所有软件。

Reflection 不会向任意前台应用发送猜测的键盘快捷键。需要应用快捷键适配时，
应作为显式、按应用配置的能力单独实现。

## 参考资料

- [Wacom Wintab reference: AXIS / LOGCONTEXT](https://developer-docs.wacom.com/docs/icbt/windows/wintab/wintab-reference/)
- [Android MotionEvent](https://developer.android.com/reference/android/view/MotionEvent)
- [Onyx 官方 Pen SDK](https://github.com/onyx-intl/OnyxAndroidDemo/blob/master/doc/Onyx-Pen-SDK.md)
- [Apple Handling Tablet Events](https://developer.apple.com/library/archive/documentation/Cocoa/Conceptual/EventOverview/HandlingTabletEvents/HandlingTabletEvents.html)
- [Microsoft POINTER_PEN_INFO](https://learn.microsoft.com/en-us/windows/win32/api/winuser/ns-winuser-pointer_pen_info)
