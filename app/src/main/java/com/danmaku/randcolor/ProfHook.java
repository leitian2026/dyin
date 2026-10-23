package com.danmaku.randcolor;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 个人主页默认停在「喜欢」标签（1.33.0 重写：只钩「切页」这一个动作，没有任何点击钩子、没有时间窗口、没有记时刻）。
 *
 * 规则：个人主页的分页控件（ProfileViewPager）被要求「切到它现在就在的这一页」（setCurrentItem(x)，x == getCurrentItem()）时，改成切到最后一页（= 喜欢）。
 * 这种「重申当前页」的调用只有抖音自己在进主页时恢复标签才会发（用户点标签是从一页切到另一页，x != 当前页；点已选中的标签走 onTabReselected，不调 setCurrentItem）。
 * 所以不用知道用户有没有点「我」、有没有点页内标签：进主页就是进主页，点别的标签就放过。
 *
 * 依据（TextRec 40.9.0，textrec-tasks-20261023-231633、textrec-tasks-20261024-071912）：
 *  - 分页控件 com.ss.android.ugc.aweme.profile.widgets.ProfileViewPager，分页控件和适配器整个进程只建一次（对象身份相同，setAdapter 只出现一次），所以它自己记着当前页（mCurItem）；
 *  - 每次进主页抖音调 setCurrentItem(记住的标签)（选作品后再进是 (0)，选喜欢后再进是 (4)），这个值就是它记着的当前页，调用等于「重申当前页」；点「喜欢」→ (4)、点「作品」→ (0) 是真的换页；「喜欢」是最后一页。
 *  - setCurrentItem(int) / (int, boolean) 是 androidx ViewPager 里声明的（没混淆），ProfileViewPager 不覆写；类名 ProfileViewPager 没混淆。
 * 钩子：prof.pager = ViewPager#setCurrentItem 两个重载。回调里：先比 this 的类名字符串（getName 返回缓存字符串）不是 ProfileViewPager 就返回；
 *   是才读一次 getCurrentItem（回调只在切页时才有，不是点击；getCurrentItem 的 Method 在安装时取好）。越界页号 99 由 ViewPager 自己收到最后一页；适配器还没设好它直接返回。
 * 没有定时器、没有轮询、没有点击钩子。
 *
 * 局限（没上机、没编译）：
 *  - 第一次进主页（冷启动后）抖音连调 4 次 (0)：第一次被改成「喜欢」后，后面几次如果还是 (0)，就是真的换页，会被放过并切回「作品」；追踪里这 4 次的先后和参数没拍清，不知道会不会发生。
 *  - 别人的主页如果也用 ProfileViewPager，抖音在那里重申当前页时也会被改；没抓过别人的主页。
 *  - 「喜欢」是最后一页，只有一个账号的证据。
 * 没挂上时个人主页保持原样，安静失效。
 */
final class ProfHook {
    private ProfHook() {}

    private static final String PAGER = "com.ss.android.ugc.aweme.profile.widgets.ProfileViewPager";
    private static final String VP = "androidx.viewpager.widget.ViewPager";
    /** 越界页号：ViewPager 收到最后一页（= 喜欢）。 */
    private static final Integer LAST = 99;

    private static final XC_MethodHook PAGER_HOOK = new PagerHook();

    private static volatile Method getCur;

    /** 启动时和每次回到前台都会被调用；功能没开就不挂（没有结论，开了之后回到前台再挂）。 */
    static void tryHook(ClassLoader cl) {
        if (cl == null || !DanmakuHook.cfg.profFav) return;
        tryPager(cl);
    }

    private static synchronized void tryPager(ClassLoader cl) {
        if (HookGuard.settled(HookGuard.PROF_PAGER)) return;
        // 只找 androidx 的 ViewPager（启动就在）。ProfileViewPager 要等第一次进个人主页才加载，类名在回调里按字符串比，不在这里 Class.forName。
        Class<?> vp = HookGuard.find(HookGuard.PROF_PAGER, VP, cl);
        if (vp == null) return;
        Method a, b, g;
        try {
            a = vp.getDeclaredMethod("setCurrentItem", int.class);
            b = vp.getDeclaredMethod("setCurrentItem", int.class, boolean.class);
            g = vp.getDeclaredMethod("getCurrentItem");
        } catch (NoSuchMethodException e) {
            HookGuard.miss(HookGuard.PROF_PAGER, "ViewPager 里没有 setCurrentItem(int) / (int, boolean) / getCurrentItem（需要重新用 TextRec 追踪个人主页切标签）");
            return;
        }
        try {
            getCur = g;
            XposedBridge.hookMethod(a, PAGER_HOOK);
            XposedBridge.hookMethod(b, PAGER_HOOK);
        } catch (Throwable t) {
            getCur = null;
            HookGuard.miss(HookGuard.PROF_PAGER, "ViewPager#setCurrentItem 挂不上：" + t);
            return;
        }
        HookGuard.ok(HookGuard.PROF_PAGER, "");
    }

    private static final class PagerHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            try {
                if (!DanmakuHook.cfg.profFav) return;
                Object o = p.thisObject;
                if (!PAGER.equals(o.getClass().getName())) return; // 别的 ViewPager：一次字符串比较就返回
                Method g = getCur;
                Object a = p.args[0];
                if (g == null || !(a instanceof Integer)) return;
                if (((Integer) a).intValue() != ((Integer) g.invoke(o)).intValue()) return; // 真的换页（用户点了别的标签）：放过
                p.args[0] = LAST;                                                           // 重申当前页 = 抖音恢复标签：改成喜欢
                if (p.args.length > 1) p.args[1] = Boolean.FALSE;                           // 不演滑动动画
            } catch (Throwable ignored) {
            }
        }
    }
}
