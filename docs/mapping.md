# 屏幕映射与切换

Windows 和 macOS 宿主支持三种目标：所有屏幕、指定屏幕、自定义矩形。
所有屏幕使用操作系统的显示器排列和联合边界；左侧、上方屏幕的负坐标会保留，
不会把不同屏幕重新横向拼接。屏幕间的空隙也属于联合矩形。
平板源区域、旋转、相对模式和压力设置仍由各平台原有映射选项控制。
Windows 注入时会把带符号桌面坐标转换为相对于虚拟桌面左上角的像素坐标，
因此主屏左侧或上方的显示器不会造成整体偏移或贴边。

这种“全部屏幕 / 单屏切换”的交互参考
[Wacom 的 Display Toggle](https://101.wacom.com/UserHelp/en/DisplayToggle_Full.htm)。

## 桌面界面

运行 `python reflection ui`，打开 **Screens and mapping**（Windows）或
**Mapping**（macOS）。屏幕图按照系统布局排列，点击屏幕即可切换。
**All screens** 使用全部屏幕；**Previous / Next** 逐块切换；
**Identify** 在各屏幕显示编号。实际切换时，目标屏幕会短暂显示提示，
不抢走绘画软件的焦点。

Windows 的快捷键在桌面窗口打开时可用，最小化不影响使用：

| 操作 | Windows | macOS |
| --- | --- | --- |
| 上一块屏幕 | Ctrl+Alt+PageUp | Control+Option+PageUp |
| 下一块屏幕 | Ctrl+Alt+PageDown | Control+Option+PageDown |
| 全部屏幕 | Ctrl+Alt+0 | Control+Option+0 |

Windows 可关闭 **Screen switching shortcuts**；被其他应用占用的组合键会明确提示。
macOS 的全局键盘监测需要桌面应用自身获得辅助功能权限；没有权限时仍可用界面按钮。
关闭桌面窗口后，平板和 CLI 仍可切屏，但该窗口的快捷键与屏幕提示不再工作。

切换是实时会话设置，不需要重启或重新配对，也不会覆盖保存的启动配置。
Windows 中，**Use active mapping at startup** 把当前目标填入启动设置，
再点 **Save settings** 保存。其他笔设置仍按界面提示保存并重启生效。
如果正在落笔绘画，切换会等待抬笔，当前笔画最后一个点仍落在原屏幕。
屏幕断开时会回退可用目标，状态会标记回退；插回后恢复选定屏幕。

## 平板联动

新版 Android 客户端顶部显示当前屏幕目标；打开 **Screens** 可查看电脑布局，
点击单块屏幕或使用 Previous / All screens / Next 切换。
电脑端的切换也会同步到平板。旧宿主不支持控制协商时，界面显示不可用，
普通笔输入仍可使用。

```bash
python reflection server mapping status
python reflection server mapping next
python reflection server mapping previous
python reflection server mapping all
python reflection server mapping select DISPLAY_ID

python reflection android mapping status
python reflection android mapping next
python reflection android mapping all
python reflection android mapping select DISPLAY_ID
```

`DISPLAY_ID` 从 `mapping status` 的 `displays[].id` 原样取得。Windows 使用
系统显示设备名，macOS 使用显示器 UUID；它们不等同于图中的编号。
Windows 宿主停止时，CLI 切屏命令会保存下次启动的目标；macOS 的切屏命令要求宿主运行。

原 Android 应用若使用另一份签名，不能直接用本机构建覆盖安装。
可按 [Android 构建说明](../client/android/README.md) 并排安装 **Reflection Preview**，
保留原应用数据。控制预览版时加上 `--package io.github.linloir.reflection.preview`，
配对命令同样支持此参数。

## Windows 悬浮笔提示

Windows 的笔反馈和鼠标箭头是不同的系统行为。原实现使用
`POINTER_FEEDBACK_DEFAULT`，可被 Windows 的笔设置隐藏。
**Show pen position** 默认开启，改用 `POINTER_FEEDBACK_INDIRECT` 显示系统笔反馈；
关闭时恢复系统设置控制的原生行为。仍然发送真实 Windows Ink 笔事件，
不会模拟额外鼠标移动，也不修改全局系统设置。
依据：[Microsoft 的反馈模式说明](https://learn.microsoft.com/en-us/windows/win32/api/winuser/ne-winuser-pointer_feedback_mode)。

CLI 对应设置：

```powershell
python reflection server config set mapping.show_cursor true
# 或 false；保存后重启宿主生效
```

不同绘画应用可能绘制自己的笔刷光标。系统 API 创建成功不能替代实际应用中的
物理笔验收；请分别检查悬停、落笔、抬笔、离开感应区和橡皮擦。
