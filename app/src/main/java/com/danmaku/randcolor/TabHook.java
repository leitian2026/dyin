package com.danmaku.randcolor;

import android.app.Activity;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 隐藏抖音首页顶部标签栏（关注 / 直播 / 同城 / 游戏 …）里被勾选的标签。
 * TextRec 的记录：标签文字是 AntiBurnDuxTextView，它的直接父级是 HomeTopTabItemViewNew（类名没混淆）。
 * 做法：
 *  1) 每次回到前台扫一遍界面：找到所有 *HomeTopTabItemView* 条目，读出文字，记到 files/danmaku_color.tabs
 *     （模块界面读它来列复选框），勾选的整条隐藏（GONE），取消勾选的恢复。
 *  2) 标签是创建时一次性设置文字的：挂 TextView.setText，文字命中且祖先里有标签条目就立刻隐藏。
 *  3) App 之后再 setVisibility 把它显示出来的，在 View.setVisibility 里强制保持 GONE。
 * 只隐藏标签这一项；首页滑动仍会切到对应页面（页面没有被移除）。
 */
final class TabHook {
    private TabHook() {}

    /** 标签条目的类名特征：HomeTopTabItemViewNew */
    private static final String ITEM = "HomeTopTabItemView";

    /** 被我们隐藏的条目（弱引用，页面销毁就自动丢）。 */
    private static final Set<View> HIDDEN = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<View, Boolean>()));
    private static volatile boolean anyHidden;
    /** 标签文字 → 最后一次看到的 left（用来按屏幕顺序排）。 */
    private static final Map<String, Integer> LEFTS = new HashMap<>();
    private static boolean installed;
    private static volatile String lastWritten = "";
    private static volatile String lastStatus = "";
    static volatile File tabsFile;

    static synchronized void install() {
        if (installed) return;
        installed = true;
        try {
            Method st = TextView.class.getDeclaredMethod("setText", CharSequence.class, TextView.BufferType.class);
            XposedBridge.hookMethod(st, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        if (p.thisObject instanceof TextView) onText((TextView) p.thisObject);
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable t) {
            DanmakuHook.status("挂接 TextView.setText 失败：" + t);
        }
        try {
            Method sv = View.class.getDeclaredMethod("setVisibility", int.class);
            XposedBridge.hookMethod(sv, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (anyHidden && p.thisObject instanceof View && HIDDEN.contains(p.thisObject)) {
                            p.args[0] = View.GONE; // 被我们隐藏的条目，App 想显示也不让
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable t) {
            DanmakuHook.status("挂接 View.setVisibility 失败：" + t);
        }
    }

    // ------------------------------------------------------------ 创建时：文字命中就隐藏

    private static void onText(final TextView tv) {
        DanmakuConfig c = DanmakuHook.cfg;
        if (c.hideTabs.isEmpty()) return; // 没勾选任何标签：这条路径什么都不做（setText 调用极频繁，必须第一时间返回）
        CharSequence t = tv.getText();
        if (t == null || t.length() == 0 || t.length() > 12) return;
        if (!c.hideTabs.contains(t.toString())) return;
        View item = findItem(tv);
        if (item != null) {
            hide(item);
        } else if (tv.getParent() == null) { // 刚创建、还没挂到父级：挂上去后再判断一次
            tv.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                    v.removeOnAttachStateChangeListener(this);
                    try {
                        onText(tv);
                    } catch (Throwable ignored) {
                    }
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                }
            });
        }
    }

    /** 往上最多 4 层找标签条目（TextRec 记录里文字的直接父级就是它）。 */
    private static View findItem(View v) {
        Object p = v.getParent();
        for (int i = 0; i < 4 && p instanceof View; i++) {
            View pv = (View) p;
            if (pv.getClass().getName().contains(ITEM)) return pv;
            p = pv.getParent();
        }
        return null;
    }

    private static void hide(final View item) {
        HIDDEN.add(item);
        anyHidden = true;
        if (item.getVisibility() == View.GONE) return;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            item.setVisibility(View.GONE);
        } else {
            item.post(new Runnable() {
                @Override
                public void run() {
                    item.setVisibility(View.GONE);
                }
            });
        }
    }

    // ------------------------------------------------------------ 回到前台：扫一遍

    static void scanLater(Activity a) {
        try {
            final View decor = a.getWindow().getDecorView();
            decor.post(new Runnable() {
                @Override
                public void run() {
                    attempt(decor, 3);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /** 冷启动时标签栏可能比 onResume 晚一点才创建：没扫到就隔 1.5 秒再试，最多 3 次。 */
    private static void attempt(final View decor, final int left) {
        int n = 0;
        try {
            n = scan(decor);
        } catch (Throwable ignored) {
        }
        if (n == 0 && left > 0) {
            decor.postDelayed(new Runnable() {
                @Override
                public void run() {
                    attempt(decor, left - 1);
                }
            }, 1500);
        }
    }

    /** 返回找到的标签条目数。 */
    private static int scan(View root) {
        DanmakuConfig c = DanmakuHook.cfg;
        ArrayList<View> todo = new ArrayList<>();
        todo.add(root);
        int seen = 0, items = 0, hid = 0;
        while (!todo.isEmpty() && seen < 8000) {
            View v = todo.remove(todo.size() - 1);
            seen++;
            if (v.getClass().getName().contains(ITEM)) {
                items++;
                String label = labelOf(v, 0);
                if (label != null) {
                    if (v.getVisibility() == View.VISIBLE && v.getWidth() > 0) {
                        LEFTS.put(label, v.getLeft());
                    } else if (!LEFTS.containsKey(label)) {
                        LEFTS.put(label, Integer.MAX_VALUE - LEFTS.size()); // 一开始就是隐藏的，没见过位置：排后面
                    }
                    if (c.hideTabs.contains(label)) {
                        hide(v);
                        hid++;
                    } else if (HIDDEN.remove(v)) { // 取消勾选：恢复显示
                        v.setVisibility(View.VISIBLE);
                    }
                }
                continue; // 条目里面不用再往下找
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) todo.add(g.getChildAt(i));
            }
        }
        if (items == 0) return 0; // 这个界面没有标签栏（比如在别的页面）
        writeTabs();
        String st = "顶部标签 " + LEFTS.size() + " 个，隐藏 " + hid + " 个";
        if (!st.equals(lastStatus)) {
            lastStatus = st;
            DanmakuHook.status(st);
        }
        return items;
    }

    /** 条目里第一个有文字的 TextView 的文字（标签名）。 */
    private static String labelOf(View v, int depth) {
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            return t != null && t.length() > 0 && t.length() <= 12 ? t.toString() : null;
        }
        if (depth > 4 || !(v instanceof ViewGroup)) return null;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            String s = labelOf(g.getChildAt(i), depth + 1);
            if (s != null) return s;
        }
        return null;
    }

    /** 按屏幕从左到右写进 files/danmaku_color.tabs，模块界面用 root 读它。 */
    private static void writeTabs() {
        File f = tabsFile;
        if (f == null) return;
        ArrayList<Map.Entry<String, Integer>> es = new ArrayList<>(LEFTS.entrySet());
        Collections.sort(es, new Comparator<Map.Entry<String, Integer>>() {
            @Override
            public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                return Integer.compare(a.getValue(), b.getValue());
            }
        });
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : es) sb.append(e.getKey()).append('\n');
        String s = sb.toString();
        if (s.equals(lastWritten)) return;
        lastWritten = s;
        try (FileOutputStream os = new FileOutputStream(f, false)) {
            os.write(s.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
        }
    }
}
