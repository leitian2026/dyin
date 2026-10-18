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
import java.lang.reflect.Method;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 视频时长常显：自己画一个 "00:12 / 03:45"，不再借用抖音的时间控件。
 *
 * 依据 TextRec 的追踪（40.7.0）：
 *  - PMPlayListenerDispatcherV2#onPlayProgressChange：播放时每次进度更新都在主线程调用（名字没混淆）。
 *    它的调用链里会调用 TTVideoEngine#getDuration()，所以在它执行期间被调用的那个 TTVideoEngine 就是当前正在播的。
 *  - TTVideoEngine#getCurrentPlaybackTime() / getDuration()：公开方法，返回毫秒。
 *
 * 做法（没有轮询，全部由抖音自己的回调驱动）：
 *  1) 进度回调执行前后各打一个标记；标记期间 TTVideoEngine#getDuration / getCurrentPlaybackTime 被调用，就记住这个引擎。
 *  2) 进度回调结束后，用记住的引擎读当前位置和总时长，格式化成 mm:ss，只在秒数变化时才 setText。
 *  3) 文字是一个独立的 TextView，加在当前 Activity 的最上层，默认在屏幕正中，按设置里的偏移（dp）移动，不拦截触摸。
 *  4) 可见性：进度条（ProSeekBarView#setProgress 的 this）还在屏幕上就保持显示（暂停时也不消失），
 *     离开视频页（进度条不可见 / 脱离窗口）后隐藏。没抓到进度条时，3 秒没有进度回调就隐藏。
 */
final class TimeHook {
    private TimeHook() {}

    /** 版本相关（40.7.0）。 */
    private static final String PM = "com.ss.android.ugc.aweme.feed.controller.refactor.impl_pm.PMPlayListenerDispatcherV2";
    private static final String PM_METHOD = "onPlayProgressChange";
    private static final String ENGINE = "com.ss.ttvideoengine.TTVideoEngine";
    private static final String SEEKBAR = "com.ss.android.ugc.aweme.o.seekbar.views.seekbar.ProSeekBarView";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile boolean progressHooked, engineHooked, seekHooked;
    private static int misses;

    /** 只在主线程读写。 */
    private static boolean inProgress;
    private static WeakReference<Object> engineRef = new WeakReference<>(null);
    private static WeakReference<View> seekRef = new WeakReference<>(null);
    private static Class<?> mCls;
    private static Method mPos, mDur;
    private static boolean loggedOk;

    private static WeakReference<TextView> viewRef = new WeakReference<>(null);
    private static String lastText = "";
    private static int lastDx = Integer.MIN_VALUE, lastDy = Integer.MIN_VALUE;
    private static long lastTickAt;
    private static final Rect TMP = new Rect();

    // ------------------------------------------------------------ 安装

    /** 目标 App 的类加载器；启动时试一次，之后每次回到前台再试，直到都挂上。 */
    static void tryHook(ClassLoader cl) {
        if (cl == null || (progressHooked && engineHooked && seekHooked)) return;
        attempt(cl);
        if (!(progressHooked && engineHooked)) {
            ClassLoader ctx = Thread.currentThread().getContextClassLoader();
            if (ctx != null && ctx != cl) attempt(ctx);
        }
        if (!(progressHooked && engineHooked) && ++misses == 6) {
            XposedBridge.log("[DanmakuColor] time hook: classes not found yet (progress=" + progressHooked
                    + ", engine=" + engineHooked + ")");
        }
    }

    private static void attempt(ClassLoader cl) {
        if (!progressHooked) progressHooked = hookAll(cl, PM, PM_METHOD, new ProgressHook());
        if (!engineHooked) {
            CaptureHook cap = new CaptureHook();
            boolean a = hookAll(cl, ENGINE, "getDuration", cap);
            boolean b = hookAll(cl, ENGINE, "getCurrentPlaybackTime", cap);
            engineHooked = a || b;
        }
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

    /** 进度回调：前面打标记，后面读时间并更新显示。 */
    private static final class ProgressHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            if (isMain()) inProgress = true;
        }

        @Override
        protected void afterHookedMethod(MethodHookParam p) {
            if (!isMain()) return;
            inProgress = false;
            try {
                tick();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 进度回调执行期间，TTVideoEngine 的这两个读数方法被谁调用，谁就是当前引擎。 */
    private static final class CaptureHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam p) {
            if (!inProgress || !isMain()) return;
            Object o = p.thisObject;
            if (o != null && engineRef.get() != o) engineRef = new WeakReference<>(o);
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

    // ------------------------------------------------------------ 读时间 + 显示

    private static void tick() throws Throwable {
        if (!DanmakuHook.cfg.timeAlways) {
            removeView();
            return;
        }
        Object e = engineRef.get();
        if (e == null) return;
        if (mCls != e.getClass()) {
            mCls = e.getClass();
            mPos = mDur = null;
            try {
                mPos = mCls.getMethod("getCurrentPlaybackTime");
                mDur = mCls.getMethod("getDuration");
            } catch (Throwable t) {
                XposedBridge.log("[DanmakuColor] time: engine methods missing " + t);
                return;
            }
        }
        if (mPos == null || mDur == null) return;
        long pos = num(mPos.invoke(e, (Object[]) null));
        long dur = num(mDur.invoke(e, (Object[]) null));
        if (!loggedOk) {
            loggedOk = true;
            XposedBridge.log("[DanmakuColor] time ok: pos=" + pos + "ms dur=" + dur + "ms");
        }
        if (pos < 0) pos = 0;
        if (dur > 0 && pos > dur) pos = dur;
        show(fmt(pos) + " / " + (dur > 0 ? fmt(dur) : "--:--"));
    }

    private static long num(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : -1;
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
                View sb = seekRef.get();
                boolean alive;
                if (sb != null) {
                    alive = sb.isAttachedToWindow() && sb.isShown() && sb.getGlobalVisibleRect(TMP);
                } else {
                    alive = System.currentTimeMillis() - lastTickAt < 3000; // 没抓到进度条：看有没有进度回调
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
