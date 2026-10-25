# 笔输入防抖与校准

Reflection 的 Android 客户端默认启用自适应坐标防抖，不校准也可以使用。
它在笔样本进入传输队列前运行，因此对 USB、Wi-Fi、蓝牙，以及 macOS、
Windows 宿主都使用同一套处理。本地预览与传输使用相同的处理后坐标。
压力、倾斜、工具、按钮和样本时间戳不受影响。

防抖用于减轻悬停和书写时的小幅位置噪声。抖动可能来自数字化器、驱动、
握笔动作或它们的组合；看到光标抖动并不能单独证明硬件精度不足。
算法不能完美区分两段观测值完全相同的“噪声”和“刻意细微运动”。
需要完全保留原始坐标时，可以随时关闭。

## 开关

平板界面的 **Reduce pen jitter** 控制防抖，设置会保存在设备上。
仓库根目录也可以执行：

```sh
python3 reflection android stabilization
python3 reflection android stabilization-off
python3 reflection android stabilization-on
```

关闭时逐样本原样通过，没有隐藏的第二层平滑。开关立即生效，不需要重启宿主。
建议在笔抬起后切换，便于比较同一笔刷和同一映射下的效果。
`android status` 中的 `stabilization` 对象提供设置、噪声参数和校准状态。

Android 子项目的独立脚本提供等价入口：

```sh
client/android/scripts/reflection-android stabilization off
client/android/scripts/reflection-android stabilization on
client/android/scripts/reflection-android stabilization status
```

## 可选校准

校准估计当前输入区域中，笔尖静止时两个方向的噪声幅度。它不是压感校准、
屏幕对齐或延迟测量，也不会提高数字化器的物理分辨率。

1. 打开平板输入界面，保持即将使用的方向和布局。
2. 点击 **Calibrate**，或运行 `android calibration-start`。
3. 在书写区中央重新落笔，用平常的握姿轻触并保持静止约 2–3 秒，再抬笔。
   不要画线、摇动笔尖或反复落笔来凑样本数。
4. 点击 **Finish**，或运行 `android calibration-finish`。

最低要求为一次连续接触中的 120 个有效样本及 1 秒采样时间。
实际需要多久取决于设备如何交付静止输入。界面状态和 JSON 会显示计数；
如果设备静止时很少发送样本，可继续使用默认防抖，无需勉强校准。

```sh
python3 reflection android calibration-start
# 在平板书写区完成一次静止接触
python3 reflection android status
python3 reflection android calibration-finish
```

校准过程中，先前的宿主笔接触会被释放。采集轨迹仅在平板显示标记，不发送给
电脑，不会在当前文档留下校准涂画。完成或取消后，悬停恢复；新的笔画需要重新
落笔，避免把校准期间的移动接到原来的笔画上。

| 操作 | CLI | 效果 |
| --- | --- | --- |
| 重新校准 | `android calibration-start`，随后 `android calibration-finish` | 新结果通过质量检查并保存后，才替换旧结果 |
| 取消本次采集 | `android calibration-cancel` | 丢弃未完成采集，保留已有校准 |
| 删除校准 | `android calibration-reset` | 删除已有数据并取消采集，恢复默认噪声参数；保留防抖开关 |

校准会拒绝样本不足、时间不足、明显移动、持续漂移、多次接触和输入异常。
失败时保留旧校准；可以继续当前静止接触以补足样本，或取消后重新开始。
校准开启时不能运行合成 `selftest`，以免把测试数据混入实际测量。

校准数据保存在 Android 应用私有配置中，只有噪声估计、采样统计和输入区域
尺寸/旋转信息，不保存整段笔迹。区域尺寸或旋转变化时，旧结果失效并回到默认
参数；布局尚未完成的零尺寸不会删除已有数据。更换笔、握姿或设备使用方式后，
也可以主动重新校准。当前配置属于该客户端设备，尚不按不同实体笔保存多套档案。

独立脚本对应 `calibration start`、`calibration finish`、
`calibration cancel`、`calibration reset`。

## 算法与取舍

实现使用 One Euro 的速度自适应低通思路，并加上二维一致性、静止置信度和
修正距离限制。它是独立的纯 Java 核心，不依赖界面、网络或宿主平台。

- 按采集时间戳计算间隔，历史批次逐点处理，不以网络到达时间替代采样时间。
- X/Y 共用一个速度自适应截止频率，避免不同轴的平滑强度扭曲转向。
- 平常将修正限制在估计噪声椭圆的 0.75 倍以内。连续至少 100ms 的轨迹
  呈现低相关、低漂移、小幅噪声特征时，允许扩大到最多 2.5 倍。
- 没有锁住光标的硬死区，也不预测或插入轨迹。所有输入样本仍然输出。
- 落笔、抬笔和取消原样保留；工具切换、接触/悬停切换、异常时间和长间隔
  重置滤波状态，避免旧轨迹拖拽新笔画的起点。
- 校准使用两个轴的中位数绝对偏差估计噪声，并检查局部变化及分段中心漂移，
  防止把明显的主动移动当成噪声。

未校准时，两个轴的噪声尺度各取输入区域长度的 0.0006。这是保守工程初值，
不是所有设备的测量值，也不是论文建议的通用参数。它约对应 1000 单位轴长上
的 0.6 单位，最终屏幕上的修正量还取决于宿主的映射与裁剪。

算法不等待未来样本或另设发送缓冲，但平滑本身仍然可能带来相位滞后。
尤其在幅度接近噪声、速度很慢的刻意摆动中，部分细节会被减弱；噪声范围之外
的快速动作更受距离上限保护。它也不会强行删除所有离群点，否则可能抹去真实
的突然转向。

## 验证

`client/android/scripts/test-core.sh` 包含固定随机种子的静止噪声、不同采样率、
小幅正弦/圆形、快速 W、直角转向、静止后起笔、边界与校准质量检查。

默认参数的合成独立轴噪声测试中，不规则 60/120/240Hz 下的静止 RMS 分别约为
原始值的 54%/42%/35%。这些是可重复的合成结果，不是实机性能保证。相同测试也
检查刻意小幅运动保留程度，避免只优化静止指标。

实际比较时，保持同一应用、笔刷、缩放与映射，分别测试静止悬停、轻压直线、
很小的慢速波浪、快速书写和突然转向。可先比较开关，再决定是否校准。
不要把传输 RTT 当作滤波滞后或笔尖到墨迹的完整延迟。

参考：

- [One Euro Filter 作者项目与论文](https://gery.casiez.net/1euro/)
- [Casiez、Roussel、Vogel：A Simple Speed-based Low-pass Filter for Noisy Input in Interactive Systems](https://inria.hal.science/hal-00670496)
