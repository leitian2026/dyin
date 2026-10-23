package com.danmaku.randcolor;

import android.os.SystemClock;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 个人主页默认停在「喜欢」标签（1.32.1 重写，1.32.0 是「点底部『我』开 3 秒窗口」的做法，太绕，已去掉）。
 *
 * 规则只有一条：个人主页的分页控件被切页时，只有「用户自己点了页内标签」才听用户的，其余一律切到最后一页（= 喜欢）。
 * 抖音每次进主页都会自己调 setCurrentItem 恢复标签（第一次是 0，之后是上次选的），这些调用都不是用户点出来的，所以每次进「我」都会落在「喜欢」；
 * 用户点「作品」「收藏」等页内标签时，那次切页会被放过，想看哪个看哪个。
 *
 * 依据（TextRec 40.9.0，textrec-tasks-20261023-231633、textrec-tasks-20261024-071912）：
 *  - 分页控件 com.ss.android.ugc.aweme.profile.widgets.ProfileViewPager（类名没混淆）；它的 setCurrentItem(int) / (int, boolean) 是 androidx ViewPager 里声明的，
 *    ProfileViewPager 不覆写。点「喜欢」→ (4)，点「作品」→ (0)；「喜欢」是最后一页。
 *  - 进主页时抖音自己调 (0)（第一次进，共 4 次，来自混淆名的协程 / Runnable）；之后每次进主页调的是它记住的标签（(0) 或 (4)）；分页控件和适配器整个进程只建一次（对象身份相同，setAdapter 只出现一次）。
 *  - 用户点页内标签：DmtTabLayout$TabView#performClick（类名、方法名都没混淆，方法是 TabView 自己声明的，栈里是 DmtTabLayout$TabView.performClick），
 *    点完 20 毫秒内就是对应的 setCurrentItem（点「作品」晚一点，经过一个 Runnable，仍在 1.2 秒内）。
 *
 * 1.32.2：prof.pager 不再在安装时 Class.forName ProfileViewPager（它要等第一次进个人主页才加载，之前这里一直找不到，钩子挂不上，设置页「钩子状态」里是「还没挂上」），
 *   只确认 androidx 的 ViewPager 在，回调里按 this 的类名字符串精确比较。
 * 钩子：prof.tap = DmtTabLayout$TabView#performClick（只挂这个类自己的方法，走 HookGuard.hook，不是全局钩子），只记一个时刻；
 *       prof.pager = ViewPager#setCurrentItem 两个重载（先确认 ProfileViewPager 在、是它的子类），回调里按 this 的类过滤、比较时间、改参数。
 * 越界页号 99 由 ViewPager 自己收到最后一页；适配器还没设好时它直接返回，之后抖音的下一次 setCurrentItem 还会被改。没有定时器、没有轮询，回调里不分配对象、不反射。
 *
 * 局限（没上机、没编译）：
 *  - 别人的主页如果用的也是 ProfileViewPager，进去也会落在最后一页；追踪里没抓过别人的主页，不知道它用不用同一个类。
 *  - 「喜欢」是最后一页，只有一个账号的证据。
 *  - 用户点页内标签之后 1.2 秒内抖音自己的切页会被放过；正常点一下不会碰到。
 * 没挂上时个人主页保持原样，安静失效。
 */
final class ProfHook {
    private ProfHook() {}

    private static final String PAGER = "com.ss.android.ugc.aweme.profile.widgets.ProfileViewPager";
    private static final String VP = "androidx.viewpager.widget.ViewPager";
    private static final String TABVIEW = "com.bytedance.ies.dmt.ui.widget.tablayout.DmtTabLayout$TabView";

    /** 用户点页内标签之后多久内的切页算用户自己选的。 */
    private static final long USER_MS = 1200L;
    /** 越界页号：ViewPager 收到最后一页（= 喜欢）。 */
    private static final Integer LAST = 99;

    private static final XC_MethodHook PAGER_HOOK = new PagerHook();
    private static final XC_MethodHook TAP_HOOK = new TapHook();

    private static volatile long userAt;

    /** 启动时和每次回到前台都会被调用；功能没开就不挂（没有结论，开了之后回到前台再挂）。 */
    static void tryHook(ClassLoader cl) {
        if (cl == null || !DanmakuHook.cfg.profFav) return;
        tryPager(cl);
        HookGuard.hook(HookGuard.PROF_TAP, TABVIEW, HookGuard.named("performClick"), TAP_HOOK,
                "DmtTabLayout$TabView 里没有 performClick（需要重新用 TextRec 追踪个人主页点页内标签）", cl);
    }

    private static synchronized void tryPager(ClassLoader cl) {
        if (HookGuard.settled(HookGuard.PROF_PAGER)) return;
        // 只找 androidx 的 ViewPager（启动时就在）。ProfileViewPager 要等第一次进个人主页才会加载，类名在回调里按字符串比，不在这里 Class.forName（1.32.1 在这里找它，找不到就一直挂不上）。
        Class<?> vp = HookGuard.find(HookGuard.PROF_PAGER, VP, cl);
        if (vp == null) return;
        Method a, b;
        try {
            a = vp.getDeclaredMethod("setCurrentItem", int.class);
            b = vp.getDeclaredMethod("setCurrentItem", int.class, boolean.class);
        } catch (NoSuchMethodException e) {
            HookGuard.miss(HookGuard.PROF_PAGER, "ViewPager 里没有 setCurrentItem(int) / (int, boolean)（需要重新用 TextRec 追踪个人主页切标签）");
            return;
        }
        try {
            XposedBridge.hookMethod(a, PAGER_HOOK);
            XposedBridge.hookMethod(b, PAGER_HOOK);
        } catch (Throwable t) {
            HookGuard.miss(HookGuard.PROF_PAGER, "ViewPager#setCurrentItem 挂不上：" + t);
            return;
        }
        HookGuard.ok(HookGuard.PROF_PAGER, "");
    }

    /** 用户点了页内标签（作品 / 日常 / 推荐 / 收藏 / 喜欢……）：只记时刻。 */
    private static final class TapHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            try {
                userAt = SystemClock.uptimeMillis();
            } catch (Throwable ignored) {
            }
        }
    }

    private static final class PagerHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            try {
                if (!DanmakuHook.cfg.profFav) return;
                if (!PAGER.equals(p.thisObject.getClass().getName())) return; // getName 返回缓存的字符串，不分配
                if (SystemClock.uptimeMillis() - userAt < USER_MS) return; // 用户自己点的：放过
                p.args[0] = LAST;
                if (p.args.length > 1) p.args[1] = Boolean.FALSE; // 不演滑动动画
            } catch (Throwable ignored) {
            }
        }
    }
}
