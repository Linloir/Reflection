# 电子纸刷新与消散笔迹

Reflection 把笔采集、局部墨迹和屏幕刷新驱动分开。电子纸选项为「自动 / 开启 / 关闭」；
自动模式识别 BOOX / Onyx、Bigme、Boyue / Meebook、InkBook、Dasung、PocketBook、
Moaan、Reinkstone 等明确的厂商品牌，以及 Onyx 框架特征。无法确定的设备保留普通
Android 模式，用户可以手动开启。品牌判断只选择交互节奏，不能证明厂商刷新 API 可用。

## 当前行为

- 没有页面滑入、透明度切换等过渡动画。设置和引导使用分页。
- 本地笔迹默认关闭，可以选择持续保留或短暂显示。
- 消散模式中，一笔抬笔后等待 1 / 1.5 / 2 秒，再从最后写下的位置向起笔位置逐段淡出。
  每段淡出默认 900ms，整笔方向传播最多额外 500ms；尚未抬笔的笔画保持可见。
- 普通屏幕的淡出每 32ms 检查一次；电子纸每 120ms 检查一次，使用离散灰阶。
  只有透明度实际变化的笔画区域才请求重绘，空白画布不持续运行刷新定时器。
- 笔画绘制请求合并为一份脏矩形。电子纸请求在 20ms 窗口内合并，输入历史采样、
  坐标、压力和网络发送均不等待这个窗口。
- 最多保留 8,192 段、512 笔；超过限制回收旧数据。清除、调整区域或切换设置会释放
  本地笔迹，避免电子纸上保存无界的画布历史。
- 清除和页面变化请求清晰刷新；使用原生区域波形的设备每 24 笔在书写区请求一次清理。
  不修改系统级刷新模式，不关闭触摸、不接管厂商直接绘制通道，也不要求 root。

## 驱动分层

`eink/RefreshDriver` 是厂商适配接口，接收 View、脏矩形和 INK / FADE / CLEAN 意图。
新增厂商驱动只需要实现这层，并在 `EinkRefresh` 中添加经过验证的能力探测。

| 驱动 | 实际行为 |
| --- | --- |
| `OnyxRefreshDriver` | 运行时检测 Onyx 框架常量和五参数区域 invalidate 方法；墨迹使用 DU、淡出使用 GU、清理使用 GC |
| `AndroidRefreshDriver` | 使用 Android `View.invalidate(Rect)`，电子纸模式仍降低淡出频率；不声称控制物理屏幕波形 |

电子纸优化开启时按框架能力探测 Onyx 驱动，不要求品牌名称匹配。
Onyx 适配不硬编码数字波形。它从 `android.onyx.ViewUpdateHelper` 读取
`UI_DU_MODE`、`UI_GU_MODE`、`UI_GC_MODE`，查找
`View.invalidate(int,int,int,int,int)` 或
`View.invalidateWithUpdateMode(int,int,int,int,int)`。
所有参数都是当前 View 内的局部边界。方法缺失、受系统隐藏 API 限制或调用失败时，
这一实例立即回退到 Android 重绘，后续不重复调用失败的方法。

界面报告的是实际选中的驱动名称；「Android regional redraw / e-paper cadence」
表示只有通用局部重绘和电子纸节奏优化，不能把它解释成 BOOX 原生低延迟笔迹。
物理屏幕是否只刷新脏矩形还受厂商固件和 Android 合成器影响。

## 研究证据与边界

2026-10-26 开始此次检查时，`adb devices -l` 没有任何已连接设备。因此没有提取当前
Note2 的笔记 APK 或固件，也没有完成 Note2 的波形、残影、实际笔尖延迟测试。
`tools/inspect_eink.py` 可以在设备重新接入后只读提取笔记 APK、框架 JAR、设备
属性和 SHA-256 清单，默认写入忽略版本控制的 `client/android/build/eink-inspection/`。
该工具不读取笔记内容、不安装应用、不修改系统设置。

已核对 Onyx 官方 `OnyxAndroidDemo` 的 `EpdDemoActivity` 和它声明的
`com.onyx.android.sdk:onyxsdk-device:1.3.5.3`。官方 Maven AAR 中 `EpdController`
提供区域 `invalidate`、`refreshScreenRegion`，其 `SDMDevice` 实现使用上述
Onyx 框架常量和 View 方法。当前桥接只实现这条经 SDK 字节码核对的区域失效路径，
没有复制 SDK 代码、打包 SDK 或假定 Note2 固件必然接受所有调用。

研究下载保存于未跟踪的 `client/android/build/eink-research/`：

```text
onyxsdk-device-1.3.5.3.aar
SHA-256 955e8df4b3439e0a4e15f007f72d99c7ef2af3c7eed083a67e453cdaea9e1e0c
classes.jar
SHA-256 298b14a962504db34c26e46311ba09f3b32e438cd8824c603abe49f6ce7defbe
```

官方来源：

- Onyx 示例：<https://github.com/onyx-intl/OnyxAndroidDemo>
- Onyx 设备 SDK：<https://repo.boox.com/repository/maven-public/com/onyx/android/sdk/onyxsdk-device/1.3.5.3/onyxsdk-device-1.3.5.3.aar>
- Android View：<https://developer.android.com/reference/android/view/View>

## 验证

`InkTrailTest` 检查抬笔计时、末尾先消散、离散灰阶、完整回收、橡皮擦与线段相交、
长笔画和多笔画的内存上限。Android 完整构建检查接口与最低 API 编译兼容。
这些检查不证明任何电子纸设备的显示速度。

连接实机后应分别验证：

1. 自动识别、强制开启和关闭的实际驱动报告；横屏两个方向的区域坐标。
2. 短点、长线、快速排线和橡皮擦是否出现区域外刷黑、缺笔、残影。
3. 同一笔输入下关闭预览、持续预览、消散预览的端到端延迟和耗电差异。
4. 切换页面、退出应用、断连、锁屏后，系统其他应用的刷新和触摸保持正常。
5. 修改固件或隐藏 API 策略后，刷新桥接失败能够回退且不影响笔数据发送。
