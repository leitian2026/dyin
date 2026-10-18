package com.danmaku.randcolor;

import android.app.Activity;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 视频时长常显：自己画一个 "00:12 / 03:45"，不用抖音的时间控件。
 *
 * 数据和状态都来自播放分发器 DIPlayListenerDispatcher（40.7.0，主线程，每个事件只到一次，不像下层包装监听器会重复十几次）：
 *  - onPlayProgressChange(String 视频id, long 位置ms, long 总时长ms)：更新文字。
 *  - 状态回调（TextRec 追踪到的真实名字）：onPlayStop / onPlayRelease 隐藏；onPausePlay / onPlayPause 暂停；
 *    onPlaying / onRenderFirstFrame 新视频开始播放，立刻显示（onPlaying 的 PlayerEvent 里带 duration）。
 *  - 兜底看门狗：只有一条 1 秒的延时消息，1 秒没有进度回调才检查进度条（暂停时还在屏幕上就继续显示）。没有任何周期性轮询。
 *  - 换视频后 1 秒内忽略刚离开的那个视频的进度回调（新旧回调交错会让文字来回跳）。
 *
 * 位置（px）由抖音里的位置浮窗（TimePanel）调整。
 */
final class TimeHook {
    private TimeHook() {}

    /** 版本相关（40.7.0）。 */
    private static final String DELEGATE = "com.ss.android.ugc.aweme.o.oplayer.util.DIPlayListenerDispatcher";
    private static final String PROGRESS = "onPlayProgressChange";
    private static final String SEEKBAR = "com.ss.android.ugc.aweme.o.seekbar.views.seekbar.ProSeekBarView";

    private static final long IDLE_MS = 1000;   // 兜底：多久没有进度回调才去看进度条
    private static final long STALE_MS = 1000;  // 换视频后忽略旧视频回调的时间
    private static final long PAUSE_CHECK_MS = 150;

    private static final int K_OTHER = 0, K_STOP = 1, K_PAUSE = 2, K_FIRST = 3;
    private static final String PLACEHOLDER = "00:00 / --:--";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile boolean progressHooked, seekHooked, eventsHooked;
    private static int misses;

    private static WeakReference<View> seekRef = new WeakReference<>(null);
    private static WeakReference<TextView> viewRef = new WeakReference<>(null);
    private static final Rect TMP = new Rect();

    // 状态（都在主线程用）
    private static String lastId, staleId;
    private static long lastSec = -1, lastDsec = -2, lastTickAt, staleUntil;
    private static String lastText = "";
    private static int lastDx = Integer.MIN_VALUE, lastDy = Integer.MIN_VALUE;
    private static int pauseChecks;
    private static boolean loggedOk, shown, watching;

    // ------------------------------------------------------------ 安装

    /** 目标 App 的类加载器；启动时试一次，之后每次回到前台再试，直到都挂上。 */
    static void tryHook(ClassLoader cl) {
        if (cl == null || (progressHooked && seekHooked && eventsHooked)) return;
        attempt(cl);
        if (!progressHooked) {
            ClassLoader ctx = Thread.currentThread().getContextClassLoader();
            if (ctx != null && ctx != cl) attempt(ctx);
        }
        if (!progressHooked && ++misses == 6) {
            XposedBridge.log("[DanmakuColor] time hook: classes not found yet (progress=" + progressHooked + ")");
        }
    }

    private static void attempt(ClassLoader cl) {
        if (!progressHooked) progressHooked = hookAll(cl, DELEGATE, PROGRESS, new ProgressHook());
        if (!seekHooked) seekHooked = hookAll(cl, SEEKBAR, "setProgress", new SeekHook());
        if (progressHooked && !eventsHooked) eventsHooked = hookEvents(cl);
    }

    private static boolean hookAll(ClassLoader cl, String cls, String method, XC_MethodHook cb) {
        try {
            Class<?> c = Class.forName(cls, false, cl);
            Set<XC_MethodHook.Unhook> s = XposedBridge.hookAllMethods(c, method, cb);
            if (s == null || s.isEmpty()) {
                XposedBridge.log("[DanmakuColor] time hook: no method " + cls + "#" + method);
                return false;
            }
            return true;
        } catch (ClassNotFoundException e) {
            return false; // 还没加载：下次回到前台再试
        } catch (Throwable t) {
            XposedBridge.log("[DanmakuColor] time hook failed: " + cls + "#" + method + " " + t);
            return false;
        }
    }

    /** 分发器里 TextRec 追踪到的状态回调（名字都没混淆），按名字精确挂，所有重载都挂。 */
    private static boolean hookEvents(ClassLoader cl) {
        try {
            Class<?> c = Class.forName(DELEGATE, false, cl);
            StringBuilder names = new StringBuilder();
            int n = 0;
            for (Method m : c.getDeclaredMethods()) {
                int kind = kindOf(m.getName());
                if (kind == K_OTHER || m.isSynthetic()) continue;
                int mod = m.getModifiers();
                if (Modifier.isStatic(mod) || Modifier.isAbstract(mod)) continue;
                try {
                    XposedBridge.hookMethod(m, new EventHook(m.getName(), kind));
                    n++;
                    names.append(' ').append(m.getName()).append('/').append(m.getParameterTypes().length);
                } catch (Throwable ignored) {
                }
            }
            XposedBridge.log("[DanmakuColor] time events hooked: " + n + names);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        } catch (Throwable t) {
            XposedBridge.log("[DanmakuColor] time events failed: " + t);
            return true; // 不再反复扫描
        }
    }

    /** 40.7.0 追踪到的真实方法名。onPreparePlay 不用：预加载的下一个视频也会走。 */
    private static int kindOf(String name) {
        switch (name) {
            case "onPlayStop":
            case "onPlayRelease":
                return K_STOP;
            case "onPausePlay":
            case "onPlayPause":
                return K_PAUSE;
            case "onPlaying":
            case "onRenderFirstFrame":
                return K_FIRST;
            default:
                return K_OTHER;
        }
    }

    private static boolean isMain() {
        return Looper.myLooper() == Looper.getMainLooper();
    }

    /** 时长文字是否应该存在：设置里开了，或者位置浮窗正开着（调位置时即使没开也要看得到）。 */
    private static boolean enabled() {
        return DanmakuHook.cfg.timeAlways || TimePanel.isOpen();
    }

    // ------------------------------------------------------------ 钩子

    private static final class ProgressHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam p) {
            try {
                Object[] a = p.args;
                if (a == null || a.length != 3 || !(a[1] instanceof Number) || !(a[2] instanceof Number)) return;
                if (!isMain()) return;
                onProgress(a[0] instanceof String ? (String) a[0] : null, ((Number) a[1]).longValue(), ((Number) a[2]).longValue());
            } catch (Throwable ignored) {
            }
        }
    }

    /** 播放器状态回调：事件一到就处理（before：比播放器自己的处理更早）。 */
    private static final class EventHook extends XC_MethodHook {
        final String name;
        final int kind;
        int logged;

        EventHook(String name, int kind) {
            this.name = name;
            this.kind = kind;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            try {
                if (logged < 2) {
                    logged++;
                    XposedBridge.log("[DanmakuColor] ev " + name + " kind=" + kind + " args=" + summary(p.args));
                }
                if (kind == K_OTHER) return;
                final String id = firstString(p.args);
                final long dur = kind == K_FIRST ? durationOf(p.args) : -1;
                if (isMain()) {
                    onEvent(kind, id, dur);
                } else {
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            onEvent(kind, id, dur);
                        }
                    });
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** 进度条自己（播放时每次进度动画都会调 setProgress）：只记下这个 View，用来判断暂停时还在不在屏幕上。 */
    private static final class SeekHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam p) {
            Object o = p.thisObject;
            if (o instanceof View && seekRef.get() != o) seekRef = new WeakReference<>((View) o);
        }
    }

    /** 参数里的 PlayerEvent（toString 里有 duration=…）读出总时长 ms；读不到返回 -1。 */
    private static long durationOf(Object[] a) {
        if (a == null) return -1;
        for (Object o : a) {
            if (o == null || o instanceof String || o instanceof Number || o instanceof Boolean) continue;
            try {
                java.lang.reflect.Field f = null;
                for (Class<?> k = o.getClass(); k != null && f == null && k != Object.class; k = k.getSuperclass()) {
                    try {
                        f = k.getDeclaredField("duration");
                    } catch (NoSuchFieldException ignored) {
                    }
                }
                if (f == null) continue;
                f.setAccessible(true);
                Object v = f.get(o);
                if (v instanceof Number && ((Number) v).longValue() > 0) return ((Number) v).longValue();
            } catch (Throwable ignored) {
            }
        }
        return -1;
    }

    private static String firstString(Object[] a) {
        if (a != null) for (Object o : a) if (o instanceof String) return (String) o;
        return null;
    }

    private static String summary(Object[] a) {
        if (a == null || a.length == 0) return "()";
        StringBuilder b = new StringBuilder("(");
        for (int i = 0; i < a.length; i++) {
            if (i > 0) b.append(", ");
            Object o = a[i];
            if (o == null) b.append("null");
            else if (o instanceof String) b.append('"').append(((String) o).length() > 24 ? ((String) o).substring(0, 24) : o).append('"');
            else if (o instanceof Number || o instanceof Boolean) b.append(o);
            else b.append(o.getClass().getSimpleName());
        }
        return b.append(')').toString();
    }

    // ------------------------------------------------------------ 状态事件

    private static void onEvent(int kind, String id, long dur) {
        if (!enabled()) return;
        boolean mine = id == null || id.equals(lastId); // 别的视频播放器的事件（比如旧视频迟到的 stop）不动当前文字
        switch (kind) {
            case K_STOP:
                if (mine) hideNow();
                break;
            case K_PAUSE:
                // 用户手动暂停：进度条还在屏幕上，保持显示；翻页离开引起的暂停：进度条已经走了，隐藏
                if (mine && shown) {
                    pauseChecks = 0;
                    MAIN.removeCallbacks(PAUSE_CHECK);
                    MAIN.postDelayed(PAUSE_CHECK, PAUSE_CHECK_MS);
                }
                break;
            case K_FIRST: // 新视频开始播放：马上画出来（onPlaying 里带总时长就直接用），不等第一个进度回调
                if (switchTo(id) || !shown) {
                    String tail = dur > 0 ? fmt(dur / 1000) : "--:--";
                    show(CommentHook.top.get(), "00:00 / " + tail);
                }
                break;
            default:
                break;
        }
    }

    /** 记下当前视频；视频变了返回 true，并把旧视频记成「刚离开」。 */
    private static boolean switchTo(String id) {
        if (id == null || id.equals(lastId)) return false;
        staleId = lastId;
        staleUntil = SystemClock.uptimeMillis() + STALE_MS;
        lastId = id;
        lastSec = -1;
        lastDsec = -2;
        return true;
    }

    private static final Runnable PAUSE_CHECK = new Runnable() {
        @Override
        public void run() {
            try {
                if (!shown || TimePanel.isOpen()) return;
                if (!seekbarOn()) hideNow();
                else if (++pauseChecks < 3) MAIN.postDelayed(this, 250); // 翻页动画还没走完：再看两次
            } catch (Throwable ignored) {
            }
        }
    };

    // ------------------------------------------------------------ 进度

    private static void onProgress(String id, long pos, long dur) {
        if (!enabled()) {
            removeView();
            return;
        }
        long now = SystemClock.uptimeMillis();
        boolean newVideo = id != null && !id.equals(lastId);
        if (newVideo) {
            if (id.equals(staleId) && now < staleUntil) return; // 刚离开的那个视频的尾巴
            switchTo(id);
        }
        long sec = pos > 0 ? pos / 1000 : 0;
        long dsec = dur > 0 ? dur / 1000 : -1;
        if (shown && sec == lastSec && dsec == lastDsec) { // 秒数没变：只记心跳
            lastTickAt = now;
            armWatchdog();
            return;
        }
        if (dsec >= 0 && sec > dsec) sec = dsec;
        lastSec = sec;
        lastDsec = dsec;
        if (!loggedOk) {
            loggedOk = true;
            XposedBridge.log("[DanmakuColor] time ok: pos=" + pos + "ms dur=" + dur + "ms");
        }
        show(CommentHook.top.get(), fmt(sec) + " / " + (dsec >= 0 ? fmt(dsec) : "--:--"));
    }

    private static String fmt(long s) {
        long h = s / 3600, m = (s / 60) % 60, sec = s % 60;
        StringBuilder b = new StringBuilder(8);
        if (h > 0) b.append(h).append(':');
        if (m < 10) b.append('0');
        b.append(m).append(':');
        if (sec < 10) b.append('0');
        b.append(sec);
        return b.toString();
    }

    // ------------------------------------------------------------ 显示

    /** 抖音里任何还活着的界面都可以放。 */
    private static boolean isHostActivity(Activity a) {
        return a != null && !a.isFinishing() && !a.isDestroyed();
    }

    /** 位置浮窗打开时调用：没有进度回调也先画一个示例文字，好边调边看。 */
    static void showSample(Activity a) {
        show(a, lastText.isEmpty() ? PLACEHOLDER : lastText);
    }

    private static void show(Activity a, String text) {
        if (!isHostActivity(a)) return;
        TextView t = viewRef.get();
        ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
        float d = a.getResources().getDisplayMetrics().density;
        if (t != null && t.getContext() != a) { // 换了 Activity：旧的拿掉重建
            detach(t);
            t = null;
        }
        if (t == null) {
            t = new TextView(a);
            t.setTextSize(20f);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setTextColor(0xFFFFFFFF);
            t.setShadowLayer(5f * d, 0f, 0f, 0xFF000000); // 黑色描边感，亮暗背景都看得清
            t.setIncludeFontPadding(false);
            t.setClickable(false);
            t.setFocusable(false);
            try {
                t.setFontFeatureSettings("tnum"); // 等宽数字，秒数跳动时不抖
            } catch (Throwable ignored) {
            }
            t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            viewRef = new WeakReference<>(t);
            lastText = "";
            lastDx = lastDy = Integer.MIN_VALUE;
        }
        View panel = TimePanel.view();
        boolean moved = false;
        if (t.getParent() != decor) {
            detach(t);
            decor.addView(t, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
            moved = true;
        } else {
            View last = decor.getChildAt(decor.getChildCount() - 1);
            if (last != t && last != panel) {
                t.bringToFront();
                moved = true;
            }
        }
        if (moved && panel != null && panel.getParent() == decor) panel.bringToFront(); // 浮窗始终压在文字上面
        applyOffset(t);
        if (!text.equals(lastText)) {
            lastText = text;
            t.setText(text);
        }
        if (t.getVisibility() != View.VISIBLE) t.setVisibility(View.VISIBLE);
        shown = true;
        lastTickAt = SystemClock.uptimeMillis();
        armWatchdog();
    }

    private static void applyOffset(TextView t) {
        DanmakuConfig c = DanmakuHook.cfg;
        if (c.timeX == lastDx && c.timeY == lastDy) return;
        lastDx = c.timeX;
        lastDy = c.timeY;
        t.setTranslationX(c.timeX);
        t.setTranslationY(c.timeY);
    }

    /** 位置浮窗拖动时实时生效。 */
    static void applyOffsetNow() {
        TextView t = viewRef.get();
        if (t != null) applyOffset(t);
    }

    /** 立刻隐藏；下一次进度回调（或首帧事件）会重新显示。 */
    private static void hideNow() {
        shown = false;
        lastSec = -1;
        lastDsec = -2;
        MAIN.removeCallbacks(PAUSE_CHECK);
        TextView t = viewRef.get();
        if (t != null) t.setVisibility(View.GONE);
    }

    // ------------------------------------------------------------ 兜底看门狗

    private static void armWatchdog() {
        if (watching) return; // 已经有一条在排队：不要因为回调频繁到达而反复重排
        watching = true;
        MAIN.postDelayed(WATCHDOG, IDLE_MS);
    }

    private static final Runnable WATCHDOG = new Runnable() {
        @Override
        public void run() {
            watching = false;
            try {
                if (!shown || TimePanel.isOpen()) return;
                long idle = SystemClock.uptimeMillis() - lastTickAt;
                if (idle < IDLE_MS) { // 期间还有回调：只等剩下的时间
                    watching = true;
                    MAIN.postDelayed(this, IDLE_MS - idle);
                    return;
                }
                if (!seekbarOn()) hideNow(); // 暂停时进度条还在屏幕上：保持显示，不再排队
            } catch (Throwable ignored) {
            }
        }
    };

    private static boolean seekbarOn() {
        View sb = seekRef.get();
        return sb != null && sb.isAttachedToWindow() && sb.isShown() && sb.getGlobalVisibleRect(TMP);
    }

    // ------------------------------------------------------------ 生命周期

    private static void detach(View v) {
        if (v != null && v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
    }

    private static void removeView() {
        MAIN.removeCallbacks(WATCHDOG);
        MAIN.removeCallbacks(PAUSE_CHECK);
        watching = shown = false;
        detach(viewRef.get());
    }

    /** 位置浮窗关闭后：没开常显就把文字撤掉，开着的话重新交给看门狗。 */
    static void onPanelClosed() {
        if (!DanmakuHook.cfg.timeAlways) removeView();
        else armWatchdog();
    }

    /** 回到前台（配置刚重读过）：需要时弹出位置浮窗；换了页面的文字拿掉重建；位置有变立即生效。 */
    static void onResume(Activity a) {
        try {
            if (!isMain()) return;
            DanmakuConfig c = DanmakuHook.cfg;
            if (c.timeAdjust && isHostActivity(a)) {
                c.timeAdjust = false; // 一次性请求：先清掉并写回，免得下次回到前台又弹
                DanmakuHook.saveCfg();
                TimePanel.open(a);
            } else if (TimePanel.isOpen()) {
                TimePanel.open(a); // 换了界面：浮窗跟过去
            }
            TextView t = viewRef.get();
            if (t == null) return;
            if (!enabled() || !isHostActivity(a) || t.getContext() != a) {
                removeView();
                return;
            }
            applyOffset(t);
        } catch (Throwable ignored) {
        }
    }
}
