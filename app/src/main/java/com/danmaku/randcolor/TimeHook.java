package com.danmaku.randcolor;

import android.os.Looper;

import de.robv.android.xposed.XC_MethodHook;

/**
 * 视频时长常显的进度来源（1.27.0 重做：只剩这一个钩子 time.progress）。
 *
 * 时长现在是每个视频页自己的一个浮层（盖在这一页的页面根上，由 PubHook 在翻页绑定时放好，1.28.0 起位置全屏可调），
 * 这里只负责把播放进度转给它：onPlayProgressChange(id, 位置ms, 总时长ms) 的 id 就是 Aweme.aid，
 * PubHook 按 id 找到页面更新文字。划走的页面本来就在屏幕外，所以不需要「停止 / 释放 / 开始播放」这些事件，
 * 也不需要「当前视频」这个概念，也不需要忽略旧视频进度的 1 秒窗口（旧版的 time.stop / time.release / time.playing 都删了）。
 *
 * 依据：SimPlayer 公共监听层 OnUIPlayListenerDelegate（40.7.0，主线程；名字没混淆，第一个参数是视频 id），
 * TextRec 1.22.4 的追踪：onPlayProgressChange(id, 位置ms, 总时长ms) 每秒来几次，同一个事件会经过 5～6 个包装监听器重复到达，
 * 处理是幂等的（PubHook 里秒数没变就直接返回）。
 *
 * 没有定时器 / 轮询。类在但方法对不上就是抖音改了，立刻放弃并记住（HookGuard）；没挂上时时长只显示 bind 时的「00:00 / 总时长」。
 */
final class TimeHook {
    private TimeHook() {}

    /** 版本相关（40.7.0）。 */
    private static final String DELEGATE = "com.ss.android.ugc.aweme.video.simplayer.listener.base.OnUIPlayListenerDelegate";
    private static final String PROGRESS = "onPlayProgressChange";

    private static final XC_MethodHook PROGRESS_HOOK = new ProgressHook();

    /** 目标 App 的类加载器；启动时试一次，之后每次回到前台再试，直到有结论（挂上 / 放弃 / 不再重试，见 HookGuard）。 */
    static void tryHook(ClassLoader cl) {
        if (cl == null) return;
        if (!DanmakuHook.cfg.timeAlways) return; // 视频时长没开：不挂这个每秒好几次的钩子（没有结论，打开后回到前台再挂）
        ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        HookGuard.hook(HookGuard.TIME_PROGRESS, DELEGATE, HookGuard.named(PROGRESS), PROGRESS_HOOK,
                "OnUIPlayListenerDelegate 里没有 " + PROGRESS + "（播放器监听接口变了，需要重新追踪）", cl, ctx);
    }

    private static final class ProgressHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam p) {
            try {
                Object[] a = p.args;
                if (a == null || a.length != 3 || !(a[1] instanceof Number) || !(a[2] instanceof Number)) return;
                if (Looper.myLooper() != Looper.getMainLooper()) return;
                PubHook.onProgress(a[0] instanceof String ? (String) a[0] : null, ((Number) a[1]).longValue(), ((Number) a[2]).longValue());
            } catch (Throwable ignored) {
            }
        }
    }
}
