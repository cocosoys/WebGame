package com.github.cocosoys.mc.webgame.web.cloud;

import java.awt.Robot;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AWT Robot 桌面输入注入（可选，默认关闭）。
 *
 * <p>把浏览器 Pointer Lock 的<b>相对位移</b>转换为鼠标移动、按键事件转换为
 * AWT key code（用 Java 内置映射，中文/全角键盘由 AWT 自身处理）。
 * 仅用于 Windows/Linux 有图形会话的宿主机端到端验证；游戏实例容器化后
 * 应替换为容器内 uinput/XTest 注入后端（后续与用户讨论）。</p>
 */
final class RobotInputInjector implements InputInjector {

    private final Robot robot;
    /** 鼠标按键按下状态，避免重复按下。 */
    private final boolean[] mouseDown = new boolean[8];

    RobotInputInjector() throws Exception {
        this.robot = new Robot();
        robot.setAutoDelay(0);
    }

    @Override
    public void dispatch(List<CloudProtocol.InputEvent> events) {
        for (CloudProtocol.InputEvent e : events) {
            try {
                switch (e.type) {
                    case CloudProtocol.EVENT_MOUSE_MOVE: {
                        java.awt.Point p = java.awt.MouseInfo.getPointerInfo() == null
                                ? new java.awt.Point(0, 0)
                                : java.awt.MouseInfo.getPointerInfo().getLocation();
                        robot.mouseMove(p.x + e.dx, p.y + e.dy);
                        break;
                    }
                    case CloudProtocol.EVENT_MOUSE_BUTTON:
                        dispatchMouseButton(e.button, e.pressed);
                        break;
                    case CloudProtocol.EVENT_KEY:
                        dispatchKey(e.code, e.pressed);
                        break;
                    case CloudProtocol.EVENT_WHEEL:
                        robot.mouseWheel(-e.delta);
                        break;
                    default:
                        break;
                }
            } catch (Throwable ignored) {
                // 注入失败不影响会话存活
            }
        }
    }

    private void dispatchMouseButton(int button, boolean pressed) {
        int mask;
        switch (button) {
            case 0:
                mask = InputEvent.BUTTON1_DOWN_MASK;
                break;
            case 1:
                mask = InputEvent.BUTTON2_DOWN_MASK;
                break;
            case 2:
                mask = InputEvent.BUTTON3_DOWN_MASK;
                break;
            default:
                return;
        }
        if (pressed && !mouseDown[button]) {
            robot.mousePress(mask);
            mouseDown[button] = true;
        } else if (!pressed && mouseDown[button]) {
            robot.mouseRelease(mask);
            mouseDown[button] = false;
        }
    }

    private void dispatchKey(String code, boolean pressed) {
        Integer key = KEY_MAP.get(code);
        if (key == null) {
            return;
        }
        if (pressed) {
            robot.keyPress(key);
        } else {
            robot.keyRelease(key);
        }
    }

    @Override
    public void close() {
        for (int i = 0; i < mouseDown.length; i++) {
            if (mouseDown[i]) {
                try {
                    dispatchMouseButton(i, false);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 浏览器 KeyboardEvent.code → AWT 虚拟键码（常用键映射）。 */
    private static final Map<String, Integer> KEY_MAP = new HashMap<>();

    static {
        String[] letters = {"KeyA", "KeyB", "KeyC", "KeyD", "KeyE", "KeyF", "KeyG", "KeyH",
                "KeyI", "KeyJ", "KeyK", "KeyL", "KeyM", "KeyN", "KeyO", "KeyP",
                "KeyQ", "KeyR", "KeyS", "KeyT", "KeyU", "KeyV", "KeyW", "KeyX", "KeyY", "KeyZ"};
        int[] vks = {KeyEvent.VK_A, KeyEvent.VK_B, KeyEvent.VK_C, KeyEvent.VK_D, KeyEvent.VK_E,
                KeyEvent.VK_F, KeyEvent.VK_G, KeyEvent.VK_H, KeyEvent.VK_I, KeyEvent.VK_J,
                KeyEvent.VK_K, KeyEvent.VK_L, KeyEvent.VK_M, KeyEvent.VK_N, KeyEvent.VK_O,
                KeyEvent.VK_P, KeyEvent.VK_Q, KeyEvent.VK_R, KeyEvent.VK_S, KeyEvent.VK_T,
                KeyEvent.VK_U, KeyEvent.VK_V, KeyEvent.VK_W, KeyEvent.VK_X, KeyEvent.VK_Y, KeyEvent.VK_Z};
        for (int i = 0; i < letters.length; i++) {
            KEY_MAP.put(letters[i], vks[i]);
        }
        String[] digits = {"Digit0", "Digit1", "Digit2", "Digit3", "Digit4",
                "Digit5", "Digit6", "Digit7", "Digit8", "Digit9"};
        int[] vkd = {KeyEvent.VK_0, KeyEvent.VK_1, KeyEvent.VK_2, KeyEvent.VK_3, KeyEvent.VK_4,
                KeyEvent.VK_5, KeyEvent.VK_6, KeyEvent.VK_7, KeyEvent.VK_8, KeyEvent.VK_9};
        for (int i = 0; i < digits.length; i++) {
            KEY_MAP.put(digits[i], vkd[i]);
        }
        KEY_MAP.put("Space", KeyEvent.VK_SPACE);
        KEY_MAP.put("Enter", KeyEvent.VK_ENTER);
        KEY_MAP.put("Tab", KeyEvent.VK_TAB);
        KEY_MAP.put("Backspace", KeyEvent.VK_BACK_SPACE);
        KEY_MAP.put("Escape", KeyEvent.VK_ESCAPE);
        KEY_MAP.put("ShiftLeft", KeyEvent.VK_SHIFT);
        KEY_MAP.put("ShiftRight", KeyEvent.VK_SHIFT);
        KEY_MAP.put("ControlLeft", KeyEvent.VK_CONTROL);
        KEY_MAP.put("ControlRight", KeyEvent.VK_CONTROL);
        KEY_MAP.put("AltLeft", KeyEvent.VK_ALT);
        KEY_MAP.put("AltRight", KeyEvent.VK_ALT);
        KEY_MAP.put("MetaLeft", KeyEvent.VK_META);
        KEY_MAP.put("MetaRight", KeyEvent.VK_META);
        KEY_MAP.put("ArrowUp", KeyEvent.VK_UP);
        KEY_MAP.put("ArrowDown", KeyEvent.VK_DOWN);
        KEY_MAP.put("ArrowLeft", KeyEvent.VK_LEFT);
        KEY_MAP.put("ArrowRight", KeyEvent.VK_RIGHT);
        KEY_MAP.put("F1", KeyEvent.VK_F1);
        KEY_MAP.put("F2", KeyEvent.VK_F2);
        KEY_MAP.put("F3", KeyEvent.VK_F3);
        KEY_MAP.put("F4", KeyEvent.VK_F4);
        KEY_MAP.put("F5", KeyEvent.VK_F5);
        KEY_MAP.put("F6", KeyEvent.VK_F6);
        KEY_MAP.put("F7", KeyEvent.VK_F7);
        KEY_MAP.put("F8", KeyEvent.VK_F8);
        KEY_MAP.put("F9", KeyEvent.VK_F9);
        KEY_MAP.put("F10", KeyEvent.VK_F10);
        KEY_MAP.put("F11", KeyEvent.VK_F11);
        KEY_MAP.put("F12", KeyEvent.VK_F12);
    }
}
