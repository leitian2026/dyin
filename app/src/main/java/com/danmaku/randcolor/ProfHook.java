package com.danmaku.randcolor;

import android.os.SystemClock;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 点底部「我」进个人主页，默认停在「喜欢」标签（1.32.0）。
 *
 * 依据（TextRec 40.9.0，textrec-tasks-20261023-231633；任务 2 点「我」、任务 3/5 点「喜欢」、任务 4 点「作品」、任务 6 切走再回来）：
 *  - 个人主页的分页控件是 com.ss.android.ugc.aweme.profile.widgets.ProfileViewPager（类名没混淆，视图树 id=yq4），
 *    标签（作品 日常 推荐 收藏 喜欢）是它的页：点「喜欢」时调 androidx.viewpager.widget.ViewPager#setCurrentItem(4)，点「作品」时是 (0)，
 *    「喜欢」是最后一页。这个方法在 androidx 的 ViewPager 里声明（int / int,boolean 两个重载，没混淆），ProfileViewPager 自己不声明。
 *  - 进主页时抖音自己调 setCurrentItem(0)（三次：标签选中的协程回调、X.18rI.run 里的 Runnable、PandaProfileMyPager.LJJLJLI 里的 Runnable，
 *    时间是点底部「我」之后 0.2～0.8 秒；都是混淆名，所以不挂它们，只挂 ViewPager#setCurrentItem 再按 this 的类过滤）。
 *    切走再回来（任务 6）抖音自己调的是 setCurrentItem(4)（记住了上次选的标签），不受这个钩子影响（只改参数 0）。
 *  - 底部「我」是 com.ss.android.ugc.aweme.homepage.tab.ui.bottom.BottomTab（类名没混淆，一共 5 个 BottomTab），里面有一个文字是「我」的
 *    TextView（AntiBurnDuxTextView）；点它时 View#performClick 在 BottomTab 上被调用（任务 2、6）。
 *  - 页内标签 DmtTabLayout$TabView 的 performClick（任务 3、4、5）用来认出「用户自己点了页内标签」。
 *
 * 做法：
 *  1) pub 之外另两个钩子：prof.tap 挂 View#performClick（框架方法，先确认 BottomTab 和 DmtTabLayout$TabView 两个类都在才挂），
 *     点到「我」的 BottomTab 就记下时刻 armedAt（点到别的 BottomTab 清掉）；点到页内标签 TabView 就记下时刻 userAt。
 *  2) prof.pager 挂 ViewPager#setCurrentItem（两个重载，先确认 ProfileViewPager 在、是它的子类），回调里：
 *     this 是 ProfileViewPager、参数是 0、距点「我」不到 WINDOW_MS、且距用户点页内标签超过 USER_MS，就把参数改成 LAST（99）。
 *     ViewPager 内部会把越界的页号收到最后一页（= 喜欢），所以不用知道有几个标签；适配器还没设好时它自己直接返回，后面的 setCurrentItem(0) 还会再被改一次。
 *     第二个重载的 smoothScroll 改成 false，不演动画。
 *  没有定时器、没有轮询：窗口只靠比较 SystemClock.uptimeMillis()。回调里不分配对象、不反射。
 *
 * 局限（没上机、没编译）：
 *  - 「喜欢」是最后一页只有这一个账号的证据；账号没有「喜欢」页或它不是最后一页时，会落在最后一页。
 *  - 点「我」时人已经在个人主页（再点一次「我」），如果抖音又调了 setCurrentItem(0)，会被改到「喜欢」；追踪里没有这种情况。
 *  - 窗口 3 秒内抖音如果在用户没点页内标签的情况下又调 setCurrentItem(0)（比如刷新），也会被改；追踪里 3 秒内只有进主页那几次。
 * 没挂上时个人主页保持原样，安静失效。
 */
final class ProfHook {
    private ProfHook() {}

    private static final String PAGER = "com.ss.android.ugc.aweme.profile.widgets.ProfileViewPager";
    private static final String VP = "androidx.viewpager.widget.ViewPager";
    private static final String BOTTOM = "com.ss.android.ugc.aweme.homepage.tab.ui.bottom.BottomTab";
    private static final String TABVIEW = "com.bytedance.ies.dmt.ui.widget.tablayout.DmtTabLayout$TabView";

    /** 点「我」之后多久内的 setCurrentItem(0) 算「进主页的默认页」。 */
    private static final long WINDOW_MS = 3000L;
    /** 用户点页内标签之后多久内的 setCurrentItem(0) 算用户自己选的。 */
    private static final long USER_MS = 1200L;
    /** 越界页号：ViewPager 收到最后一页（= 喜欢）。 */
    private static final Integer LAST = 99;
    /** 点「我」那一页底部标签里的文字。 */
    private static final String ME = "我";

    private static final XC_MethodHook PAGER_HOOK = new PagerHook();
    private static final XC_MethodHook TAP_HOOK = new TapHook();

    private static volatile Class<?> pagerCls, bottomCls, tabViewCls;
    private static volatile long armedAt, userAt;

    /** 启动时和每次回到前台都会被调用；功能没开就不挂（没有结论，开了之后回到前台再挂）。 */
    static void tryHook(ClassLoader cl) {
        if (cl == null || !DanmakuHook.cfg.profFav) return;
        tryPager(cl);
        tryTap(cl);
    }

    private static synchronized void tryPager(ClassLoader cl) {
        if (HookGuard.settled(HookGuard.PROF_PAGER)) return;
        Class<?> pc = HookGuard.find(HookGuard.PROF_PAGER, PAGER, cl);
        if (pc == null) return;
        Class<?> vp = HookGuard.find(HookGuard.PROF_PAGER, VP, cl);
        if (vp == null) return;
        if (!vp.isAssignableFrom(pc)) {
            HookGuard.miss(HookGuard.PROF_PAGER, "ProfileViewPager 不再是 androidx ViewPager 的子类（抖音换了分页控件，需要重新用 TextRec 追踪 setCurrentItem）");
            return;
        }
        int n = 0;
        try {
            Method a = vp.getDeclaredMethod("setCurrentItem", int.class);
            Method b = vp.getDeclaredMethod("setCurrentItem", int.class, boolean.class);
            pagerCls = pc;
            XposedBridge.hookMethod(a, PAGER_HOOK);
            n++;
            XposedBridge.hookMethod(b, PAGER_HOOK);
            n++;
        } catch (NoSuchMethodException e) {
            pagerCls = null;
            if (n == 0) {
                HookGuard.miss(HookGuard.PROF_PAGER, "ViewPager 里没有 setCurrentItem(int) / (int, boolean)（需要重新用 TextRec 追踪个人主页切标签）");
                return;
            }
        } catch (Throwable t) {
            pagerCls = null;
            HookGuard.miss(HookGuard.PROF_PAGER, "ViewPager#setCurrentItem 挂不上：" + t);
            return;
        }
        HookGuard.ok(HookGuard.PROF_PAGER, n == 2 ? "" : "只挂上一个重载");
    }

    private static synchronized void tryTap(ClassLoader cl) {
        if (HookGuard.settled(HookGuard.PROF_TAP)) return;
        Class<?> b = HookGuard.find(HookGuard.PROF_TAP, BOTTOM, cl);
        if (b == null) return;
        Class<?> t = HookGuard.find(HookGuard.PROF_TAP, TABVIEW, cl);
        if (t == null) return;
        try {
            bottomCls = b;
            tabViewCls = t;
            XposedBridge.hookMethod(View.class.getDeclaredMethod("performClick"), TAP_HOOK);
        } catch (Throwable e) {
            bottomCls = null;
            tabViewCls = null;
            HookGuard.miss(HookGuard.PROF_TAP, "View.performClick 挂不上：" + e);
            return;
        }
        HookGuard.ok(HookGuard.PROF_TAP, "");
    }

    /** v 下面（最多 8 层）有没有文字正好是「我」的 TextView。点击时才调，不分配对象。 */
    private static boolean hasMe(View v, int depth) {
        if (v instanceof TextView && TextUtils.equals(((TextView) v).getText(), ME)) return true;
        if (depth >= 8 || !(v instanceof ViewGroup)) return false;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0, n = g.getChildCount(); i < n; i++) {
            if (hasMe(g.getChildAt(i), depth + 1)) return true;
        }
        return false;
    }

    private static final class TapHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            try {
                if (!DanmakuHook.cfg.profFav) return;
                Object o = p.thisObject;
                Class<?> tv = tabViewCls, b = bottomCls;
                if (tv == null || b == null) return;
                if (tv.isInstance(o)) {
                    userAt = SystemClock.uptimeMillis(); // 用户自己点了页内标签（作品 / 喜欢……）
                } else if (b.isInstance(o)) {
                    armedAt = hasMe((View) o, 0) ? SystemClock.uptimeMillis() : 0L; // 点「我」：开窗口；点别的底部标签：关窗口
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static final class PagerHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            try {
                long t = armedAt;
                if (t == 0L || !DanmakuHook.cfg.profFav) return;
                Class<?> k = pagerCls;
                if (k == null || !k.isInstance(p.thisObject)) return;
                Object a = p.args[0];
                if (!(a instanceof Integer) || ((Integer) a).intValue() != 0) return;
                long now = SystemClock.uptimeMillis();
                if (now - t > WINDOW_MS || now - userAt < USER_MS) return;
                p.args[0] = LAST;
                if (p.args.length > 1) p.args[1] = Boolean.FALSE; // 不演滑动动画
            } catch (Throwable ignored) {
            }
        }
    }
}
