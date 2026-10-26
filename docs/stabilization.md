# 笔输入防抖与校准

防抖和噪声校准由 **Windows / macOS 电脑端**管理。Android 发送原始坐标，
电脑在映射和系统注入前处理；平板不再有防抖和校准开关。
新版客户端声明 `hostStabilization:1`，宿主据此启用平滑。旧客户端没有该声明时，
宿主跳过平滑，避免与旧版 Android 防抖叠加；异常跳点保护仍然工作。

## 使用方式

打开桌面 Reflection 的 **Pen stability** 区域，可以开关平滑、调整强度，
在高级设置中调整截止频率、速度响应、噪声尺度和突跳阈值。
普通设置保存后重启宿主生效。校准成功后噪声参数立即生效并保存，无需重启。
关闭平滑会保留原始有效运动，异常坐标和接触生命周期保护独立保留。

Windows CLI 示例：

```powershell
python reflection server config set stabilization.enabled true
python reflection server config set stabilization.adaptive_noise true
python reflection server config set stabilization.strength 0.5
python reflection server config set stabilization.min_cutoff_hz 3
python reflection server config set stabilization.beta 80
python reflection server status
```

macOS 使用 `stabilization.adaptiveNoise`、`stabilization.independentAxes`、`stabilization.minCutoffHz`、`stabilization.derivativeCutoffHz`、
`stabilization.maxJump`；其他键与 Windows 一致。原有 Android 防抖和校准
修改命令会提示改用电脑端，不能再启用客户端第二层平滑。

| 参数 | 默认值 | 范围 | 调整效果 |
| --- | --- | --- | --- |
| enabled | true | true / false | 开关坐标平滑 |
| adaptive_noise | true | true / false | 在线分别估计 X/Y 噪声，关闭后使用固定校准值 |
| independent_axes | false | true / false | 实验性横竖笔画增强稳定；可能改变细曲线和斜线 |
| strength | 0.5 | 0–1 | 越高越稳，细微动作的跟随更柔和 |
| min_cutoff_hz | 3 | 0.1–60 Hz | 越低，慢速/静止平滑越强 |
| beta | 80 | 0–1000 | 越高，快速运动越早减轻平滑 |
| derivative_cutoff_hz | 12 | 0.1–120 Hz | 速度估计响应速度 |
| noise | 0.0006 | 0.00001–0.02 | 原始归一化坐标的噪声尺度，通常由校准估计 |
| max_jump | 0.25 | 0.05–1 | 突然大幅坐标变化的保护阈值 |

这些是工程默认值，不代表任何设备的物理精度。默认无需校准即可使用。
**Adapt to changing pen noise** 开关控制在线估计。校准 noise 是保守下限；在线估计可以
临时增大平滑修正幅度，不能覆盖保存的校准值。界面显示当前轴向尺度及有效窗口点数。
详细实测、统计假设和不能滤除的情况见 [Note2 稳定性研究](stability-study.md)。
高级参数中的 **Steady horizontal and vertical strokes** 使用独立的横纵修正预算，
减小快速竖笔时的横向毛刺，低通速度响应仍共享。该选项默认关闭，存在方向偏好，
不能保证所有笔画更准确；建议同时对照圆圈、斜线和尖角，再决定是否保存。
多屏映射会放大相同的归一化坐标噪声，比较开关时应保持同一映射、笔刷和缩放。

## 校准

1. 连接新版平板客户端，在电脑点击 **Start calibration**。
2. 用平常握姿，在平板输入区同一位置轻触约 2–3 秒。允许轻微手漂，勿刻意画线。
3. 电脑显示采样进度，达到要求后点击 **Finish and apply**，再抬笔重新书写。

```sh
python3 reflection server calibration start --json
python3 reflection server calibration status --json
python3 reflection server calibration finish --json
# 放弃本次采集
python3 reflection server calibration cancel --json
```

最低需要一笔连续接触中的 120 个样本和 1.2 秒。采集上限为 10 秒/4096 个样本。
界面显示采样数、时间、进度及具体原因。样本不足时可以继续采集；失败和取消都
保留先前噪声值。恢复默认可使用桌面 **Restore smoothing defaults**，或将 noise
设回 0.0006，保存后重启。

校准在短时间窗口中去除缓慢线性漂移，再用中位数绝对偏差估计噪声；
少量异常点不会让整次测量作废。明显主动运动、有效样本不足、断线等情况仍须重试。
校准不是屏幕对齐、压感标定或延迟测量，也不能提高数字化器的空间分辨率。

采集期间宿主释放系统接触并暂停注入，因此不会在电脑当前文档中留下校准笔迹。
结束、取消或断线后，旧笔画不会接续；需要新的落笔才恢复接触输入。
校准结果保存在电脑宿主配置中，不自动区分不同实体笔或平板；更换设备后可重新校准。

## 稳定性设计

- Android 按设备和 pointer ID 跟踪一支接触中的笔，拒绝混入的悬浮/其他指针事件，
  冻结单笔归一化几何；非有限坐标不会被转换成屏幕边缘。
- 宿主在平滑之前检查连续性。无法信任的落笔大跳会在最后安全位置取消当前接触，
  屏蔽残余移动，等待新的落笔，避免异常点在远处点击。
- 平滑采用速度自适应低通：采样时间决定步长，X/Y 共用速度响应，限制位置修正量，
  不等待未来样本、不外推轨迹。快速转向时减轻平滑，减少拖尾。
- Windows 抬笔保持最后成功注入的接触坐标；注入时序错误单独诊断，不把失败当作
  成功接触继续处理。虚拟桌面坐标仍在最终 API 边界转换，支持负坐标的多屏布局。

平滑无法完美区分观测值相同的噪声和有意微动，可能减弱非常缓慢的细节。
突跳保护可能中断极端快速或长时间停顿后的笔画；界面的保护计数帮助识别原因。
一次异常会优先结束当前笔画，不能据此宣称从此没有任何断笔或硬件故障。

设计参考 [One Euro Filter 作者项目与论文](https://gery.casiez.net/1euro/) 的速度自适应思路，
以及 [OpenTabletDriver 反抖插件](https://github.com/Kuuuube/Kuuube-s-CHATTER-EXTERMINATOR)
对屏幕/平板坐标空间和强度的区分。系统注入时序参考
[Microsoft 输入注入文档](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-injecttouchinput)。
预测可能产生纠正伪影，参见 [Microsoft 的笔迹延迟设计](https://github.com/MicrosoftEdge/MSEdgeExplainers/blob/main/WebInkEnhancement/explainer.md)；
这里选择有界平滑和连续性保护，而不提前预测新的落笔位置。

## 验证

原生核心测试覆盖静止噪声、运动误差、异常跳点、接触边界及校准。
`tools/check_stability.py` 在真实宿主的 dry-run 模式验证协议能力、旧客户端兼容、
校准进度/失败/保存/断线，以及压力模式持久化，不向桌面注入测试点击。
Windows 桌面测试还验证不等间距压力控制点的单调性、平台段与数值边界。

默认参数的确定性 100 Hz 合成测试中，二维静止 RMS 从 0.000820109 降至
0.000362412（降低约 55.8%）。速度为每秒 0.03 / 0.6 个归一化单位的直线，
最大位置偏差分别为 0.000884194 / 0.000392825；除以对应速度得到约
29.47 ms / 0.655 ms 的等效运动滞后。这不是网络或设备端到端延迟。

合成数据只能说明指定轨迹与参数下的滤波行为，不能代替实机书写或完整笔尖到墨迹
延迟测量。实机应比较慢速小字、快速 W、圆圈、长直线、轻压/抬笔、双屏边界，
并观察系统注入错误、断线和保护计数。macOS 修改仍需在 Mac 上构建与实际绘画应用验收。
