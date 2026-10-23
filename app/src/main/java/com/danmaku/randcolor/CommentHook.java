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
 * 把"双击点赞"改成"打开评论"（开关 dtapToComment）。1.29.0 起不再有「右侧爱心按钮改评论」（FeedDiggPresenter#LJJJJJL）。
 * 双击视频：在 MonitorScrollFrameLayout.onInterceptTouchEvent 里识别双击并拦住（dtap.seen + dtap.cut），改为点一下评论按钮；
 *     再用 dtap.click 跳过随后的单击处理（不暂停）。三个钩子的类名、方法名都没混淆。
 *
 * 1.26.4 起不再有混淆名的双击兜底（BaseListFragmentPanel#LLLIILIL）：它在前面这条路径失效时照样能打开评论，
 * 会把「前面的钩子到底有没有用」盖住，设置页里钩子状态再好看也不知道是不是真的在起作用。
 * 现在这条路径失效 = 双击恢复成点赞，一眼就能看出来。（LLLIILIL 的追踪证据：40.7.0 里一次双击 X.06y9.onTouch 只调它一次，红心和点赞都从它往下走。）
 *
 * 评论按钮的找法：contentDescription 形如"评论24，按钮"、可见面积最大的那个。
 * 找不到评论按钮时：双击不放行（双击不点赞）。
 */
final class CommentHook {
    private CommentHook() {}

    static volatile WeakReference<Activity> top = new WeakReference<>(null);

    /**
     * 双击的稳定名路径（40.7.0，TextRec：textrec-tasks-20261020-203447 / 20261021-032906 / 20261021-093511）：
     *  - 双击在 MonitorScrollFrameLayout.onInterceptTouchEvent 里被识别：第二下 DOWN 时它的栈是
     *    onInterceptTouchEvent → ATListener.onTouch → FeedPinchPresenterV2 → LiveData → VideoPinchUtil$eventObserver$1
     *    → GestureDetector.onTouchEvent → SimpleOnGestureListener#onDoubleTap（监听器 AdS2S0100000_29 没有重写它，返回 false）。
     *    onDoubleTap 在两次双击里各命中 1 次（首页和个人主页视频流都有），单击、上滑里是 0 次。
     *  - 识别完 onInterceptTouchEvent 才返回，之后第二下 DOWN 才往下发给 LongPressLayout（点赞 LLLIIIL 在那里面）；
     *    点赞的界面更新（「已点赞」）在 onDoubleTap 之后约 42ms。所以在这个方法返回前把结果改成 true（拦截），
     *    LongPressLayout 就收不到第二下 DOWN。
     *  - VideoViewHolderRootView#onInterceptTouchEvent 在第二下的 MOVE 上返回 true 是抖音自己的取消，发生在点赞之后，拦不住，所以不用它。
     * 没有证据的部分：LongPressLayout 是 MonitorScrollFrameLayout 的后代（按栈上的 X.07Ut.dispatchTouchEvent 推断）；
     * 这里返回 true 之后点赞确实不会发生（按安卓的拦截规则推断）。第一下点击已经让视频暂停，这一步拦不住。
     */
    private static final String MONITOR = "com.ss.android.ugc.aweme.feed.plato.business.contentconsumption.interestselection.MonitorScrollFrameLayout";
    private static final String GESTURE_LISTENER = "android.view.GestureDetector$SimpleOnGestureListener";
    /**
     * 双击之后视频被暂停（TextRec，40.7.0，textrec-tasks-20261021-105717，开着 dyin 的双击改评论）：
     *  - 单击是「延迟处理」的：任务 2 单击（touch 18413）→ onPlayPause 在 18674，隔 261ms；任务 3 单击（touch 32279）→ 继续播放在 32467，隔 188ms。
     *    继续播放那次的调用栈是 Handler.dispatchMessage ← WeakHandler.handleMessage ← BaseListFragmentPanel.handleMsg ← X.06vk.run
     *    ← FeedComponentsRoot.handleSingleClick ← handleSingleClickInner ← handleVideoPauseAndPlay(Aweme) ← … ← OnUIPlayListenerDelegate（暂停 / 继续是同一个切换）。
     *    这四个 FeedComponentsRoot 方法 TextRec 都标 [稳定]（类名方法名没混淆）。
     *  - 任务 4 双击：第一下 touch 46492，评论页 46671 打开，第二下 touch 46763，onPlayPause 在 46844——比第二下还晚，调用栈和单击暂停那条
     *    完全相同（X.0FaC.run ← X.0FWv.run ← Handler.handleCallback）。时间上正好是「第一下点击的延迟单击处理」在双击之后才执行。
     *    推断：抖音自己的双击处理会取消这条延迟的单击，dyin 拦掉了双击，取消没发生，单击照常执行，把视频切成暂停。
     * 没有直接证据的部分：任务 4 的记录里没有出现 handleSingleClick 的栈（栈被合并成「与 #2 相同」），「单击处理是被延迟执行的」是按时间推的。
     * 做法：拦住双击时记下时刻，之后一小段时间内 handleSingleClick 被调到就跳过（不暂停）。
     */
    private static final String FEED_ROOT = "com.ss.android.ugc.aweme.feed.plato.core.FeedComponentsRoot";
    private static final String CLICK_METHOD = "handleSingleClick";
    /** 拦住双击之后多久内，把单击处理跳过（单击处理比第一下点击晚 190~350ms，留够余量，又不影响之后真正的单击）。 */
    private static final long CLICK_SKIP_MS = 800;
    private static volatile long dtapAt;
    private static final XC_MethodHook CLICK_HOOK = new ClickHook();

    /** 同一次双击只打开一次评论。 */
    private static final long REOPEN_GAP_MS = 800;

    private static volatile long lastOpenAt;

    /** 主线程上的触摸分发：正在 MonitorScrollFrameLayout.onInterceptTouchEvent 里 / 这一次里 onDoubleTap 被调到了。 */
    private static boolean inIntercept, dtapSeen;

    private static final XC_MethodHook SEEN_HOOK = new DtapSeenHook();
    private static final XC_MethodHook CUT_HOOK = new DtapCutHook();

    /** 三个钩子的类名、方法名都没混淆；找不到就放弃并记住（HookGuard），双击点赞照常。 */
    static void tryHook(ClassLoader cl) {
        if (cl == null) return;
        // 名字没混淆的双击路径：识别双击（系统方法）+ 在点赞之前拦住（抖音的类，类名和方法名都没混淆，不放宽匹配）
        HookGuard.hook(HookGuard.DTAP_SEEN, GESTURE_LISTENER, motion("onDoubleTap"), SEEN_HOOK,
                "SimpleOnGestureListener 里没有 onDoubleTap(MotionEvent)（系统类，一般不会出现）", cl);
        HookGuard.hook(HookGuard.DTAP_CUT, MONITOR, motion("onInterceptTouchEvent"), CUT_HOOK,
                "MonitorScrollFrameLayout 里没有 onInterceptTouchEvent(MotionEvent)（抖音改了这个类，需要用 TextRec 重新追踪双击时的触摸分发）", cl);
        HookGuard.hook(HookGuard.DTAP_CLICK, FEED_ROOT, voidNamed(CLICK_METHOD), CLICK_HOOK,
                "FeedComponentsRoot 里没有 void handleSingleClick（抖音改了单击处理，需要用 TextRec 重新追踪单击暂停的调用栈）", cl);
    }

    /** 名字匹配、返回 void 的所有重载（handleSingleClick() / handleSingleClick(Float, Float) 都挂，跳过时返回 null 即可）。 */
    private static HookGuard.Filter voidNamed(final String name) {
        return new HookGuard.Filter() {
            @Override
            public boolean ok(Method m) {
                return m.getName().equals(name) && m.getReturnType() == void.class;
            }
        };
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

    /** onDoubleTap 被调到：只在 MonitorScrollFrameLayout 的拦截判断里才算（别的手势检测器不理）。热路径：两次静态读写，不分配。 */
    private static final class DtapSeenHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            try {
                if (inIntercept) dtapSeen = true;
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * MonitorScrollFrameLayout.onInterceptTouchEvent：进来时记下「正在拦截判断」，出来时如果这一次里识别到了双击，
     * 就把结果改成 true（子视图收不到这个 DOWN，LongPressLayout 里的红心和点赞都不会发生）并打开评论。
     * 找不到评论按钮也照样拦：双击不点赞。
     */
    private static final class DtapCutHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            try {
                if (!DanmakuHook.cfg.dtapToComment) return;
                inIntercept = true;
                dtapSeen = false;
            } catch (Throwable ignored) {
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam p) {
            try {
                if (!inIntercept) return;
                inIntercept = false;
                if (!dtapSeen) return;
                dtapSeen = false;
                if (p.hasThrowable()) return;
                long now = SystemClock.uptimeMillis();
                dtapAt = now;
                if (now - lastOpenAt >= REOPEN_GAP_MS) {
                    lastOpenAt = now;
                    openComments();
                }
                p.setResult(Boolean.TRUE);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 拦住双击之后的一小段时间里，抖音延迟执行的「单击处理」（切换暂停 / 播放）直接跳过。热路径：一次静态读、一次时间比较，不分配。 */
    private static final class ClickHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            try {
                if (!DanmakuHook.cfg.dtapToComment) return;
                long t = dtapAt;
                if (t != 0 && SystemClock.uptimeMillis() - t < CLICK_SKIP_MS) p.setResult(null);
            } catch (Throwable ignored) {
            }
        }
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
}
