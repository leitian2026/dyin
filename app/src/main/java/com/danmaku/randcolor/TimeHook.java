package com.danmaku.randcolor;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.robv.android.xposed.XC_MethodHook;

/**
 * 视频时长常显：自己放一行 "00:12 / 03:45"，不用抖音的时间控件。
 * 1.22.0 起这一行是当前视频的 AwemeIntroInfoLayout 里的一个 TextView，在发布信息那一行的正下方（没显示发布信息就在文案下面），
 * 由 PubHook.timeView() 负责放进去并设字号、颜色；位置浮窗（TimePanel）和 timeX / timeY 都删了。
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
 */
final class TimeHook {
    private TimeHook() {}

    /** 版本相关（40.7.0）。 */
    private static final String DELEGATE = "com.ss.android.ugc.aweme.video.simplayer.listener.base.OnUIPlayListenerDelegate";
    private static final String PROGRESS = "onPlayProgressChange";

    private static final long STALE_MS = 1000;  // 离开 / 换视频后忽略旧视频进度回调的时间
    private static final Pattern DURATION = Pattern.compile("duration=(\\d+)");

    private static final Handler MAIN = new Handler(Looper.getMainLooper()); // 只用来把非主线程的事件转回主线程

    private static final XC_MethodHook PROGRESS_HOOK = new ProgressHook();
    private static final XC_MethodHook STOP_HOOK = new StopHook();
    private static final XC_MethodHook PLAYING_HOOK = new PlayingHook();

    // 状态（都在主线程用）
    private static String lastId, staleId;
    private static long lastSec = -1, lastDsec = -2, staleUntil;
    private static String lastText = "";
    private static boolean shown;

    // ------------------------------------------------------------ 安装

    /**
     * 目标 App 的类加载器；启动时试一次，之后每次回到前台再试，直到每个钩子都有结论（挂上 / 放弃 / 不再重试，见 HookGuard）。
     * 名字都是没混淆的（OnUIPlayListenerDelegate 的四个回调），类在但方法对不上就是抖音改了，立刻放弃并记住。
     */
    static void tryHook(ClassLoader cl) {
        if (cl == null) return;
        ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        hook(HookGuard.TIME_PROGRESS, PROGRESS, PROGRESS_HOOK, cl, ctx);
        hook(HookGuard.TIME_STOP, "onPlayStop", STOP_HOOK, cl, ctx);
        hook(HookGuard.TIME_RELEASE, "onPlayRelease", STOP_HOOK, cl, ctx);
        hook(HookGuard.TIME_PLAYING, "onPlaying", PLAYING_HOOK, cl, ctx);
    }

    private static void hook(int id, String method, XC_MethodHook h, ClassLoader cl, ClassLoader ctx) {
        HookGuard.hook(id, DELEGATE, HookGuard.named(method), h,
                "OnUIPlayListenerDelegate 里没有 " + method + "（播放器监听接口变了，需要重新追踪）", cl, ctx);
    }

    private static boolean isMain() {
        return Looper.myLooper() == Looper.getMainLooper();
    }

    /** 时长文字是否应该存在：设置里开了。 */
    private static boolean enabled() {
        return DanmakuHook.cfg.timeAlways;
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
        PubHook.onStop(id); // 发布日期地点的文字也跟着撤掉（它自己比较视频 id）
        if (!id.equals(lastId)) return; // 别的视频的事件（比如旧视频迟到的）不动当前文字
        staleId = id;
        staleUntil = SystemClock.uptimeMillis() + STALE_MS; // 它迟到的进度回调不再把文字画回来
        hideNow();
    }

    private static void onPlaying(String id, Object ev) {
        PubHook.onPlay(id); // 换了视频就撤掉上一个视频的发布日期地点；不依赖时长有没有开
        if (!enabled()) return;
        if (id.equals(staleId)) staleId = null; // 这个视频又开始播了（比如划回去）
        boolean changed = switchTo(id);
        if (ev == null) return;                 // 一个参数的重载没有时长，等带 PlayerEvent 的那个
        if (shown && !changed && lastDsec >= 0) return; // 暂停后继续：文字本来就在，别把位置改回 0
        long dms = durationOf(ev);
        if (dms <= 0) return;                   // 没取到时长就交给第一个进度回调
        lastSec = 0;
        lastDsec = dms / 1000;
        show(fmt(0) + " / " + fmt(lastDsec));
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
        show(fmt(sec) + " / " + (dsec >= 0 ? fmt(dsec) : "--:--"));
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

    /** 把文字放进当前视频的时长行（行由 PubHook 放进容器、设字号和颜色）；当前视频的容器还没认出来就先不显示。 */
    private static void show(String text) {
        lastText = text;
        TextView t = PubHook.timeView();
        if (t == null) {
            shown = false;
            return;
        }
        if (!text.contentEquals(t.getText())) t.setText(text);
        if (t.getVisibility() != View.VISIBLE) t.setVisibility(View.VISIBLE);
        shown = true;
    }

    /** PubHook 刚认出当前视频的容器：进度已经在跑，就把时长放进去（不用等下一秒的进度回调）。 */
    static void onIntroReady() {
        try {
            if (!isMain() || !enabled() || lastSec < 0 || lastText.isEmpty()) return;
            show(lastText);
        } catch (Throwable ignored) {
        }
    }

    /** 立刻隐藏；下一次进度回调（或 onPlaying）会重新显示。 */
    private static void hideNow() {
        shown = false;
        lastSec = -1;
        lastDsec = -2;
        PubHook.hideTime();
    }

    // ------------------------------------------------------------ 生命周期

    private static void removeView() {
        shown = false;
        PubHook.removeTime();
    }

    /** 回到前台（配置刚重读过）：没开常显就把时长行拿掉；开着的话按新的字号、颜色重新套一遍。 */
    static void onResume(Activity a) {
        try {
            if (!isMain()) return;
            if (!enabled()) {
                removeView();
                return;
            }
            if (lastSec >= 0 && !lastText.isEmpty()) show(lastText);
        } catch (Throwable ignored) {
        }
    }
}
