package com.danmaku.randcolor;

import android.app.Activity;
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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 视频时长常显：自己画一个 "00:12 / 03:45"，不用抖音的时间控件。
 *
 * 全部来自 SimPlayer 公共监听层 OnUIPlayListenerDelegate（40.7.0，主线程；名字都没混淆，第一个参数是视频 id）。
 * 依据 TextRec 1.22.4 的追踪：
 *  - onPlayProgressChange(id, 位置ms, 总时长ms)：更新文字。
 *  - onPlayStop / onPlayRelease(id)：离开这个视频（划走时 60ms 内依次触发 onPlayPause→onPausePlay→onPlayStop→onPlayRelease）
 *    → 立刻隐藏。用户手动暂停只有 onPlayPause / onPausePlay，没有 stop / release，所以暂停时文字保持显示。
 *  - onPlaying(id, PlayerEvent)：新视频开始播放，PlayerEvent 的 toString 里带 duration=总时长ms
 *    → 立刻画 "00:00 / 总时长"，不等第一个进度回调。
 *  每个事件会经过 5~6 个包装监听器重复到达，处理都是幂等的。
 *
 * 没有定时器 / 轮询：文字只在上面这些回调里更新或隐藏。换视频后 1 秒内忽略刚离开的那个视频的进度回调
 * （时间戳比较，不是定时器；还没有找到能替代它的事件钩子，所以保留）。
 *
 * 位置（px）由抖音里的位置浮窗（TimePanel）调整。
 */
final class TimeHook {
    private TimeHook() {}

    /** 版本相关（40.7.0）。 */
    private static final String DELEGATE = "com.ss.android.ugc.aweme.video.simplayer.listener.base.OnUIPlayListenerDelegate";
    private static final String PROGRESS = "onPlayProgressChange";

    private static final long STALE_MS = 1000;  // 离开 / 换视频后忽略旧视频进度回调的时间
    private static final String PLACEHOLDER = "00:00 / --:--";
    private static final Pattern DURATION = Pattern.compile("duration=(\\d+)");

    private static final Handler MAIN = new Handler(Looper.getMainLooper()); // 只用来把非主线程的事件转回主线程

    private static volatile boolean progressHooked, eventsHooked, stopHooked, releaseHooked, playingHooked;

    private static WeakReference<TextView> viewRef = new WeakReference<>(null);

    // 状态（都在主线程用）
    private static String lastId, staleId;
    private static long lastSec = -1, lastDsec = -2, staleUntil;
    private static String lastText = "";
    private static int lastDx = Integer.MIN_VALUE, lastDy = Integer.MIN_VALUE;
    private static boolean shown;

    // ------------------------------------------------------------ 安装

    static boolean allHooked() {
        return progressHooked && eventsHooked;
    }

    /** 目标 App 的类加载器；启动时试一次，之后每次回到前台再试，直到都挂上。 */
    static void tryHook(ClassLoader cl) {
        if (cl == null || allHooked()) return;
        attempt(cl);
        if (!progressHooked) {
            ClassLoader ctx = Thread.currentThread().getContextClassLoader();
            if (ctx != null && ctx != cl) attempt(ctx);
        }
    }

    private static void attempt(ClassLoader cl) {
        if (!progressHooked) progressHooked = hookAll(cl, DELEGATE, PROGRESS, new ProgressHook());
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

    /** 状态回调：名字已由 TextRec 追踪确认，不再扫描。 */
    private static boolean hookEvents(ClassLoader cl) {
        XC_MethodHook stop = new StopHook();
        if (!stopHooked) stopHooked = hookAll(cl, DELEGATE, "onPlayStop", stop);
        if (!releaseHooked) releaseHooked = hookAll(cl, DELEGATE, "onPlayRelease", stop);
        if (!playingHooked) playingHooked = hookAll(cl, DELEGATE, "onPlaying", new PlayingHook());
        return stopHooked && releaseHooked && playingHooked; // 分开记，重试时不会把已经挂上的再挂一遍
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

    /** 离开这个视频（划走 / 播放器释放）：立刻隐藏。before：比播放器自己的处理更早。 */
    private static final class StopHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            try {
                final String id = firstString(p.args);
                if (id == null) return;
                if (isMain()) {
                    onStop(id);
                } else {
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            onStop(id);
                        }
                    });
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** 开始播放：(String id) 只记下当前视频；(String id, PlayerEvent) 带总时长，立刻画出来。 */
    private static final class PlayingHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam p) {
            try {
                Object[] a = p.args;
                final String id = firstString(a);
                if (id == null) return;
                final Object ev = a.length > 1 ? a[1] : null;
                if (isMain()) {
                    onPlaying(id, ev);
                } else {
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            onPlaying(id, ev);
                        }
                    });
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static String firstString(Object[] a) {
        if (a != null && a.length > 0 && a[0] instanceof String) return (String) a[0];
        return null;
    }

    // ------------------------------------------------------------ 状态事件

    private static void onStop(String id) {
        if (!id.equals(lastId)) return; // 别的视频的事件（比如旧视频迟到的）不动当前文字
        staleId = id;
        staleUntil = SystemClock.uptimeMillis() + STALE_MS; // 它迟到的进度回调不再把文字画回来
        hideNow();
    }

    private static void onPlaying(String id, Object ev) {
        if (!enabled()) return;
        if (id.equals(staleId)) staleId = null; // 这个视频又开始播了（比如划回去）
        boolean changed = switchTo(id);
        if (ev == null) return;                 // 一个参数的重载没有时长，等带 PlayerEvent 的那个
        if (shown && !changed && lastDsec >= 0) return; // 暂停后继续：文字本来就在，别把位置改回 0
        long dms = durationOf(ev);
        if (dms <= 0) return;                   // 没取到时长就交给第一个进度回调
        lastSec = 0;
        lastDsec = dms / 1000;
        show(CommentHook.top.get(), fmt(0) + " / " + fmt(lastDsec));
    }

    /** PlayerEvent{id='…', codecType=0, duration=26331, …}：从 toString 里取 duration（毫秒），不依赖字段名。 */
    private static long durationOf(Object ev) {
        try {
            Matcher m = DURATION.matcher(String.valueOf(ev));
            return m.find() ? Long.parseLong(m.group(1)) : -1;
        } catch (Throwable t) {
            return -1;
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

    // ------------------------------------------------------------ 进度

    private static void onProgress(String id, long pos, long dur) {
        if (!enabled()) {
            removeView();
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (id != null && id.equals(staleId) && now < staleUntil) return; // 刚离开的那个视频的尾巴
        boolean newVideo = id != null && !id.equals(lastId);
        if (newVideo) switchTo(id);
        long sec = pos > 0 ? pos / 1000 : 0;
        long dsec = dur > 0 ? dur / 1000 : -1;
        if (shown && sec == lastSec && dsec == lastDsec) return; // 秒数没变
        if (dsec >= 0 && sec > dsec) sec = dsec;
        lastSec = sec;
        lastDsec = dsec;
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

    /** 立刻隐藏；下一次进度回调（或 onPlaying）会重新显示。 */
    private static void hideNow() {
        shown = false;
        lastSec = -1;
        lastDsec = -2;
        TextView t = viewRef.get();
        if (t != null) t.setVisibility(View.GONE);
    }

    // ------------------------------------------------------------ 生命周期

    private static void detach(View v) {
        if (v != null && v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
    }

    private static void removeView() {
        shown = false;
        detach(viewRef.get());
    }

    /** 位置浮窗关闭后：没开常显就把文字撤掉。 */
    static void onPanelClosed() {
        if (!DanmakuHook.cfg.timeAlways) removeView();
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
