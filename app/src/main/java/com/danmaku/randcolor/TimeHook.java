package com.danmaku.randcolor;

import android.app.Activity;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 视频时长常显：自己画一个 "00:12 / 03:45"，不再借用抖音的时间控件。
 *
 * 依据 TextRec 的追踪（40.7.0）：SimPlayer 的公共监听层
 *   com.ss.android.ugc.aweme.video.simplayer.listener.base.OnUIPlayListenerDelegate#onPlayProgressChange
 * 每次进度更新都在主线程调用，有两个重载：(float 百分比) 和 (String 视频id, long 位置ms, long 总时长ms)。
 *
 * 1.16 的改动：
 *  1) 抖音任何界面都显示（不再限定 MainActivity）。
 *  2) 更及时：视频 id 变了立即刷新；进度回调的有效期从 3 秒缩到 1 秒；显示期间每 250ms 检查一次
 *     （原来每秒一次，而且每来一次回调就把检查往后推）；检测到进度条在整屏滑动（翻页）时立刻隐藏，
 *     翻到直播（没有进度回调、进度条也不在屏幕上）就很快消失，翻到新视频等第一个回调到了再出现。
 *  3) 位置单位改为 px，位置由抖音里的「位置浮窗」（TimePanel）调整，改动实时可见。
 */
final class TimeHook {
    private TimeHook() {}

    /** 版本相关（40.7.0）。 */
    private static final String DELEGATE = "com.ss.android.ugc.aweme.video.simplayer.listener.base.OnUIPlayListenerDelegate";
    private static final String PROGRESS = "onPlayProgressChange";
    private static final String SEEKBAR = "com.ss.android.ugc.aweme.o.seekbar.views.seekbar.ProSeekBarView";

    /** 收到进度回调后多久内算「正在播放」。回调正常是连续的，太长会让刷到直播后文字赖着不走。 */
    private static final long TICK_FRESH_MS = 1000;
    /** 文字显示期间的检查间隔。 */
    private static final long POLL_MS = 250;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile boolean progressHooked, seekHooked;
    private static int misses;

    private static WeakReference<View> seekRef = new WeakReference<>(null);
    private static String lastId;
    private static long lastPos = Long.MIN_VALUE, lastDur = Long.MIN_VALUE;
    private static boolean loggedOk;

    private static WeakReference<TextView> viewRef = new WeakReference<>(null);
    private static String lastText = "";
    private static int lastDx = Integer.MIN_VALUE, lastDy = Integer.MIN_VALUE;
    private static long lastTickAt;
    private static boolean polling, swiping;
    private static int lastSbY = Integer.MIN_VALUE;
    private static final Rect TMP = new Rect();
    private static final int[] LOC = new int[2];

    // ------------------------------------------------------------ 安装

    /** 目标 App 的类加载器；启动时试一次，之后每次回到前台再试，直到都挂上。 */
    static void tryHook(ClassLoader cl) {
        if (cl == null || (progressHooked && seekHooked)) return;
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

    private static boolean isMain() {
        return Looper.myLooper() == Looper.getMainLooper();
    }

    /** 时长文字是否应该存在：设置里开了，或者位置浮窗正开着（调位置时即使没开也要看得到）。 */
    private static boolean enabled() {
        return DanmakuHook.cfg.timeAlways || TimePanel.isOpen();
    }

    // ------------------------------------------------------------ 钩子

    /** 进度回调：(String 视频id, long 位置ms, long 总时长ms) 这个重载直接带着时间；float 百分比那个重载忽略。 */
    private static final class ProgressHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam p) {
            try {
                Object[] a = p.args;
                if (a == null || a.length != 3 || !(a[1] instanceof Number) || !(a[2] instanceof Number)) return;
                if (!isMain()) return;
                String id = a[0] instanceof String ? (String) a[0] : null;
                onProgress(id, ((Number) a[1]).longValue(), ((Number) a[2]).longValue());
            } catch (Throwable ignored) {
            }
        }
    }

    /** 进度条自己（每次进度动画都会调 setProgress）：记下这个 View，用来判断还在不在视频页、页面有没有在滑。 */
    private static final class SeekHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam p) {
            Object o = p.thisObject;
            if (o instanceof View && seekRef.get() != o) seekRef = new WeakReference<>((View) o);
        }
    }

    // ------------------------------------------------------------ 显示

    private static void onProgress(String id, long pos, long dur) {
        if (!enabled()) {
            removeView();
            return;
        }
        boolean newVideo = id != null && !id.equals(lastId);
        if (!newVideo && pos == lastPos && dur == lastDur) return; // 同一个位置经过多个包装监听器重复到达
        if (id != null) lastId = id;
        lastPos = pos;
        lastDur = dur;
        if (!loggedOk) {
            loggedOk = true;
            XposedBridge.log("[DanmakuColor] time ok: pos=" + pos + "ms dur=" + dur + "ms");
        }
        if (pos < 0) pos = 0;
        if (dur > 0 && pos > dur) pos = dur;
        show(CommentHook.top.get(), fmt(pos) + " / " + (dur > 0 ? fmt(dur) : "--:--"));
    }

    private static String fmt(long ms) {
        long s = ms / 1000;
        long h = s / 3600, m = (s / 60) % 60, sec = s % 60;
        StringBuilder b = new StringBuilder(8);
        if (h > 0) b.append(h).append(':');
        if (m < 10) b.append('0');
        b.append(m).append(':');
        if (sec < 10) b.append('0');
        b.append(sec);
        return b.toString();
    }

    /** 抖音里任何还活着的界面都可以放（不再限定 MainActivity）。 */
    private static boolean isHostActivity(Activity a) {
        return a != null && !a.isFinishing() && !a.isDestroyed();
    }

    /** 位置浮窗打开时调用：没有进度回调也先画一个示例文字，好边调边看。 */
    static void showSample(Activity a) {
        show(a, lastText.isEmpty() ? "00:00 / 00:00" : lastText);
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
        boolean added = false;
        if (t.getParent() != decor) {
            detach(t);
            decor.addView(t, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
            added = true;
        } else {
            View last = decor.getChildAt(decor.getChildCount() - 1);
            if (last != t && last != panel) {
                t.bringToFront();
                added = true;
            }
        }
        if (added && panel != null && panel.getParent() == decor) panel.bringToFront(); // 浮窗始终压在文字上面
        applyOffset(t);
        if (!text.equals(lastText)) {
            lastText = text;
            t.setText(text);
        }
        if (!swiping && t.getVisibility() != View.VISIBLE) t.setVisibility(View.VISIBLE);
        lastTickAt = System.currentTimeMillis();
        startPolling();
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

    private static void startPolling() {
        if (polling) return; // 已经在排队：不要因为回调频繁到达就把检查一直往后推
        polling = true;
        MAIN.postDelayed(CHECK, POLL_MS);
    }

    /**
     * 显示期间每 250ms 检查一次：
     *  - 最近 1 秒有进度回调，或进度条还在屏幕上（暂停）→ 继续显示；否则隐藏（刷到直播就是这种情况）；
     *  - 进度条在屏幕上整体移动（正在上下翻页）→ 先藏起来，翻完、新视频的回调到了再出现。
     * 隐藏后不再排队，下次有回调时由 show() 重新启动。
     */
    private static final Runnable CHECK = new Runnable() {
        @Override
        public void run() {
            polling = false;
            try {
                TextView t = viewRef.get();
                if (t == null || t.getParent() == null) {
                    swiping = false;
                    return;
                }
                boolean panel = TimePanel.isOpen();
                boolean fresh = System.currentTimeMillis() - lastTickAt < TICK_FRESH_MS;
                View sb = seekRef.get();
                boolean sbOn = sb != null && sb.isAttachedToWindow() && sb.isShown() && sb.getGlobalVisibleRect(TMP);
                boolean moving = false;
                if (sbOn) {
                    sb.getLocationOnScreen(LOC);
                    moving = lastSbY != Integer.MIN_VALUE && Math.abs(LOC[1] - lastSbY) > 3;
                    lastSbY = LOC[1];
                } else {
                    lastSbY = Integer.MIN_VALUE;
                }
                boolean alive = enabled() && (panel || fresh || sbOn);
                if (!alive) {
                    swiping = false;
                    t.setVisibility(View.GONE);
                    return;
                }
                swiping = moving && !panel;
                t.setVisibility(swiping ? View.INVISIBLE : View.VISIBLE);
                startPolling();
            } catch (Throwable ignored) {
            }
        }
    };

    private static void detach(View v) {
        if (v != null && v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
    }

    private static void removeView() {
        MAIN.removeCallbacks(CHECK);
        polling = false;
        swiping = false;
        detach(viewRef.get());
    }

    /** 位置浮窗关闭后：没开常显就把文字撤掉，开着的话继续按正常规则走。 */
    static void onPanelClosed() {
        if (!DanmakuHook.cfg.timeAlways) removeView();
        else startPolling();
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
