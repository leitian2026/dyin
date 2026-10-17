package com.danmaku.randcolor;

import android.app.Activity;
import android.graphics.Rect;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 把“点赞”改成“打开评论”。两个入口，各有开关：
 *  1) 右侧爱心按钮：TextRec 记录 #316 的栈是 View.performClick → onClick → FeedDiggPresenter#LJJJJJL → …（40.7.0，方法名混淆，类名没混淆）。
 *     拦掉 LJJJJJL（不让它发起点赞），改为点一下评论按钮。LJJLIIIJL 是点赞后刷新界面的那一步，拦它拦不住点赞请求，所以不挂它。
 *  2) 双击视频（实验）：入口还没抓到过，所以用通用办法——监听 GestureDetector 的双击回调，命中就拦下并打开评论。
 *     抖音如果没用 GestureDetector 做双击，这个开关不会起作用；“检查状态”里会列出每个触发过的双击监听类，用来定位。
 * 评论按钮的找法：当前界面里 contentDescription 形如“评论24，按钮”、可见面积最大的那个（TextRec 记录：LinearLayout id=e9l，
 * 父级 HPFrameLayout#comment_layout），然后对它（或最近有点击监听的父 / 子）performClick。
 * 找不到评论按钮时一律不拦截，原来的点赞照常，不会出现“点了什么都没发生”。
 */
final class CommentHook {
    private CommentHook() {}

    static volatile WeakReference<Activity> top = new WeakReference<>(null);

    private static final String DIGG = "com.ss.android.ugc.aweme.feed.quick.presenter.FeedDiggPresenter";
    /** 版本相关：40.7.0 里右侧爱心按钮 onClick 调到的方法。换版本后用 TextRec 重新记录一次，把新名字改在这里。 */
    private static final String DIGG_CLICK_METHOD = "LJJJJJL";

    /** 点赞后刷新界面那一步（#316 / #545 都经过它）。只用来诊断：双击之后它又被调用，就把栈记下来，找真正的点赞入口。 */
    private static final String DIGG_UI_METHOD = "LJJLIIIJL";

    private static boolean installed;
    private static volatile boolean diggHooked;
    /** 最近一次识别到双击的时间（不管开关开没开），用来诊断。 */
    private static volatile long lastDtapAt;
    /** 最近一次我们接管双击并打开评论的时间；同一次双击里多个回调 / 多个监听只打开一次。 */
    private static volatile long lastOpenAt;
    /** 本次双击的后续事件（移动、抬起）也要一起拦掉。 */
    private static volatile boolean dtapBlocking;
    private static final Set<Class<?>> TAP_HOOKED = Collections.synchronizedSet(new HashSet<Class<?>>());
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> LOGGED = new java.util.concurrent.ConcurrentHashMap<>();

    static synchronized void install() {
        if (installed) return;
        installed = true;
        try {
            XC_MethodHook scan = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        if (p.args != null) for (Object a : p.args) hookListener(a);
                    } catch (Throwable ignored) {
                    }
                }
            };
            for (Constructor<?> c : GestureDetector.class.getDeclaredConstructors()) XposedBridge.hookMethod(c, scan);
            XposedBridge.hookMethod(GestureDetector.class.getDeclaredMethod("setOnDoubleTapListener",
                    GestureDetector.OnDoubleTapListener.class), scan);
        } catch (Throwable t) {
            DanmakuHook.status("挂接 GestureDetector 失败：" + t);
        }
    }

    // ------------------------------------------------------------ 1) 右侧爱心按钮

    /** 需要目标 App 的类加载器；启动时试一次，之后每次回到前台再试，直到挂上。 */
    static void tryHookDigg(ClassLoader cl) {
        if (diggHooked || cl == null) return;
        try {
            Class<?> k = Class.forName(DIGG, false, cl);
            int n = 0;
            for (Method m : k.getDeclaredMethods()) {
                if (!m.getName().equals(DIGG_CLICK_METHOD)) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            if (!DanmakuHook.cfg.likeBtnToComment) return;
                            // 只拦“用户点出来的”：这个方法如果在切视频 / 绑定数据 / 收到服务器结果时也会被调用，
                            // 不加这个判断就会自己弹出评论。栈里没有点击或触摸事件的一律放行。
                            if (!fromUserInput()) return;
                            logOnce("btn", "点赞入口被触发：" + callers());
                            if (openComments()) p.setResult(defaultOf(((Method) p.method).getReturnType()));
                        } catch (Throwable ignored) {
                        }
                    }
                });
                n++;
            }
            for (Method m : k.getDeclaredMethods()) {
                if (!m.getName().equals(DIGG_UI_METHOD)) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            if (android.os.SystemClock.uptimeMillis() - lastDtapAt > 1500) return;
                            String c = callers();
                            logOnce("after-tap:" + c.hashCode(), "双击后点赞仍被触发：" + c);
                        } catch (Throwable ignored) {
                        }
                    }
                });
            }
            diggHooked = true;
            DanmakuHook.status(n > 0 ? "已挂接 FeedDiggPresenter#" + DIGG_CLICK_METHOD + "（" + n + " 个重载）"
                    : "FeedDiggPresenter 里没有叫 " + DIGG_CLICK_METHOD + " 的方法：抖音版本变了，需要用 TextRec 重新记录点赞入口");
        } catch (ClassNotFoundException e) {
            // 类还没加载或这个版本没有：下次回到前台再试
        } catch (Throwable t) {
            diggHooked = true;
            DanmakuHook.status("挂接 FeedDiggPresenter 失败：" + t);
        }
    }

    private static Object defaultOf(Class<?> t) {
        if (t == boolean.class) return Boolean.FALSE;
        if (t == int.class) return 0;
        if (t == long.class) return 0L;
        if (t == float.class) return 0f;
        if (t == double.class) return 0d;
        if (t == short.class) return (short) 0;
        if (t == byte.class) return (byte) 0;
        if (t == char.class) return (char) 0;
        return null; // void 和对象
    }

    // ------------------------------------------------------------ 2) 双击视频（实验）

    private static void hookListener(Object l) {
        if (!(l instanceof GestureDetector.OnDoubleTapListener)) return;
        Class<?> k = l.getClass();
        if (!TAP_HOOKED.add(k)) return;
        // 系统在第二次点按时依次回调：onDoubleTap(第一次点按的 DOWN) → onDoubleTapEvent(第二次的 DOWN) → … → onDoubleTapEvent(UP)。
        // 点赞可能写在其中任何一个里，所以两个都要接管。
        try {
            Method m = k.getMethod("onDoubleTap", MotionEvent.class);
            if (m.getDeclaringClass() != GestureDetector.SimpleOnGestureListener.class) { // 默认空实现不用管
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            onTap(p, true);
                        } catch (Throwable ignored) {
                        }
                    }
                });
            }
        } catch (Throwable ignored) {
        }
        try {
            Method m = k.getMethod("onDoubleTapEvent", MotionEvent.class);
            if (m.getDeclaringClass() != GestureDetector.SimpleOnGestureListener.class) {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            onTap(p, false);
                        } catch (Throwable ignored) {
                        }
                    }
                });
            }
        } catch (Throwable ignored) {
        }
    }

    private static void onTap(XC_MethodHook.MethodHookParam p, boolean isDoubleTap) {
        long now = android.os.SystemClock.uptimeMillis();
        lastDtapAt = now;
        String who = p.method.getDeclaringClass().getName() + (isDoubleTap ? "#onDoubleTap" : "#onDoubleTapEvent");
        // 不管开没开关都记一次：看哪个类的哪个回调处理了双击
        logOnce("tap:" + who, "双击监听被触发：" + who);
        if (!DanmakuHook.cfg.dtapToComment) return;
        int action = -1;
        if (!isDoubleTap && p.args != null && p.args.length > 0 && p.args[0] instanceof MotionEvent) {
            action = ((MotionEvent) p.args[0]).getActionMasked();
        }
        // 第二次点按之后的移动 / 抬起：本次双击已经接管就一起拦掉
        if (!isDoubleTap && action != MotionEvent.ACTION_DOWN) {
            if (dtapBlocking && now - lastOpenAt < 3000) {
                p.setResult(Boolean.TRUE);
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) dtapBlocking = false;
            } else {
                dtapBlocking = false;
            }
            return;
        }
        // 双击开始（onDoubleTap，或 onDoubleTapEvent 的 DOWN）：同一次双击里已经有别的回调打开过评论就只拦截
        if (now - lastOpenAt < 800) {
            dtapBlocking = true;
            p.setResult(Boolean.TRUE);
            return;
        }
        if (openComments()) {
            lastOpenAt = now;
            dtapBlocking = true;
            p.setResult(Boolean.TRUE); // 返回 true = 已处理，原来的点赞逻辑不再执行
        }
    }

    // ------------------------------------------------------------ 打开评论

    /** 找到当前页的评论按钮并点一下；找不到返回 false（调用方就不拦截，点赞照常）。 */
    static boolean openComments() {
        View btn = findCommentButton();
        if (btn == null) return false;
        final View target = clickable(btn);
        // 等当前这次点击 / 手势处理完再点，避免在别人的回调里重入
        target.post(new Runnable() {
            @Override
            public void run() {
                try {
                    target.performClick();
                } catch (Throwable ignored) {
                }
            }
        });
        return true;
    }

    /** contentDescription 形如“评论24，按钮”、可见、可见面积最大的那个（相邻几个视频的右侧栏可能同时挂在界面里）。 */
    private static View findCommentButton() {
        Activity a = top.get();
        if (a == null) return null;
        View root = a.getWindow().getDecorView();
        ArrayList<View> todo = new ArrayList<>();
        todo.add(root);
        View best = null;
        long bestArea = 0;
        Rect r = new Rect();
        int seen = 0;
        while (!todo.isEmpty() && seen < 8000) {
            View v = todo.remove(todo.size() - 1);
            seen++;
            if (v.getVisibility() != View.VISIBLE) continue;
            CharSequence d = v.getContentDescription();
            if (d != null && v.isShown()) {
                String s = d.toString();
                if (s.startsWith("评论") && s.endsWith("按钮") && v.getGlobalVisibleRect(r)) {
                    long area = (long) r.width() * r.height();
                    if (area > bestArea) {
                        bestArea = area;
                        best = v;
                    }
                }
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) todo.add(g.getChildAt(i));
            }
        }
        return best;
    }

    /** 自己 → 往上 4 层 → 往下一层，找第一个有点击监听的；都没有就点它自己。 */
    private static View clickable(View btn) {
        View v = btn;
        for (int i = 0; i < 4 && v != null; i++) {
            if (v.hasOnClickListeners()) return v;
            Object p = v.getParent();
            v = p instanceof View ? (View) p : null;
        }
        if (btn instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) btn;
            for (int i = 0; i < g.getChildCount(); i++) if (g.getChildAt(i).hasOnClickListeners()) return g.getChildAt(i);
        }
        return btn;
    }

    // ------------------------------------------------------------ 诊断

    private static void logOnce(String key, String msg) {
        long now = android.os.SystemClock.uptimeMillis();
        Long last = LOGGED.get(key);
        if (last != null && now - last < 120000) return; // 同一条 2 分钟内只记一次；之后再触发会再记，免得状态文件被截断后找不到
        if (LOGGED.size() > 200) LOGGED.clear();
        LOGGED.put(key, now);
        DanmakuHook.status(msg);
    }

    /** 当前调用栈里有点击 / 触摸 / 手势事件的帧，说明是用户操作直接引起的。 */
    private static boolean fromUserInput() {
        for (StackTraceElement e : new Throwable().getStackTrace()) {
            String m = e.getMethodName();
            if (m.equals("performClick") || m.equals("onClick") || m.equals("dispatchTouchEvent") || m.equals("onTouchEvent")
                    || m.equals("onDoubleTap") || m.equals("onSingleTapUp") || m.equals("onSingleTapConfirmed")) return true;
        }
        return false;
    }

    /** 当前调用栈里最近的 12 个 App 帧（跳过 Xposed、java、android 框架），写进状态文件，用来定位点赞入口。 */
    private static String callers() {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (StackTraceElement e : new Throwable().getStackTrace()) {
            String c = e.getClassName();
            if (c.startsWith("de.robv.") || c.startsWith("LSPHooker") || c.startsWith("java.") || c.startsWith("com.danmaku.")
                    || c.startsWith("org.lsposed.") || c.startsWith("J.")) continue;
            if (n > 0) sb.append(" < ");
            sb.append(c.substring(c.lastIndexOf('.') + 1)).append('.').append(e.getMethodName());
            if (++n >= 12) break;
        }
        return sb.toString();
    }
}
