package com.danmaku.randcolor;

import android.app.Activity;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.WeakHashMap;
import java.util.regex.Pattern;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 视频时长常显（形如 00:12 / 03:45，沿用抖音自己拖动进度条时显示的那段文字）。
 *
 * 依据 TextRec 的记录（40.7.0）：ProTimeDescViewComponent 里有两个观察者
 *   $onCreate$1#onChanged：播放时每帧调用，更新时间文字；
 *   $onCreate$2#onChanged(Boolean)：只在拖动开始(true) / 松手(false) 时调用，是显示开关。
 *
 * 做法（都不需要轮询，全部由抖音自己的回调驱动）：
 *  1) $onCreate$2：把参数一律改成 true —— 松手时的 false 变成 true，时长就不会再被收起。
 *  2) $onCreate$1：借它每帧的回调，找到这个组件持有的时间 View（只在刚出现时找，找到后每 16 次才复查一次），
 *     把它强制设为可见，并按设置里的偏移量做 translation（相对抖音原位置，单位 dp）。
 *
 * 关闭开关后：下一次回调里把偏移和可见性还原。
 */
final class TimeHook {
    private TimeHook() {}

    /** 版本相关（40.7.0）：时间组件的两个观察者。换版本后需要重新记录。 */
    private static final String COMP = "com.ss.android.ugc.aweme.o.seekbar.core.components.timedesc.ProTimeDescViewComponent";
    private static final String OBS_FRAME = COMP + "$onCreate$1";
    private static final String OBS_SHOW = COMP + "$onCreate$2";

    private static final Pattern TIME = Pattern.compile("\\d{1,3}:\\d{2}");

    private static volatile boolean frameHooked, showHooked, anyApplied, loggedOk;
    private static int misses;

    // 诊断浮层用：最近一次找到的时间 View、状态说明
    private static volatile String lastNote = "还没收到时间回调（没播放视频？）";
    private static WeakReference<View> lastTv, lastTop;
    private static WeakReference<TextView> dbgView;
    private static int dbgTick;

    /** 每个时间组件实例一份状态（每个视频页一个组件），弱引用，页面回收后自动消失。 */
    private static final WeakHashMap<Object, State> STATES = new WeakHashMap<>();

    private static final class State {
        WeakReference<View> tv, top;
        DanmakuConfig cfgRef;
        int tick, attempts;
        int origTv = -1, origTop = -1;
        boolean failed, applied, unclipped, dbgBg;
        Drawable origBg;
    }

    // ------------------------------------------------------------ 安装

    /** 目标 App 的类加载器；启动时试一次，之后每次回到前台再试，直到两个观察者都挂上。 */
    static void tryHook(ClassLoader cl) {
        if (cl == null || (frameHooked && showHooked)) return;
        attempt(cl);
        if (!(frameHooked && showHooked)) {
            ClassLoader ctx = Thread.currentThread().getContextClassLoader();
            if (ctx != null && ctx != cl) attempt(ctx);
        }
        if (!(frameHooked && showHooked) && ++misses == 6) {
            XposedBridge.log("[DanmakuColor] time hook: classes not found yet (frame=" + frameHooked + ", show=" + showHooked + ")");
        }
    }

    private static void attempt(ClassLoader cl) {
        if (!showHooked) showHooked = hookObserver(cl, OBS_SHOW, new ShowHook());
        if (!frameHooked) frameHooked = hookObserver(cl, OBS_FRAME, new FrameHook());
    }

    private static boolean hookObserver(ClassLoader cl, String name, XC_MethodHook cb) {
        try {
            Class<?> k = Class.forName(name, false, cl);
            int n = 0;
            for (Method m : k.getDeclaredMethods()) {
                if (!m.getName().equals("onChanged")) continue; // 桥接方法和具体方法都挂
                XposedBridge.hookMethod(m, cb);
                n++;
            }
            return n > 0;
        } catch (ClassNotFoundException e) {
            return false; // 还没加载：下次回到前台再试
        } catch (Throwable t) {
            XposedBridge.log("[DanmakuColor] time hook failed: " + name + " " + t);
            return true; // 别反复重试
        }
    }

    // ------------------------------------------------------------ 显示开关：一律 true

    private static final class ShowHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            try {
                if (!DanmakuHook.cfg.timeAlways) return;
                Object[] a = p.args;
                if (a != null && a.length == 1 && a[0] instanceof Boolean) a[0] = Boolean.TRUE;
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------ 每帧回调：找到时间 View，保持可见 + 偏移

    private static final class FrameHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam p) {
            try {
                DanmakuConfig c = DanmakuHook.cfg;
                if (c.timeDebug && (++dbgTick & 15) == 1) debugShow(CommentHook.top.get());
                if (!c.timeAlways) {
                    lastNote = "开关没开（设置页「视频时长一直显示」）";
                    if (anyApplied) restoreAll();
                    return;
                }
                if (Looper.myLooper() != Looper.getMainLooper()) return;
                Object self = p.thisObject;
                if (self == null) return;
                Object key = outerOf(self);
                if (key == null) key = self;

                State s;
                synchronized (STATES) {
                    s = STATES.get(key);
                    if (s == null) {
                        s = new State();
                        STATES.put(key, s);
                    }
                }
                if (s.failed) return;
                s.tick++;
                if (s.tv == null) lastNote = "收到回调，正在找时间 View（第 " + s.attempts + " 次）";

                View tv = s.tv == null ? null : s.tv.get();
                View top = s.top == null ? null : s.top.get();
                if (tv != null && top != null) {
                    // 已找到：配置没变就每 16 次回调才复查一次可见性
                    if (s.cfgRef == c && (s.tick & 15) != 0) return;
                    lastTv = s.tv;
                    lastTop = s.top;
                    lastNote = "已找到并套用";
                    apply(s, tv, top, c);
                    s.cfgRef = c;
                    return;
                }

                // 还没找到：前几次回调每次都试，之后每 16 次试一次，最多 8 次
                if (!(s.tick <= 6 || (s.tick & 15) == 0)) return;
                s.attempts++;
                View[] f = locate(self, s.tick);
                if (f == null) {
                    lastNote = "没找到时间 View（已试 " + s.attempts + " 次）";
                    if (s.attempts >= 8) {
                        s.failed = true;
                        lastNote = "放弃：组件里没找到时间 View";
                        XposedBridge.log("[DanmakuColor] time view not found, observer=" + self.getClass().getName());
                    }
                    return;
                }
                s.tv = new WeakReference<>(f[0]);
                s.top = new WeakReference<>(f[1]);
                lastTv = s.tv;
                lastTop = s.top;
                lastNote = "已找到并套用";
                if (!loggedOk) {
                    loggedOk = true;
                    CharSequence t = f[0] instanceof TextView ? ((TextView) f[0]).getText() : null;
                    XposedBridge.log("[DanmakuColor] time view ok: " + f[0].getClass().getName()
                            + " text=\"" + t + "\" top=" + f[1].getClass().getName()
                            + " | " + describe(f[0]) + " | top: " + describe(f[1]));
                }
                apply(s, f[0], f[1], c);
                s.cfgRef = c;
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------ 应用 / 还原

    private static void apply(State s, View tv, View top, DanmakuConfig c) {
        float d = tv.getResources().getDisplayMetrics().density;
        float tx = c.timeDx * d, ty = c.timeDy * d;
        if (s.origTv < 0) s.origTv = tv.getVisibility();
        if (s.origTop < 0) s.origTop = top.getVisibility();

        // 从文字一路到 top 都要可见（只动这一段，不碰上面更高层的容器：清屏 / 评论面板打开时它们本来就该隐藏）
        View v = tv;
        while (true) {
            if (v.getVisibility() != View.VISIBLE) v.setVisibility(View.VISIBLE);
            if (v.getAlpha() < 1f) v.setAlpha(1f);
            if (v == top) break;
            ViewParent pp = v.getParent();
            if (!(pp instanceof View)) break;
            v = (View) pp;
        }
        if (top.getTranslationX() != tx) top.setTranslationX(tx);
        if (top.getTranslationY() != ty) top.setTranslationY(ty);
        if (!s.unclipped && (c.timeDx != 0 || c.timeDy != 0)) {
            s.unclipped = true;
            unclip(top);
        }
        // 诊断：给它涂半透明红底，文字是空的 / 透明时也能看见这块区域
        if (c.timeDebug && !s.dbgBg) {
            s.dbgBg = true;
            s.origBg = top.getBackground();
            top.setBackgroundColor(0x66FF0000);
        } else if (!c.timeDebug && s.dbgBg) {
            s.dbgBg = false;
            top.setBackground(s.origBg);
            s.origBg = null;
        }
        s.applied = true;
        anyApplied = true;
    }

    /** 关掉开关后还原：偏移归零，可见性回到我们第一次碰它之前的状态。 */
    private static void restoreAll() {
        anyApplied = false;
        synchronized (STATES) {
            for (State s : STATES.values()) {
                if (!s.applied) continue;
                s.applied = false;
                s.cfgRef = null;
                View tv = s.tv == null ? null : s.tv.get();
                View top = s.top == null ? null : s.top.get();
                if (top != null) {
                    if (s.dbgBg) {
                        s.dbgBg = false;
                        top.setBackground(s.origBg);
                        s.origBg = null;
                    }
                    top.setTranslationX(0f);
                    top.setTranslationY(0f);
                    if (s.origTop > 0) top.setVisibility(s.origTop);
                }
                if (tv != null && tv != top && s.origTv > 0) tv.setVisibility(s.origTv);
            }
        }
    }

    /** 偏移后的 View 可能超出父容器：把比较小的祖先的裁剪关掉（碰到铺满屏幕的祖先就停，它本来就不会裁掉屏幕内的内容）。 */
    private static void unclip(View top) {
        View root = top.getRootView();
        int w = root.getWidth(), h = root.getHeight();
        ViewParent pp = top.getParent();
        for (int i = 0; i < 8 && pp instanceof ViewGroup; i++) {
            ViewGroup g = (ViewGroup) pp;
            if (w > 0 && g.getWidth() >= w * 0.9f && g.getHeight() >= h * 0.8f) break;
            g.setClipChildren(false);
            g.setClipToPadding(false);
            pp = g.getParent();
        }
    }

    // ------------------------------------------------------------ 诊断浮层

    /** 回到前台时刷新（也负责在关掉诊断后把浮层拿掉）。 */
    static void onResume(Activity a) {
        debugShow(a);
    }

    /** 在当前 Activity 的最上层放一小块文字：它是独立的 TextView，不依赖抖音的时间 View 显不显示。 */
    static void debugShow(Activity a) {
        try {
            if (Looper.myLooper() != Looper.getMainLooper()) return;
            TextView t = dbgView == null ? null : dbgView.get();
            if (!DanmakuHook.cfg.timeDebug || a == null) {
                if (t != null && t.getParent() instanceof ViewGroup) ((ViewGroup) t.getParent()).removeView(t);
                return;
            }
            if (t != null && t.getContext() != a) { // 换了 Activity：旧的拿掉重建
                if (t.getParent() instanceof ViewGroup) ((ViewGroup) t.getParent()).removeView(t);
                t = null;
            }
            ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
            float d = a.getResources().getDisplayMetrics().density;
            if (t == null) {
                t = new TextView(a);
                t.setTextSize(10f);
                t.setTypeface(Typeface.MONOSPACE);
                t.setTextColor(0xFF66FF99);
                t.setBackgroundColor(0xCC000000);
                int pad = (int) (6 * d);
                t.setPadding(pad, pad, pad, pad);
                dbgView = new WeakReference<>(t);
            }
            if (t.getParent() != decor) {
                if (t.getParent() instanceof ViewGroup) ((ViewGroup) t.getParent()).removeView(t);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
                lp.topMargin = (int) (90 * d);
                decor.addView(t, lp);
            } else if (decor.indexOfChild(t) != decor.getChildCount() - 1) {
                t.bringToFront();
            }
            t.setText(buildInfo(decor));
        } catch (Throwable ignored) {
        }
    }

    private static String buildInfo(View decor) {
        StringBuilder sb = new StringBuilder("[时长诊断]\n");
        sb.append("钩子 frame=").append(frameHooked).append(" show=").append(showHooked).append('\n');
        sb.append("状态 ").append(lastNote).append('\n');
        sb.append("屏幕 ").append(decor.getWidth()).append('x').append(decor.getHeight()).append('\n');
        View tv = lastTv == null ? null : lastTv.get();
        View top = lastTop == null ? null : lastTop.get();
        if (tv == null || top == null) return sb.append("还没有时间 View").toString();
        sb.append("文字 ").append(describe(tv));
        if (tv instanceof TextView) sb.append("\n  内容 \"").append(((TextView) tv).getText()).append('"');
        if (top != tv) sb.append("\n外层 ").append(describe(top));
        sb.append("\n偏移 ").append((int) top.getTranslationX()).append(',').append((int) top.getTranslationY()).append("px");
        sb.append(hiddenAncestor(top));
        return sb.toString();
    }

    private static String describe(View v) {
        int[] l = new int[2];
        v.getLocationOnScreen(l);
        boolean on = v.getGlobalVisibleRect(new Rect());
        String n = v.getClass().getName();
        n = n.substring(n.lastIndexOf('.') + 1);
        return n + " " + v.getWidth() + "x" + v.getHeight() + " @" + l[0] + "," + l[1]
                + " " + visName(v.getVisibility()) + " a=" + v.getAlpha() + " shown=" + v.isShown() + " 屏内=" + on;
    }

    private static String visName(int vis) {
        return vis == View.VISIBLE ? "VISIBLE" : vis == View.INVISIBLE ? "INVISIBLE" : "GONE";
    }

    /** 从这个 View 往上，找第一个不可见 / 全透明 / 尺寸为 0 的祖先：「看不到」多半卡在这里。 */
    private static String hiddenAncestor(View v) {
        View cur = v;
        for (int i = 0; i < 14 && cur != null; i++) {
            if (cur.getVisibility() != View.VISIBLE || cur.getAlpha() == 0f
                    || (cur.getWidth() == 0 && cur.getHeight() == 0 && cur.isLaidOut())) {
                String n = cur.getClass().getName();
                n = n.substring(n.lastIndexOf('.') + 1);
                return "\n卡住: 往上第 " + i + " 层 " + n + " " + visName(cur.getVisibility())
                        + " a=" + cur.getAlpha() + " " + cur.getWidth() + "x" + cur.getHeight();
            }
            ViewParent pp = cur.getParent();
            cur = pp instanceof View ? (View) pp : null;
        }
        return "\n往上 14 层都可见";
    }

    // ------------------------------------------------------------ 找时间 View

    private static Field outerF;
    private static boolean outerInit;

    /** 观察者（Kotlin 匿名类）持有外层组件的那个字段，通常叫 this$0；按类型找，不依赖名字。 */
    private static Object outerOf(Object self) {
        try {
            if (!outerInit) {
                outerInit = true;
                Class<?> k = self.getClass();
                String outer = k.getName();
                int i = outer.indexOf('$');
                if (i > 0) outer = outer.substring(0, i);
                for (Field f : k.getDeclaredFields()) {
                    if (f.getType().getName().equals(outer)) {
                        f.setAccessible(true);
                        outerF = f;
                        break;
                    }
                }
            }
            return outerF == null ? null : outerF.get(self);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 从观察者出发，沿字段往下最多 3 层（只进抖音自己的类，遇到 kotlin 的 Lazy 就取它的值），收集这个组件持有的 View，
     * 再挑出显示 "分:秒" 的那个 TextView。返回 {文字所在的 TextView, 要移动的最外层小容器}。
     */
    private static View[] locate(Object self, int tick) {
        ArrayList<View> cands = new ArrayList<>();
        scan(self, 0, cands, new IdentityHashMap<Object, Boolean>(), new int[]{200});
        if (cands.isEmpty()) return null;

        TextView hit = null;
        for (View v : cands) {
            if (v instanceof TextView) {
                if (hasTime(((TextView) v).getText())) {
                    hit = (TextView) v;
                    break;
                }
            } else if (v instanceof ViewGroup) {
                TextView t = findTimeText((ViewGroup) v, new int[]{80});
                if (t != null) {
                    hit = t;
                    break;
                }
            }
        }
        // 文字还是空的（时间还没写进去）：前几次先等，之后退而求其次，取组件持有的第一个 TextView
        if (hit == null && tick >= 6) {
            for (View v : cands) {
                if (v instanceof TextView) {
                    hit = (TextView) v;
                    break;
                }
            }
        }
        if (hit == null) return null;
        return new View[]{hit, climb(hit)};
    }

    private static void scan(Object o, int depth, ArrayList<View> out, IdentityHashMap<Object, Boolean> seen, int[] budget) {
        if (o == null || budget[0] <= 0 || seen.put(o, Boolean.TRUE) != null) return;
        budget[0]--;
        if (o instanceof View) {
            out.add((View) o);
            return;
        }
        Class<?> k = o.getClass();
        if (k.getName().contains("Lazy")) {
            try {
                Method m = k.getMethod("getValue");
                scan(m.invoke(o), depth, out, seen, budget);
            } catch (Throwable ignored) {
            }
            return;
        }
        if (depth >= 3) return;
        for (Class<?> c = k; c != null && c != Object.class && !isFramework(c.getName()); c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(o);
                    if (v == null) continue;
                    if (v instanceof View) {
                        scan(v, depth + 1, out, seen, budget);
                    } else {
                        String n = v.getClass().getName();
                        if (n.contains("Lazy") || isAppClass(n)) scan(v, depth + 1, out, seen, budget);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static boolean isFramework(String n) {
        return n.startsWith("java.") || n.startsWith("android.") || n.startsWith("androidx.") || n.startsWith("kotlin.");
    }

    private static boolean isAppClass(String n) {
        return n.startsWith("com.ss.android.") || n.startsWith("com.bytedance.") || n.startsWith("X.");
    }

    private static boolean hasTime(CharSequence t) {
        return t != null && t.length() > 0 && TIME.matcher(t).find();
    }

    private static TextView findTimeText(ViewGroup g, int[] budget) {
        for (int i = 0; i < g.getChildCount(); i++) {
            if (budget[0]-- <= 0) return null;
            View c = g.getChildAt(i);
            if (c instanceof TextView) {
                if (hasTime(((TextView) c).getText())) return (TextView) c;
            } else if (c instanceof ViewGroup) {
                TextView t = findTimeText((ViewGroup) c, budget);
                if (t != null) return t;
            }
        }
        return null;
    }

    /** 文字外面如果只是一个小的包装容器（底板 / 阴影之类），连它一起移动；最多往上 3 层，碰到大容器就停。 */
    private static View climb(TextView t) {
        View v = t;
        View root = t.getRootView();
        int w = root.getWidth(), h = root.getHeight();
        for (int i = 0; i < 3; i++) {
            ViewParent pp = v.getParent();
            if (!(pp instanceof ViewGroup)) break;
            ViewGroup g = (ViewGroup) pp;
            if (w <= 0 || h <= 0 || g.getWidth() <= 0 || g.getWidth() > w * 0.6f || g.getHeight() > h * 0.25f) break;
            v = g;
        }
        return v;
    }
}
