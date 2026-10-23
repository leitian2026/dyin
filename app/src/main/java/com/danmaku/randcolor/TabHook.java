package com.danmaku.randcolor;

import android.view.View;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 首页顶部标签行（热点 / 经验 / 游戏 / 少儿 / 同城 / 精选 / 团购 / 免费看剧 / 商城 / 短剧 / 直播 / 关注 / 推荐）：
 * 设置页里勾选哪个，就把哪个标签隐藏（setVisibility(GONE)）。
 *
 * 依据（TextRec 追踪，40.7.0，textrec-tasks-20261019-202639）：
 *  - 标签行是 MainTabStripNew > MainTabStripScrollView > MainTabRelativeLayout，里面每个标签是一个
 *    com.ss.android.ugc.aweme.homepage.tab.top.ui.tabstrip.item.HomeTopTabItemViewNew（类名没混淆）。
 *  - 每个标签都会被调用 View.setContentDescription，参数形如"热点，按钮"、"推荐，已选中，按钮"
 *    （13 个标签同一毫秒一起设，页面切换、刷新时会重设）。标签文字 = 第一个"，"之前的部分。
 *  - 追踪里没有这个 setContentDescription 的设置栈，所以没有更靠近业务的稳定钩子；这里钩框架方法
 *    View.setContentDescription，并用「调用者是 HomeTopTabItemViewNew」过滤。
 *
 * 为什么不会像 1.11 以前那样耗电：旧的 TabHook 钩的是全局的 TextView.setText / View.setVisibility（极高频），
 * 还会定时扫 View 树。这里钩的 setContentDescription 调用很少，回调里先看有没有勾选（没勾选直接返回），
 * 再比较一次类，只有标签行的 View 才会去匹配文字；没有定时器、没有扫描、不分配对象。
 * 没找到 HomeTopTabItemViewNew 这个类时，根本不挂这个全局钩子。
 *
 * 局限：
 *  - 取消勾选后要重启抖音才会恢复（模块没有记录哪些标签是它隐藏的，不会把抖音自己隐藏的标签误显示出来）。
 *  - 只隐藏标签，不改翻页：左右划动时仍然会翻到被隐藏标签对应的页面。
 *  - 隐藏后标签行怎么重排取决于抖音的布局（MainTabRelativeLayout 是否忽略 GONE 的子 View），没有真机验证过。
 *  - 抖音如果在我们设完之后又把这个标签设回可见，就会再显示出来，直到下一次 setContentDescription。
 * 没挂上时标签行保持原样，安静失效。
 */
final class TabHook {
    private TabHook() {}

    private static final String ITEM = "com.ss.android.ugc.aweme.homepage.tab.top.ui.tabstrip.item.HomeTopTabItemViewNew";
    private static final XC_MethodHook HOOK = new Hook();

    private static volatile Class<?> itemCls;

    /** 启动时和每次回到前台都会被调用；HookGuard 保证已有结论后立刻返回。 */
    static void tryHook(ClassLoader cl) {
        if (cl == null || HookGuard.settled(HookGuard.TAB)) return;
        if (DanmakuHook.cfg.hiddenTabs.length == 0) return; // 没勾选任何标签：不挂全局的 View.setContentDescription（没有结论，勾选后回到前台再挂）
        Class<?> c = HookGuard.find(HookGuard.TAB, ITEM, cl); // 类没找到：记一次等待，下次回到前台再试
        if (c == null) return;
        try {
            itemCls = c;
            Method m = View.class.getDeclaredMethod("setContentDescription", CharSequence.class);
            XposedBridge.hookMethod(m, HOOK);
        } catch (Throwable t) {
            HookGuard.miss(HookGuard.TAB, "View.setContentDescription 挂不上：" + t);
            return;
        }
        HookGuard.ok(HookGuard.TAB, "");
    }

    private static final class Hook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam p) {
            try {
                String[] hide = DanmakuHook.cfg.hiddenTabs;
                if (hide.length == 0) return; // 没勾选：什么都不做
                Object o = p.thisObject;
                Class<?> k = itemCls;
                if (k == null || !k.isInstance(o) || p.args[0] == null) return;
                String s = p.args[0].toString(); // 字符串本身，不分配
                int n = s.length();
                for (String label : hide) {
                    int l = label.length();
                    if (l == 0 || !s.startsWith(label) || (n > l && s.charAt(l) != '，')) continue;
                    View v = (View) o;
                    if (v.getVisibility() != View.GONE) v.setVisibility(View.GONE);
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
    }
}
