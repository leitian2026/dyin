package com.danmaku.randcolor;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 无操作自动退出：连续 N 分钟（设置页里填，0 = 关闭）没有操作抖音，就结束抖音进程。
 *
 * 怎么记、怎么判断（没有倒计时，不每秒走）：
 *  - 记：只记一个时刻 lastActive。Activity.onUserInteraction（手指按下 / 按键按下时系统调一次，
 *    滑动过程中的移动事件不会调）和 Activity.onResume（回到前台也算一次操作）各把它覆盖成「现在」，
 *    不比较、不排消息。时钟用 elapsedRealtime（包含休眠时间）。
 *  - 判：只有一条延时消息 CHECK。回到前台时排一条 N 分钟后的；到点醒来才做一次减法
 *    （现在 − lastActive）：不够 N 分钟，就按剩余时间再排一条；够了，结束进程。
 *    N 分钟里不管操作多少次，消息都只在到点时醒一回。
 *  - 抖音在后台也照常计时（后台被冻结时消息会晚到，没有损失）。
 *  - 晚到保护：消息比预定时间晚了 LATE_MS 以上（进程刚被冻结又醒来），而你恰好在这一刻切回抖音，
 *    onResume / 触摸事件可能还排在后面没处理。所以这种情况先把检查往消息队列末尾再放一次，
 *    让它们先把 lastActive 更新完，再判断，避免误杀刚切回来的抖音。
 *
 * 这是整个模块里唯一的延时消息，它是功能本身，不是自检用的定时器（HOOKS.md 第 4 节第 6 条禁止的是后者）。
 * onUserInteraction 是 Android 框架方法，不走 HookGuard（HOOKS.md 第 5 节）。
 * 全部在主线程，所以不需要同步。
 */
final class IdleExit {
    private IdleExit() {}

    private static final long LATE_MS = 5000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static long lastActive = SystemClock.elapsedRealtime();
    private static long dueAt;
    private static boolean requeued;

    /** 钩 Activity.onUserInteraction：每次操作只覆盖一个变量。 */
    static void install() {
        try {
            XposedBridge.hookMethod(Activity.class.getDeclaredMethod("onUserInteraction"), new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        lastActive = SystemClock.elapsedRealtime();
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("[DanmakuColor] hook Activity.onUserInteraction failed: " + t);
        }
    }

    /** 每次回到前台（DanmakuHook.hookResume 里，配置已重读）：算一次操作，重新排消息。 */
    static void onResume() {
        lastActive = SystemClock.elapsedRealtime();
        MAIN.removeCallbacks(CHECK);
        requeued = false;
        long ms = DanmakuHook.cfg.idleExitMin * 60000L;
        if (ms > 0) schedule(ms);
    }

    private static void schedule(long delayMs) {
        dueAt = SystemClock.uptimeMillis() + delayMs;
        MAIN.postDelayed(CHECK, delayMs);
    }

    private static final Runnable CHECK = new Runnable() {
        @Override
        public void run() {
            try {
                long ms = DanmakuHook.cfg.idleExitMin * 60000L;
                if (ms <= 0) return;
                long idle = SystemClock.elapsedRealtime() - lastActive;
                if (idle < ms) { // 这段时间里有操作：按剩余时间再约一次
                    requeued = false;
                    schedule(ms - idle);
                    return;
                }
                if (!requeued && SystemClock.uptimeMillis() - dueAt > LATE_MS) {
                    requeued = true; // 晚到：让排队中的 onResume / 触摸先处理，再判断一次
                    dueAt = SystemClock.uptimeMillis();
                    MAIN.post(this);
                    return;
                }
                Process.killProcess(Process.myPid());
            } catch (Throwable ignored) {
            }
        }
    };
}
