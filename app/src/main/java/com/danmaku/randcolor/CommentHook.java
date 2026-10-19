package com.danmaku.randcolor;

import android.app.Activity;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;

import de.robv.android.xposed.XC_MethodHook;

/**
 * 把"点赞"改成"打开评论"。两个入口，各有开关，每个入口只钩一个方法：
 *  1) 右侧爱心按钮（likeBtnToComment）：拦掉 FeedDiggPresenter#LJJJJJL，改为点一下评论按钮。
 *  2) 双击视频（dtapToComment）：拦掉 BaseListFragmentPanel#LLLIILIL(MotionEvent)，改为点一下评论按钮。
 *
 * 为什么钩 LLLIILIL：TextRec 的追踪（40.7.0）显示，一次双击里，抖音的触摸监听 X.06y9.onTouch 只调用它一次，
 * 红心（FullFeedFragmentPanel.LLLIIIL → BaseListFragmentPanel.LLLIIIL → DiggLayout.LIZJ）和点赞（LiveData → observeVM$2.LIZ → X.1DAj → LJJLIIIJL）
 * 都从它往下走，次数 1:1。在这个入口直接跳过，下游的红心、点赞都不会发生，不需要时间窗，也不需要再拦下游方法。
 *
 * 评论按钮的找法：contentDescription 形如"评论24，按钮"、可见面积最大的那个。
 * 找不到评论按钮时：按钮点赞照常放行；双击则不放行（双击不点赞）。
 */
final class CommentHook {
    private CommentHook() {}

    static volatile WeakReference<Activity> top = new WeakReference<>(null);

    private static final String DIGG = "com.ss.android.ugc.aweme.feed.quick.presenter.FeedDiggPresenter";
    /** 版本相关：40.7.0 里右侧爱心按钮 onClick 调到的方法。换版本后需要重新记录。 */
    private static final String DIGG_CLICK_METHOD = "LJJJJJL";
    /** 版本相关：40.7.0 里双击的统一入口，void LLLIILIL(MotionEvent)，由 X.06y9.onTouch 调用。 */
    private static final String PANEL = "com.ss.android.ugc.aweme.feed.panel.BaseListFragmentPanel";
    private static final String PANEL_DTAP_METHOD = "LLLIILIL";
    /** 同一次双击只打开一次评论。 */
    private static final long REOPEN_GAP_MS = 800;

    private static volatile long lastOpenAt;

    private static final XC_MethodHook DTAP_HOOK = new DtapHook();
    private static final XC_MethodHook DIGG_HOOK = new DiggHook();

    /**
     * 这两个方法名是混淆的（换版本会变），不做放宽匹配：双击入口如果只按签名 void(MotionEvent) 去猜，
     * 猜错了会让每次触摸都打开评论，宁可不挂。找不到就放弃并记住（HookGuard），按钮点赞 / 双击点赞照常。
     */
    static void tryHook(ClassLoader cl) {
        if (cl == null) return;
        HookGuard.hook(HookGuard.DTAP, PANEL, motion(PANEL_DTAP_METHOD), DTAP_HOOK,
                PANEL_DTAP_METHOD + "(MotionEvent) 在 BaseListFragmentPanel 里没有找到（方法名是混淆的，抖音更新后会变，需要重新追踪）", cl);
        HookGuard.hook(HookGuard.DIGG, DIGG, HookGuard.named(DIGG_CLICK_METHOD), DIGG_HOOK,
                DIGG_CLICK_METHOD + " 在 FeedDiggPresenter 里没有找到（方法名是混淆的，抖音更新后会变，需要重新追踪）", cl);
    }

    /** 只挂参数是单个 MotionEvent、名字匹配的重载。 */
    private static HookGuard.Filter motion(final String name) {
        return new HookGuard.Filter() {
            @Override
            public boolean ok(Method m) {
                if (!m.getName().equals(name)) return false;
                Class<?>[] pt = m.getParameterTypes();
                return pt.length == 1 && pt[0] == MotionEvent.class;
            }
        };
    }

    /** 双击：跳过抖音的双击处理（红心和点赞都在它下面），改为打开评论。找不到评论按钮也不放行：双击不点赞。 */
    private static final class DtapHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            try {
                if (!DanmakuHook.cfg.dtapToComment) return;
                long now = SystemClock.uptimeMillis();
                if (now - lastOpenAt >= REOPEN_GAP_MS) {
                    lastOpenAt = now;
                    openComments();
                }
                p.setResult(defaultOf(((Method) p.method).getReturnType()));
            } catch (Throwable ignored) {
            }
        }
    }

    /** 右侧爱心按钮：只拦“用户点出来的”（切视频 / 绑定数据 / 服务器结果也会调到这个方法）。找不到评论按钮时放行点赞。 */
    private static final class DiggHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            try {
                if (!DanmakuHook.cfg.likeBtnToComment || !fromUserInput()) return;
                if (openComments()) p.setResult(defaultOf(((Method) p.method).getReturnType()));
            } catch (Throwable ignored) {
            }
        }
    }

    private static Object defaultOf(Class<?> t) {
        if (t == boolean.class) return Boolean.FALSE;
        if (t == int.class) return 0;
        if (t == long.class) return 0L;
        if (t == float.class) return 0f;
        if (t == double.class) return 0d;
        if (t == short.class) return (short) 0;
        if (t == byte.class) return (byte) 0;
        if (t == char.class) return (char) 0;
        return null; // void 和对象
    }

    // ------------------------------------------------------------ 打开评论

    /** 找到当前页的评论按钮并点一下；找不到返回 false。 */
    static boolean openComments() {
        View btn = findCommentButton();
        if (btn == null) return false;
        final View target = clickable(btn);
        // 等当前这次点击 / 手势处理完再点，避免在别人的回调里重入
        target.post(new Runnable() {
            @Override
            public void run() {
                try {
                    target.performClick();
                } catch (Throwable ignored) {
                }
            }
        });
        return true;
    }

    /** contentDescription 形如"评论24，按钮"、可见、可见面积最大的那个（相邻几个视频的右侧栏可能同时挂在界面里）。 */
    private static View findCommentButton() {
        Activity a = top.get();
        if (a == null) return null;
        View root = a.getWindow().getDecorView();
        ArrayList<View> todo = new ArrayList<>();
        todo.add(root);
        View best = null;
        long bestArea = 0;
        Rect r = new Rect();
        int seen = 0;
        while (!todo.isEmpty() && seen < 8000) {
            View v = todo.remove(todo.size() - 1);
            seen++;
            if (v.getVisibility() != View.VISIBLE) continue;
            CharSequence d = v.getContentDescription();
            if (d != null && v.isShown()) {
                String s = d.toString();
                if (s.startsWith("评论") && s.endsWith("按钮") && v.getGlobalVisibleRect(r)) {
                    long area = (long) r.width() * r.height();
                    if (area > bestArea) {
                        bestArea = area;
                        best = v;
                    }
                }
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) todo.add(g.getChildAt(i));
            }
        }
        return best;
    }

    /** 自己 → 往上 4 层 → 往下一层，找第一个有点击监听的；都没有就点它自己。 */
    private static View clickable(View btn) {
        View v = btn;
        for (int i = 0; i < 4 && v != null; i++) {
            if (v.hasOnClickListeners()) return v;
            Object p = v.getParent();
            v = p instanceof View ? (View) p : null;
        }
        if (btn instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) btn;
            for (int i = 0; i < g.getChildCount(); i++) if (g.getChildAt(i).hasOnClickListeners()) return g.getChildAt(i);
        }
        return btn;
    }

    /** 当前调用栈里有点击 / 触摸 / 手势事件的帧，说明是用户操作直接引起的（只在点爱心按钮时才会走到这里）。 */
    private static boolean fromUserInput() {
        for (StackTraceElement e : new Throwable().getStackTrace()) {
            String m = e.getMethodName();
            if (m.equals("performClick") || m.equals("onClick") || m.equals("dispatchTouchEvent") || m.equals("onTouchEvent")
                    || m.equals("onDoubleTap") || m.equals("onSingleTapUp") || m.equals("onSingleTapConfirmed")) return true;
        }
        return false;
    }
}
