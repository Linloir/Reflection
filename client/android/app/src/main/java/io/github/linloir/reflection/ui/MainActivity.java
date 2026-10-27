package io.github.linloir.reflection.ui;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import io.github.linloir.reflection.control.Controller;
import io.github.linloir.reflection.core.EdgeNavigator;
import io.github.linloir.reflection.core.MappingState;
import io.github.linloir.reflection.core.PenSample;
import io.github.linloir.reflection.core.SurfaceCalibration;
import io.github.linloir.reflection.core.TiltCalibration;
import io.github.linloir.reflection.eink.EinkRefresh;
import io.github.linloir.reflection.input.TouchpadInput;
import io.github.linloir.reflection.input.WorkspaceInputGate;
import java.util.ArrayList;
import java.util.List;

/** The reader surface, setup flow and settings share a quiet, flat visual vocabulary. */
public final class MainActivity extends Activity {
    private static final int PAPER = 0xfffafbf8, INK = 0xff252a26, MUTED = 0xff687068, LINE = 0xffd7dcd4, ACCENT = 0xff365c4c;
    private static final String REPOSITORY = "https://github.com/Linloir/Reflection";
    private static final String[] SECTIONS = {"工作区", "笔与手势", "电子纸", "连接", "校准", "关于"};
    private final Handler handler = new Handler();
    private Controller controller;
    private UiPreferences prefs, draft;
    private CalibrationStore calibration;
    private SurfaceCalibration surface;
    private InkView ink;
    private TouchpadInput touchpad;
    private CalibrationView calibrationView;
    private LinearLayout root, settingsBody;
    private TextView liveStatus, guideMessage, guideProgress;
    private Button guideNext, guideSkip;
    private MappingView screenDiagram;
    private String route = "home", displayedPairing = "", mappingIdentity = "";
    private int guideStep, section, settingsPage, screenPage, toolbarPage;
    private boolean toolbarCollapsed, guideFromSettings, edgeContact, penContact, penProximity, penHoverSeen, resumed, edgeButtonDown;
    private int edge = -1, tapEdge = -1, taps;
    private int penPointer = -1, penDevice = Integer.MIN_VALUE, proximityDevice = Integer.MIN_VALUE;
    private long lastTap, lastPenSeen, edgeTriggered;
    private final DisplayMetrics displayMetrics = new DisplayMetrics();
    private final int[] viewLocation = new int[2];
    private AlertDialog pairingDialog;
    private EinkRefresh pageRefresh;
    private boolean displayedConnected;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            if ("home".equals(route) && displayedConnected != controller.connected() && !penContact && !touchpad.active()) showHome();
            if (liveStatus != null) setTextIfChanged(liveStatus, connectionText());
            if (ink != null) ink.refreshSettings();
            if (screenDiagram != null) {
                MappingState state = controller.mapping(); screenDiagram.update(state);
                String id = state == null ? "" : state.json();
                if (!mappingIdentity.equals(id) && "screens".equals(route)) { mappingIdentity = id; showScreens(); }
            }
            if ("onboarding".equals(route) && guideStep == 5 && guideNext != null) {
                guideNext.setEnabled(controller.connected()); guideSkip.setVisibility(controller.connected() ? View.INVISIBLE : View.VISIBLE);
            }
            checkPairing();
            handler.postDelayed(this, 1000);
        }
    };
    private final Runnable hoverSwitch = () -> {
        if (edge >= 0 && prefs.edgeSwitch && "hover".equals(prefs.edgeGesture) && !penContact && !edgeContact) triggerEdge(edge);
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setStatusBarColor(PAPER); getWindow().setNavigationBarColor(PAPER);
        controller = Controller.get(this); prefs = UiPreferences.load(this); calibration = new CalibrationStore(this);
        surface = calibration.surface(); toolbarCollapsed = prefs.collapsed;
        touchpad = new TouchpadInput(getResources().getDisplayMetrics().density, controller::submitTouchpad);
        touchpad.setRawSink(controller::rawTouchpadSupported, controller::submitRawTouchpad);
        guideStep = UiPreferences.store(this).getInt("guideStep", 0);
        immersive();
        if (!UiPreferences.store(this).getBoolean("onboarded", false)) showOnboarding(); else showHome();
    }
    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
    @Override public void onWindowFocusChanged(boolean focused) { super.onWindowFocusChanged(focused); if (focused) immersive(); }
    @Override protected void onResume() {
        super.onResume(); resumed = true; controller.startAutomaticConnection(); handler.removeCallbacks(poll); handler.post(poll);
    }
    @Override protected void onPause() { cancelInput(); resumed = false; handler.removeCallbacks(poll); super.onPause(); }
    @Override protected void onDestroy() {
        cancelInput(); handler.removeCallbacksAndMessages(null);
        if (pageRefresh != null) pageRefresh.cancel();
        if (pairingDialog != null) pairingDialog.dismiss();
        super.onDestroy();
    }
    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration); cancelInput();
        if ("settings".equals(route)) showSettings(); else if ("onboarding".equals(route)) showOnboarding();
        else if ("screens".equals(route)) showScreens(); else if ("region".equals(route)) showRegion(); else showHome();
    }
    @Override public void onBackPressed() {
        if ("settings".equals(route)) { draft = null; showHome(); }
        else if ("region".equals(route)) showSettings();
        else if ("screens".equals(route)) showHome();
        else if ("onboarding".equals(route) && guideStep > 0) { guideStep--; showOnboarding(); }
        else if ("onboarding".equals(route) && guideFromSettings) { guideFromSettings = false; showSettings(); }
        else super.onBackPressed();
    }
    private void cancelInput() {
        if (ink != null) ink.cancelInput();
        if (calibrationView != null) calibrationView.cancel();
        if (touchpad != null) touchpad.cancel();
        handler.removeCallbacks(hoverSwitch); edge = -1; edgeContact = penContact = penProximity = penHoverSeen = false; taps = 0;
        lastPenSeen = 0;
        penPointer = -1; penDevice = proximityDevice = Integer.MIN_VALUE;
        edgeButtonDown = false;
    }
    private void newPage(String route) {
        cancelInput(); this.route = route;
        if (pageRefresh != null) pageRefresh.cancel();
        ink = null; calibrationView = null; liveStatus = null; screenDiagram = null; guideNext = guideSkip = null;
        root = column(); root.setBackgroundColor(PAPER); root.setPadding(dp(28), dp(16), dp(28), dp(12));
        setContentView(root);
        pageRefresh = new EinkRefresh(root, prefs.eink);
        root.post(() -> { if (pageRefresh != null) pageRefresh.clean(); });
    }
    private void showHome() {
        newPage("home"); root.setPadding(0, 0, 0, 0);
        displayedConnected = controller.connected();
        FrameLayout frame = new FrameLayout(this);
        ink = new InkView(this, controller); ink.applyOptions(prefs.workspace());
        ink.setSampleTransform(this::calibratedSample); ink.setEventInterceptor(this::workspaceEvent);
        boolean left = "left".equals(prefs.toolbar);
        FrameLayout.LayoutParams drawing = new FrameLayout.LayoutParams(-1, -1);
        if (!prefs.overlay && !toolbarCollapsed) { if (left) drawing.leftMargin = dp(60); else drawing.bottomMargin = dp(56); }
        frame.addView(ink, drawing);
        LinearLayout toolbar = left ? column() : row(); toolbar.setGravity(Gravity.CENTER); toolbar.setBackgroundColor(PAPER);
        toolbar.setPadding(dp(3), dp(3), dp(3), dp(3));
        if (toolbarCollapsed) {
            iconButton(toolbar, "menu", "展开操作栏", () -> { toolbarCollapsed = false; showHome(); }, false);
        } else {
            iconButton(toolbar, "menu", "收起操作栏", () -> { toolbarCollapsed = true; showHome(); }, false);
            separator(toolbar, left);
            iconButton(toolbar, "settings", "设置", () -> { draft = UiPreferences.load(this); section = settingsPage = 0; showSettings(); }, false);
            List<String> tools = new ArrayList<>();
            if (prefs.toolScreens) tools.add("screens");
            if (prefs.toolClear) tools.add("clear");
            if (prefs.toolPreview) tools.add("preview");
            if (prefs.toolTouchpad) tools.add("touch");
            int length = left ? getResources().getConfiguration().screenHeightDp : getResources().getConfiguration().screenWidthDp;
            boolean paged = tools.size() * 48 + 162 > length - 8;
            int perPage = paged ? Math.max(1, (length - 218) / 48) : Math.max(1, tools.size());
            int pages = Math.max(1, (tools.size() + perPage - 1) / perPage);
            toolbarPage %= pages;
            for (int i = toolbarPage * perPage; i < Math.min(tools.size(), (toolbarPage + 1) * perPage); i++) {
                String tool = tools.get(i);
                if ("screens".equals(tool)) iconButton(toolbar, tool, "屏幕", this::showScreens, false);
                else if ("clear".equals(tool)) iconButton(toolbar, tool, "清空笔迹", () -> { controller.clearPreview(); ink.refreshSettings(); }, false);
                else if ("preview".equals(tool)) iconButton(toolbar, tool, "本地笔迹", () -> { prefs.localPreview = !controller.preview(); controller.setPreview(prefs.localPreview); showHome(); }, controller.preview());
                else iconButton(toolbar, tool, "触摸板", () -> {
                    touchpad.cancel(); prefs.touchpad = !prefs.touchpad;
                    UiPreferences.store(this).edit().putBoolean("touchpad", prefs.touchpad).apply(); showHome();
                }, prefs.touchpad);
            }
            if (pages > 1) iconButton(toolbar, "more", "更多工具", () -> { toolbarPage++; showHome(); }, false);
            separator(toolbar, left);
            iconButton(toolbar, "connect", "连接状态", () -> { draft = UiPreferences.load(this); section = 3; settingsPage = 0; showSettings(); }, controller.connected());
        }
        FrameLayout.LayoutParams bar = new FrameLayout.LayoutParams(left ? dp(60) : -2, left ? -2 : dp(56));
        bar.gravity = left ? Gravity.LEFT | Gravity.CENTER_VERTICAL : Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        frame.addView(toolbar, bar); root.addView(frame, new LinearLayout.LayoutParams(-1, 0, 1));
        ink.post(() -> {
            if (ink == null) return;
            if (surface == null) controller.setCalibrationNoise(0);
            else {
                RectF area = ink.activeRect(); ink.getDisplay().getRealMetrics(displayMetrics);
                double scale = Math.max(displayMetrics.widthPixels / Math.max(1, area.width()),
                    displayMetrics.heightPixels / Math.max(1, area.height()));
                scale *= Math.max(Math.abs(surface.x[0]) + Math.abs(surface.x[1]), Math.abs(surface.y[0]) + Math.abs(surface.y[1]));
                controller.setCalibrationNoise((float) Math.max(.00001, Math.min(.02, surface.noise * scale)));
            }
        });
        if (!controller.connected()) {
            TextView hint = text(debuggingEnabled() ? "正在自动发现宿主机 · 首次连接请在电脑端选择信任" : "正在发现宿主机 · USB 连接需要开启调试，点此查看", 12, MUTED);
            hint.setGravity(Gravity.CENTER); hint.setPadding(dp(12), dp(8), dp(12), dp(8));
            hint.setBackgroundColor(PAPER); hint.setOnClickListener(v -> { draft = UiPreferences.load(this); section = 3; settingsPage = 0; showSettings(); });
            FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL); hp.topMargin = dp(8); frame.addView(hint, hp);
        }
    }
    private boolean workspaceEvent(MotionEvent event, RectF active) {
        int action = event.getActionMasked(); boolean pen = false; int penIndex = -1;
        for (int i = 0; i < event.getPointerCount(); i++) {
            int tool = event.getToolType(i);
            if (tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER) {
                pen = true; if (penIndex < 0 || event.getPointerId(i) == penPointer) penIndex = i;
            }
        }
        if (!pen) {
            if (!prefs.touchpad) return true;
            if (action == MotionEvent.ACTION_DOWN && !active.contains(event.getX(), event.getY())) { touchpad.cancel(); return true; }
            touchpad.setBounds(active.left, active.top, active.right, active.bottom);
            return touchpad.onTouch(event, penProximity || penContact || SystemClock.uptimeMillis() - lastPenSeen < 350);
        }
        lastPenSeen = SystemClock.uptimeMillis(); touchpad.cancel();
        if (WorkspaceInputGate.consumes(event, penIndex, penContact || edgeContact, penDevice, penPointer)) return true;
        if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
            action = action == MotionEvent.ACTION_POINTER_DOWN ? MotionEvent.ACTION_DOWN : MotionEvent.ACTION_UP;
        }
        if (proximityDevice != event.getDeviceId()) { penHoverSeen = false; proximityDevice = event.getDeviceId(); }
        if (action == MotionEvent.ACTION_HOVER_ENTER || action == MotionEvent.ACTION_HOVER_MOVE) penHoverSeen = true;
        // Devices without hover do not send HOVER_EXIT after lifting the pen.
        penProximity = action != MotionEvent.ACTION_HOVER_EXIT && action != MotionEvent.ACTION_CANCEL
            && (action != MotionEvent.ACTION_UP || penHoverSeen);
        if (action == MotionEvent.ACTION_HOVER_EXIT || action == MotionEvent.ACTION_CANCEL) penHoverSeen = false;
        boolean buttonPressed = (event.getButtonState() & (MotionEvent.BUTTON_STYLUS_PRIMARY | MotionEvent.BUTTON_STYLUS_SECONDARY
            | MotionEvent.BUTTON_SECONDARY | MotionEvent.BUTTON_TERTIARY)) != 0;
        boolean buttonJustPressed = buttonPressed && !edgeButtonDown;
        edgeButtonDown = buttonPressed;
        if (action == MotionEvent.ACTION_HOVER_EXIT) { lastPenSeen = 0; resetEdge(); return false; }
        if (action == MotionEvent.ACTION_CANCEL) { penContact = false; edgeContact = false; resetEdge(); return false; }
        int direction = -1;
        if (prefs.edgeSwitch && prefs.border && active.left >= dp(8) && active.top >= dp(8)
            && ink.getWidth() - active.right >= dp(8) && ink.getHeight() - active.bottom >= dp(8)
            && controller.mapping() != null && "display".equals(controller.mapping().mode)) {
            float x = event.getX(penIndex), y = event.getY(penIndex), band = dp(24);
            if (y >= active.top - band && y <= active.bottom + band) {
                if (x <= active.left && x >= active.left - band) direction = EdgeNavigator.LEFT;
                else if (x >= active.right && x <= active.right + band) direction = EdgeNavigator.RIGHT;
            }
            if (direction < 0 && x >= active.left - band && x <= active.right + band) {
                if (y <= active.top && y >= active.top - band) direction = EdgeNavigator.UP;
                else if (y >= active.bottom && y <= active.bottom + band) direction = EdgeNavigator.DOWN;
            }
        }
        if (action == MotionEvent.ACTION_DOWN) {
            resetEdge();
            edgeContact = direction >= 0; penContact = !edgeContact;
            penPointer = event.getPointerId(penIndex); penDevice = event.getDeviceId();
        }
        if (penContact) { if (action == MotionEvent.ACTION_UP) penContact = false; resetEdge(); return false; }
        if (direction != edge) {
            resetEdge(); edge = direction;
            if (edge >= 0) ink.cancelInput();
            if (edge >= 0 && "hover".equals(prefs.edgeGesture) && !edgeContact) handler.postDelayed(hoverSwitch, prefs.hoverDelay);
        }
        if (direction >= 0 && "button".equals(prefs.edgeGesture) && buttonJustPressed)
            triggerEdge(direction);
        if (action == MotionEvent.ACTION_UP && edgeContact) {
            edgeContact = false;
            if (direction >= 0 && "hover".equals(prefs.edgeGesture)) handler.postDelayed(hoverSwitch, prefs.hoverDelay);
            if (direction >= 0 && ("tap".equals(prefs.edgeGesture) || "double".equals(prefs.edgeGesture) || "triple".equals(prefs.edgeGesture))) {
                long now = SystemClock.uptimeMillis();
                taps = direction == tapEdge && now - lastTap < 400 ? taps + 1 : 1; tapEdge = direction; lastTap = now;
                int needed = "tap".equals(prefs.edgeGesture) ? 1 : "double".equals(prefs.edgeGesture) ? 2 : 3;
                if (taps >= needed) { triggerEdge(direction); taps = 0; }
            }
            return true;
        }
        return direction >= 0 || edgeContact;
    }
    private void resetEdge() { handler.removeCallbacks(hoverSwitch); edge = -1; }
    private void triggerEdge(int direction) {
        long now = SystemClock.uptimeMillis(); if (now - edgeTriggered < 900) return;
        String next = EdgeNavigator.neighbor(controller.mapping(), direction);
        if (next != null) { switchMapping("select", next); edgeTriggered = now; }
    }
    private PenSample calibratedSample(PenSample p) {
        float x = p.x, y = p.y;
        if (surface != null && ink != null && ink.getDisplay() != null) {
            RectF r = ink.activeRect(); ink.getLocationOnScreen(viewLocation); ink.getDisplay().getRealMetrics(displayMetrics);
            int rotation = ink.getDisplay().getRotation();
            double[] panel = SurfaceCalibration.toPanel((viewLocation[0] + r.left + x * (r.width() - 1)) / displayMetrics.widthPixels,
                (viewLocation[1] + r.top + y * (r.height() - 1)) / displayMetrics.heightPixels, rotation);
            double[] corrected = surface.correct(panel[0], panel[1]);
            double[] screen = SurfaceCalibration.fromPanel(corrected[0], corrected[1], rotation);
            x = clamp((float) ((screen[0] * displayMetrics.widthPixels - viewLocation[0] - r.left) / Math.max(1, r.width() - 1)));
            y = clamp((float) ((screen[1] * displayMetrics.heightPixels - viewLocation[1] - r.top) / Math.max(1, r.height() - 1)));
        }
        int rotation = ink == null || ink.getDisplay() == null ? 0 : ink.getDisplay().getRotation();
        float[] tilt = TiltCalibration.apply(p.tiltX, p.tiltY, calibration.axisForInput(3, rotation), calibration.sign(3),
            calibration.axisForInput(2, rotation), calibration.sign(2));
        return new PenSample(p.sequence, p.timeNanos, x, y, p.pressure, tilt[0], tilt[1], p.distance, p.phase, p.tool, p.buttons, p.capabilities);
    }

    private void showOnboarding() {
        newPage("onboarding"); guideStep = Math.max(0, Math.min(5, guideStep));
        UiPreferences.store(this).edit().putInt("guideStep", guideStep).apply();
        boolean compact = getResources().getConfiguration().screenHeightDp < 420;
        boolean shortScreen = getResources().getConfiguration().screenHeightDp < 360;
        if (shortScreen) root.setPadding(dp(28), dp(8), dp(28), dp(8));
        String[] icons = {"welcome", "calibrate", "vertical", "horizontal", "host", "connect"};
        String[] titles = {"让每一笔，自然抵达。", "找到笔尖的准确位置", "认识上下倾斜", "认识左右倾斜", "在电脑上打开 Reflection", "靠近，即可开始"};
        String[] subtitles = {"欢迎使用 Reflection。将手中的笔，延伸到电脑上的每一块屏幕。", "校准 1 / 3  ·  坐标与抖动噪声", "校准 2 / 3  ·  上下倾斜方案", "校准 3 / 3  ·  左右倾斜方案", "宿主机负责接收笔迹，并将它们交给你正在使用的应用。", "设备自动发现，信任后自动连接。优先 USB，其次 Wi-Fi，再到蓝牙。"};
        LineIcon icon = new LineIcon(this, icons[guideStep]);
        int iconSize = shortScreen ? 28 : compact ? 36 : 64;
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(iconSize), dp(iconSize)); ip.gravity = Gravity.CENTER; ip.topMargin = dp(compact ? 0 : 8); root.addView(icon, ip);
        TextView title = text(titles[guideStep], compact ? 20 : 28, INK); title.setGravity(Gravity.CENTER); title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        add(root, title, -1, -2, shortScreen ? 4 : 6);
        TextView subtitle = text(subtitles[guideStep], compact ? 12 : 13, MUTED); subtitle.setGravity(Gravity.CENTER); add(root, subtitle, -1, -2, shortScreen ? 4 : 6);
        FrameLayout content = new FrameLayout(this); LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, 0, 1); cp.topMargin = dp(shortScreen ? 6 : compact ? 8 : 20); cp.bottomMargin = dp(shortScreen ? 4 : 8); root.addView(content, cp);
        guideMessage = text("", 13, MUTED); guideMessage.setGravity(Gravity.CENTER);
        if (guideStep >= 1 && guideStep <= 3) {
            calibrationView = new CalibrationView(this, guideStep, calibration, (message, done, hasData) -> {
                guideMessage.setText(message); if (guideNext != null) guideNext.setEnabled(done);
                if (guideSkip != null) guideSkip.setVisibility(hasData ? View.INVISIBLE : View.VISIBLE);
            });
            FrameLayout.LayoutParams cv = new FrameLayout.LayoutParams(-1, -1); cv.bottomMargin = dp(32); content.addView(calibrationView, cv);
            FrameLayout.LayoutParams message = new FrameLayout.LayoutParams(-1, dp(32), Gravity.BOTTOM); content.addView(guideMessage, message);
        } else if (guideStep == 0) {
            LinearLayout welcome = column(); welcome.setGravity(Gravity.CENTER);
            TextView wordmark = text("REFLECTION", compact ? 26 : 40, INK); wordmark.setTypeface(Typeface.create("serif", Typeface.NORMAL)); wordmark.setLetterSpacing(.16f); wordmark.setGravity(Gravity.CENTER);
            welcome.addView(wordmark); TextView note = text("一支笔。一块安静的画布。\n接下来，用几步完成适合这台设备的设置。", 14, MUTED); note.setGravity(Gravity.CENTER); add(welcome, note, -1, -2, 16); content.addView(welcome, new FrameLayout.LayoutParams(-1, -1));
        } else if (guideStep == 4) {
            LinearLayout install = column(); install.setGravity(Gravity.CENTER);
            install.addView(text("01  获取对应系统的宿主机\n02  安装并打开，启动输入服务\n03  macOS 按提示授予辅助功能权限", compact ? 12 : 16, INK), new LinearLayout.LayoutParams(-2, -2));
            Button repository = button("GitHub · Linloir / Reflection  ↗", () -> openUrl(REPOSITORY)); add(install, repository, -2, dp(compact ? 36 : 48), compact ? 8 : 14);
            TextView availability = text("仓库 README 提供各平台构建与安装说明；发布包以 Releases 为准。", compact ? 11 : 12, MUTED); availability.setGravity(Gravity.CENTER); add(install, availability, -1, -2, compact ? 4 : 8);
            content.addView(install, new FrameLayout.LayoutParams(-1, -1));
        } else {
            LinearLayout connect = column(); connect.setGravity(Gravity.CENTER);
            LinearLayout modes = row();
            connectionColumn(modes, "01  USB", "连接数据线，开启 USB 调试，\n在平板上允许这台电脑。");
            connectionColumn(modes, "02  Wi-Fi", "接入同一局域网，在宿主机\n找到平板并选择「信任并连接」。");
            connectionColumn(modes, "03  蓝牙", "系统配对电脑，允许附近设备。\n在电脑端信任后自动连接。");
            connect.addView(modes, new LinearLayout.LayoutParams(-1, -2));
            liveStatus = text(connectionText(), compact ? 11 : 12, ACCENT); liveStatus.setGravity(Gravity.CENTER); add(connect, liveStatus, -1, -2, compact ? 4 : 12);
            Button debug = button(debuggingEnabled() ? "调试已开启 · 查看设备设置" : "打开设备设置，开启调试", this::openDeviceSettings);
            add(connect, debug, -2, dp(compact ? 36 : 44), compact ? 4 : 8); content.addView(connect, new FrameLayout.LayoutParams(-1, -1));
        }
        LinearLayout footer = footer(); LinearLayout left = row(), right = row();
        Button back = button("← 返回", () -> { if (guideStep > 0) { guideStep--; showOnboarding(); } }); back.setVisibility(guideStep == 0 ? View.INVISIBLE : View.VISIBLE); left.addView(back);
        if (guideStep >= 1 && guideStep <= 3) left.addView(button("重做", () -> { calibration.clear(guideStep); surface = calibration.surface(); showOnboarding(); }));
        guideProgress = text(String.format(java.util.Locale.ROOT, "%02d  /  06", guideStep + 1), 13, MUTED); guideProgress.setGravity(Gravity.CENTER); guideProgress.setLetterSpacing(.12f);
        guideSkip = button("跳过", this::advanceGuide); guideSkip.setVisibility(guideStep >= 1 && guideStep <= 3 && !calibration.complete(guideStep) || guideStep == 5 && !controller.connected() ? View.VISIBLE : View.INVISIBLE); right.addView(guideSkip);
        guideNext = button(guideStep == 5 ? "完成  ✓" : "下一步  →", this::advanceGuide); primary(guideNext);
        guideNext.setEnabled(guideStep < 1 || guideStep > 3 ? guideStep != 5 || controller.connected() : calibration.complete(guideStep)); right.addView(guideNext);
        triptych(footer, left, guideProgress, right); root.addView(footer);
    }
    private void advanceGuide() {
        surface = calibration.surface();
        if (guideStep < 5) { guideStep++; showOnboarding(); return; }
        UiPreferences.store(this).edit().putBoolean("onboarded", true).putInt("guideStep", 0).apply();
        if (guideFromSettings) { guideFromSettings = false; showSettings(); } else showHome();
    }
    private void connectionColumn(LinearLayout parent, String name, String description) {
        boolean compact = getResources().getConfiguration().screenHeightDp < 420;
        LinearLayout col = column(); col.setPadding(dp(compact ? 8 : 16), dp(compact ? 4 : 8), dp(compact ? 8 : 16), dp(compact ? 4 : 8)); col.setBackground(outline(PAPER, LINE));
        col.addView(text(name + "  ↗", compact ? 13 : 16, INK)); add(col, text(description, compact ? 11 : 12, MUTED), -1, -2, compact ? 4 : 8);
        col.setFocusable(true); col.setClickable(true);
        col.setContentDescription(name + " · 打开系统设置");
        col.setOnClickListener(v -> {
            if (name.contains("蓝牙")) openBluetoothSettings();
            else if (name.contains("Wi-Fi")) launchSettings(Settings.ACTION_WIFI_SETTINGS);
            else openDeviceSettings();
        });
        parent.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
    }

    private interface RowMaker { View make(); }
    private void showSettings() {
        newPage("settings"); if (draft == null) draft = UiPreferences.load(this);
        LinearLayout header = row(); header.setGravity(Gravity.CENTER_VERTICAL);
        LineIcon icon = new LineIcon(this, "settings"); header.addView(icon, new LinearLayout.LayoutParams(dp(38), dp(38)));
        LinearLayout heading = column(); heading.setPadding(dp(14), 0, 0, 0); heading.addView(text("设置 / " + SECTIONS[section], 24, INK));
        heading.addView(text("调整你的书写空间。更改将在保存后生效。", 12, MUTED)); header.addView(heading); root.addView(header); addRule(root, 14);
        List<RowMaker> rows = settingsRows();
        int extraNavigation = getResources().getConfiguration().screenWidthDp < 620 ? 44 : 0;
        int rowsPerPage = Math.max(1, Math.min(4, (getResources().getConfiguration().screenHeightDp - 158 - extraNavigation) / 76));
        int pages = Math.max(1, (rows.size() + rowsPerPage - 1) / rowsPerPage); settingsPage = Math.max(0, Math.min(pages - 1, settingsPage));
        settingsBody = column(); root.addView(settingsBody, new LinearLayout.LayoutParams(-1, 0, 1));
        int start = settingsPage * rowsPerPage;
        for (int i = start; i < Math.min(rows.size(), start + rowsPerPage); i++) {
            settingsBody.addView(rows.get(i).make(), new LinearLayout.LayoutParams(-1, 0, 1)); addRule(settingsBody, 0);
        }
        LinearLayout footer = footer(), actions = row(), nav = row(), paging = row();
        actions.addView(button("← 取消", () -> { draft = null; showHome(); }));
        Button save = button("保存 ✓", () -> {
            boolean collapseChanged = draft.collapsed != prefs.collapsed;
            draft.save(this); prefs = UiPreferences.load(this); draft = null;
            if (collapseChanged) toolbarCollapsed = prefs.collapsed;
            showHome();
        }); primary(save); actions.addView(save);
        boolean compact = getResources().getConfiguration().screenWidthDp < 850;
        for (int i = 0; i < SECTIONS.length; i++) {
            final int index = i;
            Button tab = button(compact ? new String[]{"区域", "手势", "墨水", "连接", "校准", "关于"}[i] : SECTIONS[i], () -> { section = index; settingsPage = 0; showSettings(); });
            tab.setTextSize(compact ? 11 : 13); tab.setMinWidth(0); tab.setPadding(dp(compact ? 7 : 12), 0, dp(compact ? 7 : 12), 0);
            if (i == section) primary(tab); nav.addView(tab, new LinearLayout.LayoutParams(-2, dp(44)));
        }
        if (pages > 1) {
            Button previous = button("↑", () -> { settingsPage--; showSettings(); }); previous.setEnabled(settingsPage > 0); previous.setContentDescription("上一页"); paging.addView(previous);
            TextView count = text((settingsPage + 1) + "/" + pages, 12, MUTED); count.setGravity(Gravity.CENTER); paging.addView(count, new LinearLayout.LayoutParams(dp(36), dp(44)));
            Button next = button("↓", () -> { settingsPage++; showSettings(); }); next.setEnabled(settingsPage + 1 < pages); next.setContentDescription("下一页"); paging.addView(next);
        }
        // On narrow screens the section strip gets its own fixed row, never a horizontal scroll area.
        if (getResources().getConfiguration().screenWidthDp < 620) { nav.setGravity(Gravity.CENTER); root.addView(nav); triptych(footer, actions, text("", 12, MUTED), paging); }
        else triptych(footer, actions, nav, paging);
        root.addView(footer);
    }
    private List<RowMaker> settingsRows() {
        List<RowMaker> rows = new ArrayList<>();
        if (section == 0) {
            rows.add(() -> setting("操作栏位置", "贴近惯用手，保留完整画布。", choice(draft.toolbar, new String[]{"bottom", "left"}, new String[]{"下方", "左侧"}, v -> draft.toolbar = v)));
            rows.add(() -> setting("默认收起操作栏", "仍可通过菜单按钮随时展开。", toggle(draft.collapsed, v -> draft.collapsed = v)));
            rows.add(() -> setting("画布延伸到操作栏后方", "关闭时为操作栏单独保留空间。", toggle(draft.overlay, v -> draft.overlay = v)));
            rows.add(() -> setting("操作区域边框", "使用细线标明笔输入的边界。", toggle(draft.border, v -> draft.border = v)));
            rows.add(() -> setting("显示屏幕边界", "在映射全部屏幕时描出显示器分区。", toggle(draft.screenBorders, v -> draft.screenBorders = v)));
            rows.add(() -> setting("四周留白", "边界手势需要四周至少 8 dp 的留白。", numberChoice(draft.margin, new int[]{0, 12, 24, 40, 64}, " dp", v -> draft.margin = v)));
            rows.add(() -> setting("操作区域范围", "拖动选择一块区域，或恢复全部范围。", button("选择区域 →", this::showRegion)));
            rows.add(() -> setting("画布背景", "用点与线定位，保留安静的书写空间。", choice(draft.background, new String[]{"blank", "dots", "grid"}, new String[]{"空白", "点阵", "网格"}, v -> draft.background = v)));
            rows.add(() -> setting("背景间距", "点阵与网格共用此尺寸。", numberChoice(draft.spacing, new int[]{12, 20, 24, 32, 48}, " dp", v -> draft.spacing = v)));
            rows.add(() -> setting("工具栏 · 屏幕切换", "显示屏幕选择入口。", toggle(draft.toolScreens, v -> draft.toolScreens = v)));
            rows.add(() -> setting("工具栏 · 清空", "显示清空本地笔迹按钮。", toggle(draft.toolClear, v -> draft.toolClear = v)));
            rows.add(() -> setting("工具栏 · 笔迹", "显示本地笔迹开关。", toggle(draft.toolPreview, v -> draft.toolPreview = v)));
            rows.add(() -> setting("工具栏 · 触摸板", "显示触摸板快捷开关。", toggle(draft.toolTouchpad, v -> draft.toolTouchpad = v)));
        } else if (section == 1) {
            rows.add(() -> setting("倾斜角方案", "自动识别已验证的设备格式，或使用标准 Android 轴。", choice(draft.tiltMode, new String[]{"auto", "standard", "off"}, new String[]{"自动", "标准", "关闭"}, v -> draft.tiltMode = v)));
            rows.add(() -> setting("触摸板", controller.connected() && !controller.touchpadSupported()
                ? "当前宿主机未启用触摸扩展，请升级宿主机。"
                : "单指移动与轻点；双指右键、滚动与缩放。", toggle(draft.touchpad, v -> draft.touchpad = v)));
            rows.add(() -> setting("多指手势", controller.connected() && controller.rawTouchpadSupported()
                ? "多指接触交给 Windows 原生触摸板处理；滚动、缩放与系统手势遵循电脑设置。"
                : "三／四指轻扫使用系统切换快捷操作；具体缩放取决于目标应用。",
                text(controller.connected() && controller.rawTouchpadSupported() ? "Windows 原生触摸板" : "兼容手势", 12, MUTED)));
            rows.add(() -> setting("边界切换屏幕", "仅在单屏 focus、边框开启且四周留白时生效。", toggle(draft.edgeSwitch, v -> draft.edgeSwitch = v)));
            rows.add(() -> setting("切换手势", "用笔在边界操作，按显示器实际方位切换。", choice(draft.edgeGesture, new String[]{"hover", "tap", "double", "triple", "button"}, new String[]{"悬停", "单击", "双击", "三击", "笔侧键"}, v -> draft.edgeGesture = v)));
            rows.add(() -> setting("悬停等待时间", "持续停留后切换，避免经过边界时误触。", numberChoice(draft.hoverDelay, new int[]{500, 800, 1200, 1600}, " ms", v -> draft.hoverDelay = v)));
        } else if (section == 2) {
            rows.add(() -> setting("本地笔迹", "在平板上显示书写轨迹，不影响电脑端输入。", toggle(draft.localPreview, v -> draft.localPreview = v)));
            rows.add(() -> setting("电子纸优化", "自动识别设备；可手动开启或关闭。", choice(draft.eink, new String[]{"auto", "on", "off"}, new String[]{"自动", "开启", "关闭"}, v -> draft.eink = v)));
            rows.add(() -> setting("当前模式", "电子纸按区域请求刷新，以离散灰阶消散笔迹。", text(EinkRefresh.isEnabled(this, draft.eink) ? "电子纸" : "标准显示", 14, ACCENT)));
            rows.add(() -> setting("渐隐笔迹", "抬笔后等待，再从笔迹末尾逐渐消散。开启时同时显示本地笔迹。", toggle(draft.fading, v -> { draft.fading = v; if (v) draft.localPreview = true; })));
            rows.add(() -> setting("笔迹停留时间", "保留短暂的视觉反馈。", numberChoice(draft.fadeDelay, new int[]{1000, 1500, 2000}, " ms", v -> draft.fadeDelay = v)));
            rows.add(() -> setting("刷新适配", "已支持的厂商接口独立加载；不可用时回退到标准局部重绘。", text(new EinkRefresh(root, draft.eink).driverName(), 12, MUTED)));
        } else if (section == 3) {
            rows.add(() -> { liveStatus = text(connectionText(), 12, ACCENT); return setting("自动发现与连接", "USB > Wi-Fi > 蓝牙；连接恢复后自动选优。", liveStatus); });
            rows.add(() -> setting("这台设备的配对码", "在电脑端选择同名设备并核对配对码，然后信任并连接。", text(controller.pairingCode(), 22, INK)));
            rows.add(() -> setting("已信任的电脑", "信任按电脑保存，换电脑不需要重新输入密钥。", text(controller.trustedHostCount() + " 台", 16, INK)));
            rows.add(() -> setting("USB 调试", debuggingEnabled() ? "USB 调试已开启。连接数据线后，在系统弹窗中授权电脑。" : "进入关于设备，连续点击版本号，再在开发者选项中开启 USB 调试。", button("打开设备设置 ↗", this::openDeviceSettings)));
            rows.add(() -> setting("蓝牙", "先在系统中配对电脑，再允许附近设备权限。", button("配对与权限 ↗", this::openBluetoothSettings)));
            rows.add(() -> setting("宿主机安装", "Windows / macOS 的安装、启动与权限说明。", button("打开 GitHub ↗", () -> openUrl(REPOSITORY))));
        } else if (section == 4) {
            rows.add(() -> setting("坐标与噪声", calibration.complete(1) ? "已保存五点校准。宿主机负责笔输入防抖。" : "依次按住多个点，测量坐标偏差和静止抖动。", button("开始校准 →", () -> beginCalibration(1))));
            rows.add(() -> setting("上下倾斜", calibration.complete(2) ? "已匹配上下倾斜方向。" : "笔尖原位，上下倾斜以匹配设备轴。", button("开始校准 →", () -> beginCalibration(2))));
            rows.add(() -> setting("左右倾斜", calibration.complete(3) ? "已匹配左右倾斜方向。" : "独立校准左右方向，不推测硬件未提供的轴。", button("开始校准 →", () -> beginCalibration(3))));
            rows.add(() -> setting("重新查看引导", "校准数据会保留，只有重做才会清空对应项。", button("打开引导 →", () -> beginCalibration(0))));
        } else {
            rows.add(() -> setting("Reflection", "让 Android 平板与电子纸成为电脑的手写输入设备。", text(version(), 16, INK)));
            rows.add(() -> setting("开发者", "Linloir · 3145078758@qq.com", button("联系 ↗", () -> openUrl("mailto:3145078758@qq.com"))));
            rows.add(() -> setting("源代码", "github.com/Linloir/Reflection", button("查看仓库 ↗", () -> openUrl(REPOSITORY))));
            rows.add(() -> setting("开源许可", "Copyright © 2026 Linloir", button("MIT License ↗", () -> openUrl(REPOSITORY + "/blob/main/LICENSE"))));
        }
        return rows;
    }
    private void beginCalibration(int step) { guideFromSettings = true; guideStep = step; showOnboarding(); }
    private void showRegion() {
        newPage("region"); root.addView(text("定义你的操作区域", 26, INK)); add(root, text("在预览中拖动选择。宽高至少占 20%；留白仍会叠加到这个范围。", 13, MUTED), -1, -2, 8);
        RegionEditorView editor = new RegionEditorView(this, draft); LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-1, 0, 1); ep.topMargin = ep.bottomMargin = dp(24); root.addView(editor, ep);
        LinearLayout footer = footer(); footer.addView(button("← 返回设置", this::showSettings)); View spacer = new View(this); footer.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
        footer.addView(button("恢复全部区域", () -> { draft.left = draft.top = 0; draft.right = draft.bottom = 1; editor.invalidate(); })); root.addView(footer);
    }
    private void showScreens() {
        newPage("screens"); root.addView(text("屏幕 / Focus", 26, INK)); add(root, text("点选一块屏幕。正在书写时，切换会等待抬笔后生效。", 13, MUTED), -1, -2, 8);
        screenDiagram = new MappingView(this, id -> switchMapping("select", id)); screenDiagram.update(controller.mapping());
        root.addView(screenDiagram, new LinearLayout.LayoutParams(-1, 0, 1));
        MappingState state = controller.mapping(); LinearLayout choices = row(); choices.setGravity(Gravity.CENTER);
        if (state != null) {
            int perPage = Math.max(2, getResources().getConfiguration().screenWidthDp / 160), pages = (state.displays.size() + perPage - 1) / perPage;
            screenPage = Math.min(screenPage, pages - 1);
            for (int i = screenPage * perPage; i < Math.min(state.displays.size(), (screenPage + 1) * perPage); i++) {
                MappingState.Display d = state.displays.get(i); Button b = button(d.name, () -> switchMapping("select", d.id)); b.setMaxLines(1);
                if (d.id.equals(state.selected)) primary(b); choices.addView(b, new LinearLayout.LayoutParams(0, dp(48), 1));
            }
            if (pages > 1) choices.addView(button("更多 →", () -> { screenPage = (screenPage + 1) % pages; showScreens(); }));
        } else choices.addView(text("连接宿主机后，显示器布局会出现在这里。", 14, MUTED));
        root.addView(choices);
        LinearLayout footer = footer(), left = row(), right = row(); left.addView(button("← 返回", this::showHome));
        Button all = button("全部屏幕", () -> switchMapping("all", null)); all.setEnabled(state != null); right.addView(all);
        TextView label = text(state == null ? "等待宿主机" : state.label(), 12, MUTED); label.setGravity(Gravity.CENTER);
        triptych(footer, left, label, right); root.addView(footer);
    }
    private void switchMapping(String action, String id) {
        try { controller.requestMapping(action, id); } catch (Exception error) { toast(error.getMessage()); }
    }
    private void checkPairing() {
        String pending = controller.pendingPairingSummary();
        if (pending == null || pending.isEmpty()) { if (pairingDialog != null) { pairingDialog.dismiss(); pairingDialog = null; } displayedPairing = ""; return; }
        if (pending.equals(displayedPairing)) return;
        displayedPairing = pending;
        pairingDialog = new AlertDialog.Builder(this).setTitle("信任这台电脑？")
            .setMessage(pending + "\n确认这是你正在电脑端连接的宿主机。以后会自动重连。")
            .setNegativeButton("拒绝", (d, which) -> controller.rejectPendingPairing())
            .setPositiveButton("信任并连接", (d, which) -> controller.approvePendingPairing()).setCancelable(false).create();
        pairingDialog.setOnDismissListener(d -> pairingDialog = null); pairingDialog.show();
    }
    private String connectionText() { return controller.connectionSummary(); }
    private boolean debuggingEnabled() { return Settings.Global.getInt(getContentResolver(), Settings.Global.ADB_ENABLED, 0) != 0; }
    private void openDeviceSettings() {
        toast("在关于设备中连续点击版本号，然后到开发者选项开启 USB 调试。");
        launchSettings(Settings.ACTION_DEVICE_INFO_SETTINGS);
    }
    private void openBluetoothSettings() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 10);
        else launchSettings(Settings.ACTION_BLUETOOTH_SETTINGS);
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == 10 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) launchSettings(Settings.ACTION_BLUETOOTH_SETTINGS);
    }
    private void launchSettings(String action) {
        try { startActivity(new Intent(action)); } catch (Exception unavailable) { try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored) { toast("无法打开系统设置。"); } }
    }
    private void openUrl(String uri) { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(uri))); } catch (Exception unavailable) { toast("没有可打开此链接的应用。"); } }
    private String version() { try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { return "Reflection"; } }

    private interface BoolSetter { void set(boolean value); }
    private interface StringSetter { void set(String value); }
    private interface IntSetter { void set(int value); }
    private Button toggle(boolean value, BoolSetter setter) {
        Button b = button(value ? "开启  ■" : "关闭  □", () -> { setter.set(!value); showSettings(); }); if (value) primary(b); return b;
    }
    private Button choice(String current, String[] values, String[] labels, StringSetter setter) {
        int found = 0; for (int i = 0; i < values.length; i++) if (values[i].equals(current)) found = i;
        final int index = found; return button(labels[index] + "  ›", () -> { setter.set(values[(index + 1) % values.length]); showSettings(); });
    }
    private Button numberChoice(int current, int[] values, String suffix, IntSetter setter) {
        int found = 0; for (int i = 0; i < values.length; i++) if (values[i] == current) found = i;
        final int index = found; return button(current + suffix + "  ›", () -> { setter.set(values[(index + 1) % values.length]); showSettings(); });
    }
    private View setting(String title, String detail, View control) {
        LinearLayout row = row(); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(4), dp(8), dp(4), dp(8));
        LinearLayout label = column(); label.addView(text(title, 17, INK)); TextView description = text(detail, 12, MUTED); add(label, description, -1, -2, 4);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2); cp.leftMargin = dp(20); row.addView(control, cp);
        if (control instanceof TextView) ((TextView) control).setMaxWidth(dp(340));
        return row;
    }
    private void iconButton(LinearLayout parent, String icon, String description, Runnable action, boolean selected) {
        FrameLayout b = new FrameLayout(this); b.setContentDescription(description); b.setFocusable(true); b.setClickable(true);
        b.setBackground(outline(selected ? 0xffe4eadf : PAPER, selected ? ACCENT : PAPER));
        LineIcon view = new LineIcon(this, icon); FrameLayout.LayoutParams vp = new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER); b.addView(view, vp);
        b.setOnClickListener(v -> action.run()); b.setOnLongClickListener(v -> { toast(description); return true; });
        parent.addView(b, new LinearLayout.LayoutParams(dp(48), dp(48)));
    }
    private void separator(LinearLayout parent, boolean verticalToolbar) {
        View line = new View(this); line.setBackgroundColor(LINE); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(verticalToolbar ? dp(28) : 1, verticalToolbar ? 1 : dp(28));
        p.gravity = Gravity.CENTER; p.setMargins(dp(4), dp(4), dp(4), dp(4)); parent.addView(line, p);
    }
    private LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout row() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.HORIZONTAL); return v; }
    private TextView text(String value, float size, int color) { TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color); v.setFontFeatureSettings("kern"); v.setLineSpacing(dp(2), 1); return v; }
    private Button button(String value, Runnable action) {
        Button b = new Button(this); b.setText(value); b.setAllCaps(false); b.setTextSize(13); b.setTextColor(INK);
        b.setMinHeight(dp(44)); b.setMinimumHeight(dp(44)); b.setMinWidth(dp(44)); b.setMinimumWidth(dp(44)); b.setPadding(dp(14), 0, dp(14), 0);
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_pressed}, outline(0xffe4eadf, ACCENT));
        background.addState(new int[]{}, outline(PAPER, LINE));
        b.setBackground(background); b.setStateListAnimator(null); b.setElevation(0); b.setOnClickListener(v -> action.run());
        android.content.res.ColorStateList colors = new android.content.res.ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}}, new int[]{0xffb0b6ae, INK}); b.setTextColor(colors);
        return b;
    }
    private void primary(Button b) {
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{-android.R.attr.state_enabled}, outline(PAPER, LINE));
        background.addState(new int[]{android.R.attr.state_pressed}, outline(ACCENT, ACCENT));
        background.addState(new int[]{}, outline(INK, INK));
        b.setBackground(background); b.setTextColor(new android.content.res.ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}}, new int[]{0xffa1a89c, Color.WHITE}));
    }
    private GradientDrawable outline(int fill, int stroke) { GradientDrawable d = new GradientDrawable(); d.setColor(fill); d.setCornerRadius(0); d.setStroke(dp(1), stroke); return d; }
    private LinearLayout footer() { LinearLayout v = row(); v.setGravity(Gravity.CENTER_VERTICAL); v.setPadding(0, dp(12), 0, 0); return v; }
    private void triptych(LinearLayout footer, View left, View center, View right) {
        int unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        left.measure(unspecified, unspecified); right.measure(unspecified, unspecified);
        int sideWidth = Math.max(left.getMeasuredWidth(), right.getMeasuredWidth());
        if (left instanceof LinearLayout) ((LinearLayout) left).setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        if (right instanceof LinearLayout) ((LinearLayout) right).setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        footer.addView(left, new LinearLayout.LayoutParams(sideWidth, dp(48)));
        FrameLayout middle = new FrameLayout(this); middle.addView(center, new FrameLayout.LayoutParams(-2, -1, Gravity.CENTER)); footer.addView(middle, new LinearLayout.LayoutParams(0, dp(48), 1));
        footer.addView(right, new LinearLayout.LayoutParams(sideWidth, dp(48)));
    }
    private void addRule(LinearLayout parent, int margin) { View line = new View(this); line.setBackgroundColor(LINE); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, 1); p.topMargin = dp(margin); parent.addView(line, p); }
    private void add(LinearLayout parent, View child, int width, int height, int top) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width, height); p.topMargin = dp(top); parent.addView(child, p); }
    private void setTextIfChanged(TextView view, String text) { if (!text.contentEquals(view.getText())) view.setText(text); }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }
    private float clamp(float value) { return Math.max(0, Math.min(1, value)); }
}
