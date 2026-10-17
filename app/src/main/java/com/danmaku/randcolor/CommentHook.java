package com.danmaku.randcolor;

import android.app.Activity;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
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
 * 把"点赞"改成"打开评论"，并且不出红心。三个入口，两个开关：
 *  1) 右侧爱心按钮（likeBtnToComment）：拦掉 FeedDiggPresenter#LJJJJJL，改为点一下评论按钮。
 *  2) 双击视频（dtapToComment）：
 *     a) 监听系统 GestureDetector 的 onDoubleTap / onDoubleTapEvent，命中就拦下并打开评论；
 *     b) 双击后 DTAP_GUARD_MS 内跳过 FeedDiggPresenter$observeVM$2.LIZ（真正发起点赞的地方）；
 *     c) 双击后 DTAP_GUARD_MS 内跳过 DiggLayout.LIZJ（红心动画的来源）。
 *     "双击窗口"由 Activity.dispatchTouchEvent 里检测第二次按下提前打开（红心在 onDoubleTap 之前就会生成）。
 * 评论按钮的找法：contentDescription 形如"评论24，按钮"、可见面积最大的那个。
 * 找不到评论按钮时：按钮点赞照常放行；双击则不放行（双击不点赞）。
 *
 * 省电：只挂这几个点，全都是"事件触发"才干活；没有全局的 addView / setVisibility / 动画钩子，没有调用栈诊断，没有日志。
 */
final class CommentHook {
    private CommentHook() {}

    static volatile WeakReference<Activity> top = new WeakReference<>(null);

    private static final String DIGG = "com.ss.android.ugc.aweme.feed.quick.presenter.FeedDiggPresenter";
    /** 版本相关：40.7.0 里右侧爱心按钮 onClick 调到的方法。换版本后需要重新记录。 */
    private static final String DIGG_CLICK_METHOD = "LJJJJJL";
    /** 双击后真正发起点赞的观察者（类名没混淆，方法名混淆）。 */
    private static final String DIGG_OBSERVER = DIGG + "$observeVM$2";
    private static final String DIGG_OBSERVER_METHOD = "LIZ";
    /** 双击红心的来源：common.widget.DiggLayout.LIZJ。 */
    private static final String DIGG_LAYOUT = "com.ss.android.ugc.aweme.common.widget.DiggLayout";
    private static final String DIGG_LAYOUT_METHOD = "LIZJ";
    /** 双击之后多长时间内，把点赞 / 红心当作"双击点赞"拦掉（毫秒）。 */
    private static final long DTAP_GUARD_MS = 1500;

    private static boolean installed;
    private static volatile boolean clickHooked, observerHooked, diggLayoutHooked;
    /** 在这个时间点之前，点赞观察者 / 红心的调用一律拦掉（只在"双击→评论"开着时才会被设置）。 */
    private static volatile long dtapGuardUntil;
    /** 最近一次我们接管双击并打开评论的时间；同一次双击里多个回调 / 多个监听只打开一次。 */
    private static volatile long lastOpenAt;
    /** 本次双击的后续事件（移动、抬起）也要一起拦掉。 */
    private static volatile boolean dtapBlocking;
    private static final Set<Class<?>> TAP_HOOKED = Collections.synchronizedSet(new HashSet<Class<?>>());

    private static volatile long lastDownT;
    private static volatile float lastDownX, lastDownY;

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
        } catch (Throwable ignored) {
        }
        hookTouchGuard();
    }

    private static boolean guardActive() {
        return DanmakuHook.cfg.dtapToComment && SystemClock.uptimeMillis() <= dtapGuardUntil;
    }

    // ------------------------------------------------------------ 双击窗口（触摸层）

    /**
     * 在 Activity.dispatchTouchEvent（所有触摸的最前面）里自己判断"第二次按下"：与上一次按下间隔小于系统双击超时、位置相近，就立刻开窗。
     * 只处理 ACTION_DOWN，其余触摸事件第一时间返回。
     */
    private static void hookTouchGuard() {
        try {
            XposedBridge.hookMethod(Activity.class.getDeclaredMethod("dispatchTouchEvent", MotionEvent.class), new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        MotionEvent e = (MotionEvent) p.args[0];
                        if (e.getActionMasked() != MotionEvent.ACTION_DOWN) return;
                        if (!DanmakuHook.cfg.dtapToComment || e.getPointerCount() != 1) return;
                        long t = e.getEventTime();
                        float x = e.getX(), y = e.getY();
                        float dx = x - lastDownX, dy = y - lastDownY;
                        float lim = 100f * ((Activity) p.thisObject).getResources().getDisplayMetrics().density;
                        long gap = t - lastDownT;
                        if (gap >= 40 && gap <= ViewConfiguration.getDoubleTapTimeout() && dx * dx + dy * dy <= lim * lim) {
                            dtapGuardUntil = SystemClock.uptimeMillis() + DTAP_GUARD_MS;
                        }
                        lastDownT = t;
                        lastDownX = x;
                        lastDownY = y;
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ 挂接抖音自己的类（需要它的类加载器；回到前台没挂上就再试）

    static void tryHook(ClassLoader cl) {
        if (cl == null) return;
        hookObserver(cl);
        hookDiggLayout(cl);
        hookDiggClick(cl);
    }

    /** 1) 右侧爱心按钮。 */
    private static void hookDiggClick(ClassLoader cl) {
        if (clickHooked) return;
        try {
            Class<?> k = Class.forName(DIGG, false, cl);
            for (Method m : k.getDeclaredMethods()) {
                if (!m.getName().equals(DIGG_CLICK_METHOD)) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            if (!DanmakuHook.cfg.likeBtnToComment) return;
                            // 只拦"用户点出来的"：这个方法在切视频 / 绑定数据 / 收到服务器结果时也可能被调用。栈里没有点击或触摸事件的一律放行。
                            if (!fromUserInput()) return;
                            if (openComments()) p.setResult(defaultOf(((Method) p.method).getReturnType()));
                        } catch (Throwable ignored) {
                        }
                    }
                });
            }
            clickHooked = true;
        } catch (ClassNotFoundException e) {
            // 类还没加载或这个版本没有：下次回到前台再试
        } catch (Throwable t) {
            clickHooked = true;
        }
    }

    /** 2b) 双击点赞的真正入口：窗口内跳过 observeVM$2.LIZ；其它时候（按钮点赞、切视频）原样放行。 */
    private static void hookObserver(ClassLoader cl) {
        if (observerHooked) return;
        try {
            Class<?> k = Class.forName(DIGG_OBSERVER, false, cl);
            int n = 0;
            for (Method m : k.getDeclaredMethods()) {
                if (!m.getName().equals(DIGG_OBSERVER_METHOD) || m.isBridge() || m.isSynthetic()) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            if (!guardActive()) return;
                            p.setResult(defaultOf(((Method) p.method).getReturnType()));
                        } catch (Throwable ignored) {
                        }
                    }
                });
                n++;
            }
            observerHooked = n > 0;
        } catch (ClassNotFoundException e) {
            // 下次回到前台再试
        } catch (Throwable t) {
            observerHooked = true;
        }
    }

    /** 2c) 双击红心：窗口内直接跳过 DiggLayout.LIZJ（只拦 void / 基本类型返回值的重载，返回对象的不拦，免得调用方拿到 null 崩）。 */
    private static void hookDiggLayout(ClassLoader cl) {
        if (diggLayoutHooked) return;
        try {
            Class<?> k = Class.forName(DIGG_LAYOUT, false, cl);
            int n = 0;
            for (Method m : k.getDeclaredMethods()) {
                if (!m.getName().equals(DIGG_LAYOUT_METHOD)) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            if (!guardActive()) return;
                            Class<?> rt = ((Method) p.method).getReturnType();
                            if (rt == void.class || rt.isPrimitive()) {
                                dtapGuardUntil = SystemClock.uptimeMillis() + DTAP_GUARD_MS; // 连点：每挡一次延长窗口
                                p.setResult(defaultOf(rt));
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
                n++;
            }
            diggLayoutHooked = n > 0;
        } catch (ClassNotFoundException e) {
            // 下次回到前台再试
        } catch (Throwable t) {
            diggLayoutHooked = true;
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

    // ------------------------------------------------------------ 2a) 双击视频

    private static void hookListener(Object l) {
        if (!(l instanceof GestureDetector.OnDoubleTapListener)) return;
        Class<?> k = l.getClass();
        if (!TAP_HOOKED.add(k)) return;
        // 系统在第二次点按时依次回调：onDoubleTap(第一次点按的 DOWN) → onDoubleTapEvent(第二次的 DOWN) → … → onDoubleTapEvent(UP)。
        // 点赞可能写在其中任何一个里，所以两个都要接管。
        hookTap(k, "onDoubleTap", true);
        hookTap(k, "onDoubleTapEvent", false);
    }

    private static void hookTap(Class<?> k, String name, final boolean isDoubleTap) {
        try {
            Method m = k.getMethod(name, MotionEvent.class);
            if (m.getDeclaringClass() == GestureDetector.SimpleOnGestureListener.class) return; // 默认空实现不用管
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        onTap(p, isDoubleTap);
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static void onTap(XC_MethodHook.MethodHookParam p, boolean isDoubleTap) {
        if (!DanmakuHook.cfg.dtapToComment) return;
        long now = SystemClock.uptimeMillis();
        // 先把点赞 / 红心堵住（按这个时间窗在 hookObserver / hookDiggLayout 里拦截）
        dtapGuardUntil = now + DTAP_GUARD_MS;
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
        // 双击开始（onDoubleTap，或 onDoubleTapEvent 的 DOWN）：同一次双击里已经有别的回调处理过就只拦截
        if (now - lastOpenAt < 800) {
            dtapBlocking = true;
            p.setResult(Boolean.TRUE);
            return;
        }
        lastOpenAt = now;
        dtapBlocking = true;
        p.setResult(Boolean.TRUE); // 返回 true = 已处理，原来的点赞逻辑不再执行
        openComments(); // 找不到评论按钮也不放行点赞：双击不点赞
    }

    // ------------------------------------------------------------ 打开评论

    /** 找到当前页的评论按钮并点一下；找不到返回 false。 */
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

    /** contentDescription 形如"评论24，按钮"、可见、可见面积最大的那个（相邻几个视频的右侧栏可能同时挂在界面里）。 */
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

    /** 当前调用栈里有点击 / 触摸 / 手势事件的帧，说明是用户操作直接引起的（只在点爱心按钮时才会走到这里）。 */
    private static boolean fromUserInput() {
        for (StackTraceElement e : new Throwable().getStackTrace()) {
            String m = e.getMethodName();
            if (m.equals("performClick") || m.equals("onClick") || m.equals("dispatchTouchEvent") || m.equals("onTouchEvent")
                    || m.equals("onDoubleTap") || m.equals("onSingleTapUp") || m.equals("onSingleTapConfirmed")) return true;
        }
        return false;
    }
}
