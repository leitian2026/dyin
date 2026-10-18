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
 * 后一个直接带着位置和时长，例如 ("7697697250122729935", 638, 25727)。信息流专用的 PMPlayListenerDispatcherV2
 * 只是它的下游，所以改钩这一层（不再认播放器引擎、不依赖信息流）。
 *
 * 做法（没有轮询，全部由抖音自己的回调驱动）：
 *  1) 钩 onPlayProgressChange，读 (位置, 总时长)；同一个位置会经过好几个包装监听器重复到达，按位置去重。
 *  2) 格式化成 mm:ss，只在文字变化时才 setText。
 *  3) 文字是一个独立的 TextView，加在当前 Activity 的最上层，默认在屏幕正中，按设置里的偏移（dp）移动，不拦截触摸。
 *  4) 可见性：3 秒内收到过进度回调就显示；没有回调（暂停等）时，进度条（ProSeekBarView#setProgress 的 this）
 *     还在屏幕上就继续显示，离开视频页后隐藏；没抓到进度条时，3 秒没有回调就隐藏。
 */
final class TimeHook {
    private TimeHook() {}

    /** 版本相关（40.7.0）。 */
    private static final String DELEGATE = "com.ss.android.ugc.aweme.video.simplayer.listener.base.OnUIPlayListenerDelegate";
    private static final String PROGRESS = "onPlayProgressChange";
    private static final String SEEKBAR = "com.ss.android.ugc.aweme.o.seekbar.views.seekbar.ProSeekBarView";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile boolean progressHooked, seekHooked;
    private static int misses;

    private static WeakReference<View> seekRef = new WeakReference<>(null);
    private static long lastPos = Long.MIN_VALUE, lastDur = Long.MIN_VALUE;
    private static boolean loggedOk;

    private static WeakReference<TextView> viewRef = new WeakReference<>(null);
    private static String lastText = "";
    private static int lastDx = Integer.MIN_VALUE, lastDy = Integer.MIN_VALUE;
    private static long lastTickAt;
    private static final Rect TMP = new Rect();

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

    // ------------------------------------------------------------ 钩子

    /** 进度回调：(String 视频id, long 位置ms, long 总时长ms) 这个重载直接带着时间；float 百分比那个重载忽略。 */
    private static final class ProgressHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam p) {
            try {
                Object[] a = p.args;
                if (a == null || a.length != 3 || !(a[1] instanceof Number) || !(a[2] instanceof Number)) return;
                if (!isMain()) return;
                onProgress(((Number) a[1]).longValue(), ((Number) a[2]).longValue());
            } catch (Throwable ignored) {
            }
        }
    }

    /** 进度条自己（每次进度动画都会调 setProgress）：只记下这个 View，用来判断是不是还在视频页。 */
    private static final class SeekHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam p) {
            Object o = p.thisObject;
            if (o instanceof View && seekRef.get() != o) seekRef = new WeakReference<>((View) o);
        }
    }

    // ------------------------------------------------------------ 显示

    private static void onProgress(long pos, long dur) {
        if (!DanmakuHook.cfg.timeAlways) {
            removeView();
            return;
        }
        if (pos == lastPos && dur == lastDur) return; // 同一个位置经过多个包装监听器重复到达
        lastPos = pos;
        lastDur = dur;
        if (!loggedOk) {
            loggedOk = true;
            XposedBridge.log("[DanmakuColor] time ok: pos=" + pos + "ms dur=" + dur + "ms");
        }
        if (pos < 0) pos = 0;
        if (dur > 0 && pos > dur) pos = dur;
        show(fmt(pos) + " / " + (dur > 0 ? fmt(dur) : "--:--"));
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

    private static boolean isHostActivity(Activity a) {
        return a != null && !a.isFinishing() && a.getClass().getName().contains("MainActivity");
    }

    private static void show(String text) {
        Activity a = CommentHook.top.get();
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
        if (t.getParent() != decor) {
            detach(t);
            decor.addView(t, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        } else if (decor.indexOfChild(t) != decor.getChildCount() - 1) {
            t.bringToFront();
        }
        applyOffset(t, d);
        if (!text.equals(lastText)) {
            lastText = text;
            t.setText(text);
        }
        if (t.getVisibility() != View.VISIBLE) t.setVisibility(View.VISIBLE);
        lastTickAt = System.currentTimeMillis();
        MAIN.removeCallbacks(CHECK);
        MAIN.postDelayed(CHECK, 1000);
    }

    private static void applyOffset(TextView t, float d) {
        DanmakuConfig c = DanmakuHook.cfg;
        if (c.timeDx == lastDx && c.timeDy == lastDy) return;
        lastDx = c.timeDx;
        lastDy = c.timeDy;
        t.setTranslationX(c.timeDx * d);
        t.setTranslationY(c.timeDy * d);
    }

    /** 每秒检查一次：进度条还在屏幕上就继续显示，否则隐藏。只在显示期间运行，隐藏后不再排队。 */
    private static final Runnable CHECK = new Runnable() {
        @Override
        public void run() {
            try {
                TextView t = viewRef.get();
                if (t == null || t.getVisibility() != View.VISIBLE) return;
                boolean alive = System.currentTimeMillis() - lastTickAt < 3000; // 正在播放：有进度回调
                if (!alive) {
                    View sb = seekRef.get(); // 暂停等没有回调的情况：进度条还在屏幕上就继续显示
                    alive = sb != null && sb.isAttachedToWindow() && sb.isShown() && sb.getGlobalVisibleRect(TMP);
                }
                if (alive && DanmakuHook.cfg.timeAlways) {
                    MAIN.postDelayed(this, 1000);
                } else {
                    t.setVisibility(View.GONE);
                }
            } catch (Throwable ignored) {
            }
        }
    };

    private static void detach(View v) {
        if (v != null && v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
    }

    private static void removeView() {
        MAIN.removeCallbacks(CHECK);
        detach(viewRef.get());
    }

    /** 回到前台（配置刚重读过）：换了页面就拿掉；位置有变就立即生效。 */
    static void onResume(Activity a) {
        try {
            if (Looper.myLooper() != Looper.getMainLooper()) return;
            TextView t = viewRef.get();
            if (t == null) return;
            if (!DanmakuHook.cfg.timeAlways || !isHostActivity(a) || t.getContext() != a) {
                removeView();
                return;
            }
            applyOffset(t, a.getResources().getDisplayMetrics().density);
        } catch (Throwable ignored) {
        }
    }
}
