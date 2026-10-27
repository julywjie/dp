package com.dp.agent.ui;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.W32APIOptions;
import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.Locale;

/**
 * Windows 原生标题栏与窗口边框配色。
 *
 * JavaFX 的 CSS 只能画客户区，系统标题栏依旧是浅色，与深色界面形成一条突兀的白边。
 * 这里直接调用 DWM：深色模式开关 + 标题栏颜色 + 边框颜色，让非客户区跟随主题。
 * 仅 Windows 生效；其他平台/旧系统调用失败时静默忽略。
 */
final class WindowsTitleBar {

    private static final int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;
    private static final int DWMWA_USE_IMMERSIVE_DARK_MODE_OLD = 19;
    private static final int DWMWA_BORDER_COLOR = 34;
    private static final int DWMWA_CAPTION_COLOR = 35;

    /** COLORREF 为 0x00BBGGRR，与 CSS 的 #RRGGBB 字节序相反。 */
    private static final int CAPTION_DARK = colorRef(0x16213a);  // -app-surface（深色）
    private static final int CAPTION_LIGHT = colorRef(0xffffff);
    private static final int BORDER_DARK = colorRef(0x2a3a54);
    private static final int BORDER_LIGHT = colorRef(0xe2e8f0);

    private WindowsTitleBar() {
    }

    static void apply(Stage stage, Window owner) {
        boolean dark = owner != null && owner.getScene() != null
                && owner.getScene().getRoot().getStyleClass().contains("theme-dark");
        apply(stage, dark);
    }

    static void apply(Stage stage, boolean dark) {
        if (stage == null || !isWindows()) {
            return;
        }
        // 等窗口真正显示后再取 HWND；FindWindow 依赖标题，attachStage 已先设置标题。
        Platform.runLater(() -> {
            if (!stage.isShowing()) {
                return;
            }
            Pointer hwnd = User32.INSTANCE.FindWindow(null, stage.getTitle());
            if (hwnd == null) {
                return;
            }
            IntByReference enabled = new IntByReference(dark ? 1 : 0);
            if (Dwmapi.INSTANCE.DwmSetWindowAttribute(hwnd,
                    DWMWA_USE_IMMERSIVE_DARK_MODE, enabled.getPointer(), 4) != 0) {
                // Windows 10 1903–2004 使用属性 19
                Dwmapi.INSTANCE.DwmSetWindowAttribute(hwnd,
                        DWMWA_USE_IMMERSIVE_DARK_MODE_OLD, enabled.getPointer(), 4);
            }
            IntByReference caption = new IntByReference(dark ? CAPTION_DARK : CAPTION_LIGHT);
            IntByReference border = new IntByReference(dark ? BORDER_DARK : BORDER_LIGHT);
            Dwmapi.INSTANCE.DwmSetWindowAttribute(hwnd,
                    DWMWA_CAPTION_COLOR, caption.getPointer(), 4);
            Dwmapi.INSTANCE.DwmSetWindowAttribute(hwnd,
                    DWMWA_BORDER_COLOR, border.getPointer(), 4);
        });
    }

    private static int colorRef(int rgb) {
        int r = (rgb >> 16) & 0xff;
        int g = (rgb >> 8) & 0xff;
        int b = rgb & 0xff;
        return (b << 16) | (g << 8) | r;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private interface User32 extends Library {
        User32 INSTANCE = Native.load("user32", User32.class, W32APIOptions.DEFAULT_OPTIONS);

        Pointer FindWindow(String className, String windowName);
    }

    private interface Dwmapi extends Library {
        Dwmapi INSTANCE = Native.load("dwmapi", Dwmapi.class);

        int DwmSetWindowAttribute(Pointer hwnd, int attribute, Pointer value, int size);
    }
}
