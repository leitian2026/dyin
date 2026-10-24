package com.danmaku.randcolor;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 个人主页默认停在「喜欢」（1.35.2：触发 + 一次主动切页 + 冷启动那 3 次恢复用「计数」改掉；没有定时器、没有延时消息）。
 *
 * 三件事：
 *  1. 触发 prof.enter = com.ss.android.ugc.aweme.main.MainPageFragment$2#onChanged(Object)：底部标签切换的观察者，参数是一个 Pair，toString 形如
 *     "(USER, TabRouteConfig(enterFrom='change_selected_bottom_tab', ...))"；第一项是 USER = 切到底部「我」，HOME = 切到首页。
 *  2. 动作：触发时对个人主页的分页控件调一次 setCurrentItem(99, false)；99 越界，ViewPager 自己收成最后一页（= 喜欢），不认标签、不扫视图树、不依赖标签个数。
 *     分页控件从哪来：prof.pager 挂 ViewPager#setCurrentItem 两个重载，认出 this 是 ProfileViewPager 就记一个弱引用（整个进程只建一次，只在换对象时才分配）。
 *     触发时没有记下的对象 = 什么都不做。
 *  3. 冷启动计数：进程里**第一次**触发（= 冷启动后第一次点「我」）时把 remaining 设成 COLD_RESTORES（3）；之后 ProfileViewPager 每收到一次 setCurrentItem(0)
 *     （抖音自己恢复标签），参数改成 99 并 remaining 减一，减到 0 停。切到首页（HOME）时 remaining 清零。第二次起的触发不再计数（热启动只有 1 次恢复，读的是「记住的标签」，不会盖掉）。
 *     没有定时器、不看时间，只数次数，和手机快慢无关。
 *
 * 依据（TextRec 40.9.0，textrec-tasks-20261024-141908，加上之前的 20261023-231633、20261024-071912）：
 *  - MainPageFragment$2#onChanged：类名、方法名没混淆；签名 public final (Object) → void，同名重载只有 1 个；实现的是 androidx.lifecycle.Observer#onChanged，
 *    调用栈 NextLiveData$NextObserver.onChanged ← LiveData.considerNotify ← dispatchingValue ← setValue（主线程），启动后 28ms 就能找到这个类。
 *    点「我」两次各调一次 (USER, …)，点「首页」调 (HOME, …)。
 *  - 冷启动第一次进「我」，ProfileViewPager 上的 setCurrentItem(0) 共 4 次：19351ms（DmtTabLayout.onTabSelected，在触发之前，不算）、
 *    19827ms（X.18rI.run，触发后 +224ms）、19836ms（PandaProfileMyPager#LJJLJLI，+233ms）、约 20155ms（同 LJJLJLI，+552ms）；onChanged(USER) 在 19603ms。
 *    触发之后的 3 次就是 COLD_RESTORES = 3 的来源。setAdapter(ProfileViewPager) 在 19329ms，所以触发时分页控件已经建好、已被 prof.pager 记下。
 *  - 第二次进「我」（任务 4）：onChanged(USER) 在 56995ms，抖音自己的 setCurrentItem(0) 只有 1 次，在 57463ms（+468ms）；上机确认第二次起生效。
 *  - ViewPager#setCurrentItem(int) / (int, boolean) 是 androidx 声明的（没混淆），ProfileViewPager 不覆写；类名 com.ss.android.ugc.aweme.profile.widgets.ProfileViewPager 没混淆。
 * 没有定时器、没有轮询、没有扫描；prof.enter 回调只在底部切页时才有（用户点一次一次），不是点击检测；prof.pager 回调里只比较引用 / 一个整数，计数期间才改参数。
 *
 * 局限（没编译、没上机）：
 *  - COLD_RESTORES = 3 只来自这一份冷启动追踪（一台手机、一个抖音版本 40.9.0）。抖音更新后次数变了：
 *      变多 → 多出来的 (0) 没被改，冷启动第一次又被盖回「作品」（症状同 1.35.0）；
 *      变少 → 剩下的次数会被你之后手点「作品」吃掉，那一下点了没反应（仍停在「喜欢」），再点一次才行；切到首页清零，所以最多影响当次进「我」。
 *    抖音更新后如果冷启动第一次又不生效，用 TextRec 数冷启动时 ProfileViewPager 上 (0) 的次数，改 COLD_RESTORES。
 *  - 计数只算 setCurrentItem(0)：抖音恢复如果换成别的页号，不会被数到（安静失效）。
 *  - 参数是 Pair 这一点来自 toString 的格式，没有看到类名；回调里用 toString().startsWith("(USER,") / ("(HOME,") 判断，格式变了就不触发（安静失效）。
 *    这个 toString 每次底部切页只做一次，不是热路径。
 *  - 「喜欢」是最后一页只有一个账号的证据。
 * 没挂上时个人主页保持原样，安静失效。
 */
final class ProfHook {
    private ProfHook() {}

    private static final String PAGER = "com.ss.android.ugc.aweme.profile.widgets.ProfileViewPager";
    private static final String VP = "androidx.viewpager.widget.ViewPager";
    private static final String ENTER = "com.ss.android.ugc.aweme.main.MainPageFragment$2";
    /** Pair.toString() 里第一项是 USER 的开头。 */
    private static final String USER_PREFIX = "(USER,";
    /** Pair.toString() 里第一项是 HOME 的开头（切到首页）。 */
    private static final String HOME_PREFIX = "(HOME,";
    /** 越界页号：ViewPager 收到最后一页（= 喜欢）。 */
    private static final Integer LAST = 99;

    private static final XC_MethodHook PAGER_HOOK = new PagerHook();
    private static final XC_MethodHook ENTER_HOOK = new EnterHook();

    /** 冷启动：触发之后抖音自己恢复标签的 setCurrentItem(0) 的次数（TextRec 40.9.0 数出来的，见头部注释）。 */
    private static final int COLD_RESTORES = 3;

    private static volatile WeakReference<Object> pagerRef;
    /** 进程里还没有过第一次触发（只在主线程回调里读写）。 */
    private static boolean cold = true;
    /** 还要改掉几次 setCurrentItem(0)（主线程读写）。 */
    private static int remaining;
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

    /** 记住个人主页的分页控件；冷启动计数期间把抖音自己恢复标签的 (0) 改成 99。 */
    private static final class PagerHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            try {
                if (!DanmakuHook.cfg.profFav) return;
                Object o = p.thisObject;
                WeakReference<Object> r = pagerRef;
                if (r == null || r.get() != o) {                    // 没记着这个对象：才比类名（别的 ViewPager 一次字符串比较就返回）
                    if (!PAGER.equals(o.getClass().getName())) return;
                    pagerRef = new WeakReference<Object>(o);
                }
                if (remaining <= 0) return;                         // 没在计数：什么都不改
                Object a = p.args[0];
                if (!(a instanceof Integer) || ((Integer) a).intValue() != 0) return; // 只数抖音的恢复 (0)；我们自己的 99 放过
                p.args[0] = LAST;                                   // 恢复 (0) → 喜欢
                if (p.args.length > 1) p.args[1] = Boolean.FALSE;   // 不演滑动动画
                remaining--;
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
                if (a == null) return;
                String t = a.toString();
                if (t.startsWith(HOME_PREFIX)) {                                // 切到首页：计数清零
                    remaining = 0;
                    return;
                }
                if (!t.startsWith(USER_PREFIX)) return;                         // 不是切到「我」
                if (cold) {                                                     // 进程里第一次：开始数抖音随后的恢复
                    cold = false;
                    remaining = COLD_RESTORES;
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
