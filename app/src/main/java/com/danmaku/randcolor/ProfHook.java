package com.danmaku.randcolor;

import android.os.Handler;
import android.os.Looper;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 个人主页默认停在「喜欢」（1.35.0 重写：触发 + 一次主动切页；不再改抖音自己的切页参数）。
 *
 * 两步：
 *  1. 触发 prof.enter = com.ss.android.ugc.aweme.main.MainPageFragment$2#onChanged(Object)：底部标签切换的观察者，参数是一个 Pair，toString 形如
 *     "(USER, TabRouteConfig(enterFrom='change_selected_bottom_tab', ...))"；第一项是 USER = 切到底部「我」，HOME = 切到首页。
 *  2. 动作：触发时对个人主页的分页控件调一次 setCurrentItem(99, false)；99 越界，ViewPager 自己收成最后一页（= 喜欢），不认标签、不扫视图树、不依赖标签个数。
 *     分页控件从哪来：prof.pager 挂 ViewPager#setCurrentItem 两个重载，只做一件事——认出 this 是 ProfileViewPager 就记一个弱引用（整个进程只建一次，只在换对象时才分配）。
 *     触发时没有记下的对象 = 什么都不做。
 *  ★ 特例（1.35.1，唯一的延时消息，和 IdleExit 那一条一样是 HOOKS.md 里写明的例外，不是先例）：
 *     进程里**第一次**触发（= 冷启动后第一次点「我」）不立刻切，而是发一条 700ms 的延时消息再切；之后每次触发都是立刻切，不再发消息。
 *     原因：冷启动时抖音自己恢复标签的 setCurrentItem(0) 在触发之后还有 3 次（见下面「依据」），立刻切会被它们盖回「作品」。
 *     消息只发一次、不重复、不循环，不是轮询，也不是自检；回调里不分配（Handler 和 Runnable 都只建一次）。
 *
 * 依据（TextRec 40.9.0，textrec-tasks-20261024-141908，加上之前的 20261023-231633、20261024-071912）：
 *  - MainPageFragment$2#onChanged：类名、方法名没混淆；签名 public final (Object) → void，同名重载只有 1 个；实现的是 androidx.lifecycle.Observer#onChanged，
 *    调用栈 NextLiveData$NextObserver.onChanged ← LiveData.considerNotify ← dispatchingValue ← setValue（主线程），启动后 28ms 就能找到这个类。
 *    点「我」两次各调一次 (USER, …)，点「首页」调 (HOME, …)。
 *  - 冷启动第一次进「我」：setAdapter(ProfileViewPager) 在 19329ms、setCurrentItem(0) 第一次 19351ms、onChanged(USER) 19603ms、setCurrentItem(0) 一共 4 次到 20154ms。
 *    所以触发时分页控件已经建好，也已经被 prof.pager 记下；但抖音那 4 次 (0) 有一部分在触发之后。
 *  - 冷启动那 4 次 (0) 的来源和时间：19351（DmtTabLayout.onTabSelected，在触发之前）、19827（X.18rI.run，触发后 +224ms）、19836（PandaProfileMyPager#LJJLJLI，+233ms）、
 *    约 20155（同 LJJLJLI，+552ms）。所以触发后 +552ms 以后切才不会被盖回去；延时取 700ms，只比最后一次多 148ms。
 *  - 第二次进「我」（任务 4）：onChanged(USER) 在 56995ms，抖音自己的 setCurrentItem(0) 在 57463ms，晚 468ms。
 *  - ViewPager#setCurrentItem(int) / (int, boolean) 是 androidx 声明的（没混淆），ProfileViewPager 不覆写；类名 com.ss.android.ugc.aweme.profile.widgets.ProfileViewPager 没混淆。
 * 没有定时器、没有轮询、没有扫描；prof.enter 回调只在底部切页时才有（用户点一次一次），不是点击检测。
 *
 * 局限（没编译、没上机）：
 *  - 热启动（第二次起）抖音恢复标签的 setCurrentItem 在触发后约 470ms 才来，立刻切不会被盖掉（上机确认过：第二次起生效）——推测它读的是「记住的标签」，我们切页后这个记录跟着变了。
 *  - 冷启动 700ms 只比最后一次 (0)（+552ms）多 148ms：机器忙、抖音那几次恢复往后拖时，第一次进「我」仍可能被盖回「作品」；只有一份冷启动追踪，没有第二份。
 *  - 冷启动第一次触发后 700ms 内，你如果手点了页内标签，700ms 到了还是会被切到「喜欢」（消息不看你点了什么）。
 *  - 参数是 Pair 这一点来自 toString 的格式，没有看到类名；回调里用 toString().startsWith("(USER,") 判断，格式变了就不触发（安静失效）。
 *    这个 toString 每次底部切页只做一次，不是热路径。
 *  - 别人的主页如果也用 ProfileViewPager 则没有影响：只有点底部「我」才触发；「喜欢」是最后一页只有一个账号的证据。
 * 没挂上时个人主页保持原样，安静失效。
 */
final class ProfHook {
    private ProfHook() {}

    private static final String PAGER = "com.ss.android.ugc.aweme.profile.widgets.ProfileViewPager";
    private static final String VP = "androidx.viewpager.widget.ViewPager";
    private static final String ENTER = "com.ss.android.ugc.aweme.main.MainPageFragment$2";
    /** Pair.toString() 里第一项是 USER 的开头。 */
    private static final String USER_PREFIX = "(USER,";
    /** 越界页号：ViewPager 收到最后一页（= 喜欢）。 */
    private static final Integer LAST = 99;

    private static final XC_MethodHook PAGER_HOOK = new PagerHook();
    private static final XC_MethodHook ENTER_HOOK = new EnterHook();

    /** 冷启动特例：进程里第一次触发延迟这么久再切。 */
    private static final long COLD_DELAY_MS = 700;

    private static volatile WeakReference<Object> pagerRef;
    /** 还没发过冷启动的那条延时消息（只在主线程回调里读写）。 */
    private static boolean cold = true;
    private static final Runnable COLD_SWITCH = new Runnable() {
        @Override
        public void run() {
            switchToLast();
        }
    };
    private static volatile Method setItem;

    /** 启动时和每次回到前台都会被调用；功能没开就不挂（没有结论，开了之后回到前台再挂）。 */
    static void tryHook(ClassLoader cl) {
        if (cl == null || !DanmakuHook.cfg.profFav) return;
        tryPager(cl);
        tryEnter(cl);
    }

    private static synchronized void tryPager(ClassLoader cl) {
        if (HookGuard.settled(HookGuard.PROF_PAGER)) return;
        // 只找 androidx 的 ViewPager（启动就在）。ProfileViewPager 要等第一次进个人主页才加载，类名在回调里按字符串比，不在这里 Class.forName。
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
            setItem = b;
            XposedBridge.hookMethod(a, PAGER_HOOK);
            XposedBridge.hookMethod(b, PAGER_HOOK);
        } catch (Throwable t) {
            setItem = null;
            HookGuard.miss(HookGuard.PROF_PAGER, "ViewPager#setCurrentItem 挂不上：" + t);
            return;
        }
        HookGuard.ok(HookGuard.PROF_PAGER, "");
    }

    private static void tryEnter(ClassLoader cl) {
        HookGuard.hook(HookGuard.PROF_ENTER, ENTER, HookGuard.named("onChanged"), ENTER_HOOK,
                "MainPageFragment$2 里没有 onChanged（抖音改了底部标签切换的观察者，需要重新用 TextRec 追踪点底部「我」）", cl);
    }

    /** 只记住个人主页的分页控件，不改任何参数。 */
    private static final class PagerHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            try {
                if (!DanmakuHook.cfg.profFav) return;
                Object o = p.thisObject;
                WeakReference<Object> r = pagerRef;
                if (r != null && r.get() == o) return;          // 已经记着了：一次引用比较就返回
                if (!PAGER.equals(o.getClass().getName())) return; // 别的 ViewPager
                pagerRef = new WeakReference<Object>(o);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 点底部「我」：切到最后一页。 */
    private static final class EnterHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam p) {
            try {
                if (!DanmakuHook.cfg.profFav) return;
                Object a = p.args[0];
                if (a == null || !a.toString().startsWith(USER_PREFIX)) return; // 不是切到「我」
                if (cold) {                                                     // 特例：进程里第一次，发一条延时消息，之后不再发
                    cold = false;
                    new Handler(Looper.getMainLooper()).postDelayed(COLD_SWITCH, COLD_DELAY_MS);
                    return;
                }
                switchToLast();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 对记下的分页控件切到最后一页。 */
    private static void switchToLast() {
        try {
            if (!DanmakuHook.cfg.profFav) return;
            WeakReference<Object> r = pagerRef;
            Object pager = r != null ? r.get() : null;
            Method m = setItem;
            if (pager == null || m == null) return; // 还没见过分页控件：什么都不做
            m.invoke(pager, LAST, Boolean.FALSE);   // 越界 → 最后一页（喜欢）；不演滑动动画
        } catch (Throwable ignored) {
        }
    }
}
