package com.danmaku.randcolor;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;

/**
 * 发布日期和地点常显：自己画一行 "3天前 北京" / "10月10日 江西"，位置用抖音里的浮窗（TimePanel）调整，和视频时长一样。
 *
 * 依据（TextRec，抖音 40.7.0，textrec-tasks-20261020-084058 的 #424）：
 *  - 点视频文案末尾的「展开」时，MentionTextView（id g=q，页面 UltraDetailActivity > C2FeedFragment，
 *    父级 feed.long_press_panel.ui.FixedNestedScrollView）被设置成完整文案，结尾是「4 个空格 + 日期 + 空格 + 地点」：
 *    「…#历史剧解说    3天前 北京」。没有地点的视频结尾只有日期，地点可有可无。
 *  - 设置侧的稳定钩点：com.bytedance.ies.dmt.ui.widget.DmtTextView#setText(CharSequence, TextView$BufferType)
 *    （TextRec 推荐的签名，类名和方法名都没混淆）。这个方法是通用文字控件的，所以回调里先看开关，再比较 this 的类是不是
 *    MentionTextView，再看文字结尾；其他控件的调用在第一次类比较就返回。
 *  - 这条设置只在「展开」时出现过（任务 4 共 13 次，划视频的任务里 0 次），所以每个视频要点过一次「展开」才有得显示。
 *
 * 什么时候撤掉：换了视频（播放器 onPlaying 的视频 id 变了）或离开这个视频（onPlayStop）就隐藏，下一个视频要再展开一次；
 * 这两个事件来自 TimeHook 已经挂好的播放器监听（TimeHook 里各加了一行调用）。那几个时长钩子没挂上时，
 * 文字会一直留到下一次捕获或换了界面。
 *
 * 没有定时器 / 轮询：文字只在上面的回调里更新或隐藏。
 */
final class PubHook {
    private PubHook() {}

    /** 版本相关（40.7.0）。 */
    private static final String DMT = "com.bytedance.ies.dmt.ui.widget.DmtTextView";
    private static final String MENTION = "com.ss.android.ugc.aweme.views.MentionTextView";
    private static final String PLACEHOLDER = "3天前 北京";
    /** 结尾「日期 地点」最多看多少个字符。 */
    private static final int MAX_TAIL = 40;

    private static final Handler MAIN = new Handler(Looper.getMainLooper()); // 只用来把非主线程的事件转回主线程
    private static final XC_MethodHook HOOK = new Hook();

    private static final HookGuard.Filter SET_TEXT = new HookGuard.Filter() {
        @Override
        public boolean ok(Method m) {
            if (!m.getName().equals("setText")) return false;
            Class<?>[] t = m.getParameterTypes();
            return t.length == 2 && t[0] == CharSequence.class && t[1] == TextView.BufferType.class;
        }
    };

    private static volatile Class<?> mentionCls;
    private static WeakReference<TextView> viewRef = new WeakReference<>(null);

    // 状态（都在主线程用）
    private static String curId;
    private static String lastText = "";
    private static int lastDx = Integer.MIN_VALUE, lastDy = Integer.MIN_VALUE;

    // ------------------------------------------------------------ 安装

    /** 启动时和每次回到前台都会被调用；HookGuard 保证已有结论后立刻返回。 */
    static void tryHook(ClassLoader cl) {
        if (cl == null || HookGuard.settled(HookGuard.PUB)) return;
        ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        Class<?> mc = HookGuard.find(HookGuard.PUB, MENTION, cl, ctx); // 类没找到：记一次等待，下次回到前台再试
        if (mc == null) return;
        mentionCls = mc;
        HookGuard.hook(HookGuard.PUB, DMT, SET_TEXT, HOOK,
                "DmtTextView 里没有 setText(CharSequence, BufferType)（抖音改了文字控件，需要重新追踪）", cl, ctx);
    }

    private static boolean isMain() {
        return Looper.myLooper() == Looper.getMainLooper();
    }

    /** 文字是否应该存在：设置里开了，或者位置浮窗正在调它（调位置时即使没开也要看得到）。 */
    private static boolean enabled() {
        return DanmakuHook.cfg.pubAlways || (TimePanel.isOpen() && TimePanel.target() == TimePanel.PUB);
    }

    // ------------------------------------------------------------ 钩子

    private static final class Hook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam p) {
            try {
                if (!enabled()) return;
                Class<?> k = mentionCls;
                Object self = p.thisObject;
                if (k == null || self == null || self.getClass() != k) return; // 只处理 MentionTextView 本身
                Object a = p.args[0];
                if (!(a instanceof CharSequence)) return;
                final String tail = tail((CharSequence) a);
                if (tail == null) return;
                if (isMain()) {
                    onCaptured(tail);
                } else {
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            onCaptured(tail);
                        }
                    });
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 取文字结尾的「日期 地点」。结尾是 4 个空格 + 日期 + 空格 + 地点（地点可有可无）。
     * 日期只认追踪里见过的写法：第一个字符是数字、最后一个字符是「前」或「日」（"3天前" "39分钟前" "10月10日"）。
     * 不认的写法（比如"昨天"）返回 null，什么都不显示。只在确认是日期之后才分配字符串。
     */
    private static String tail(CharSequence cs) {
        int n = cs.length();
        int lo = Math.max(0, n - MAX_TAIL);
        int sep = -1;
        for (int i = n - 4; i >= lo; i--) {
            if (cs.charAt(i) == ' ' && cs.charAt(i + 1) == ' ' && cs.charAt(i + 2) == ' ' && cs.charAt(i + 3) == ' ') {
                sep = i + 4; // 最靠后的一段空格的结尾
                break;
            }
        }
        if (sep < 0 || sep >= n) return null;
        int e = sep;
        while (e < n && cs.charAt(e) != ' ') e++; // 日期这一段的结尾
        char first = cs.charAt(sep);
        char last = cs.charAt(e - 1);
        if (first < '0' || first > '9') return null;
        if (last != '前' && last != '日') return null;
        String s = cs.subSequence(sep, n).toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static void onCaptured(String text) {
        show(CommentHook.top.get(), text);
    }

    // ------------------------------------------------------------ 视频切换（由 TimeHook 里已有的播放器监听转进来，主线程）

    /** 某个视频开始播放：换了视频就把上一个视频的文字撤掉（位置浮窗开着时保留示例）。 */
    static void onPlay(String id) {
        if (id == null || id.equals(curId)) return;
        curId = id;
        if (!TimePanel.isOpen()) hideNow();
    }

    /** 离开这个视频（划走 / 播放器释放）：别的视频迟到的事件不动当前文字。 */
    static void onStop(String id) {
        if (id == null || !id.equals(curId)) return;
        curId = null;
        if (!TimePanel.isOpen()) hideNow();
    }

    // ------------------------------------------------------------ 显示

    private static boolean isHostActivity(Activity a) {
        return a != null && !a.isFinishing() && !a.isDestroyed();
    }

    /** 位置浮窗打开时调用：没有捕获到文字也先画一个示例，好边调边看。 */
    static void showSample(Activity a) {
        show(a, lastText.isEmpty() ? PLACEHOLDER : lastText);
    }

    private static void show(Activity a, String text) {
        if (!isHostActivity(a)) return;
        TextView t = viewRef.get();
        ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
        float d = a.getResources().getDisplayMetrics().density;
        if (t != null && t.getContext() != a) { // 换了 Activity：旧的拿掉重建
            detach(t);
            t = null;
        }
        if (t == null) {
            t = new TextView(a);
            t.setTextSize(16f);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setTextColor(0xFFFFFFFF);
            t.setShadowLayer(5f * d, 0f, 0f, 0xFF000000); // 黑色描边感，亮暗背景都看得清
            t.setIncludeFontPadding(false);
            t.setClickable(false);
            t.setFocusable(false);
            t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            viewRef = new WeakReference<>(t);
            lastText = "";
            lastDx = lastDy = Integer.MIN_VALUE;
        }
        View panel = TimePanel.view();
        boolean moved = false;
        if (t.getParent() != decor) {
            detach(t);
            decor.addView(t, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
            moved = true;
        } else {
            View last = decor.getChildAt(decor.getChildCount() - 1);
            if (last != t && last != panel) {
                t.bringToFront();
                moved = true;
            }
        }
        if (moved && panel != null && panel.getParent() == decor) panel.bringToFront(); // 浮窗始终压在文字上面
        applyOffset(t);
        if (!text.equals(lastText)) {
            lastText = text;
            t.setText(text);
        }
        if (t.getVisibility() != View.VISIBLE) t.setVisibility(View.VISIBLE);
    }

    private static void applyOffset(TextView t) {
        DanmakuConfig c = DanmakuHook.cfg;
        if (c.pubX == lastDx && c.pubY == lastDy) return;
        lastDx = c.pubX;
        lastDy = c.pubY;
        t.setTranslationX(c.pubX);
        t.setTranslationY(c.pubY);
    }

    /** 位置浮窗拖动时实时生效。 */
    static void applyOffsetNow() {
        TextView t = viewRef.get();
        if (t != null) applyOffset(t);
    }

    private static void hideNow() {
        TextView t = viewRef.get();
        if (t != null) t.setVisibility(View.GONE);
    }

    // ------------------------------------------------------------ 生命周期

    private static void detach(View v) {
        if (v != null && v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
    }

    private static void removeView() {
        detach(viewRef.get());
    }

    /** 位置浮窗关闭后：没开常显就把文字撤掉。 */
    static void onPanelClosed() {
        if (!DanmakuHook.cfg.pubAlways) removeView();
    }

    /** 回到前台（配置刚重读过）：需要时弹出位置浮窗；换了页面的文字拿掉重建；位置有变立即生效。 */
    static void onResume(Activity a) {
        try {
            if (!isMain()) return;
            DanmakuConfig c = DanmakuHook.cfg;
            if (c.pubAdjust && isHostActivity(a)) {
                c.pubAdjust = false; // 一次性请求：先清掉并写回，免得下次回到前台又弹
                DanmakuHook.saveCfg();
                TimePanel.open(a, TimePanel.PUB);
            }
            TextView t = viewRef.get();
            if (t == null) return;
            if (!enabled() || !isHostActivity(a) || t.getContext() != a) {
                removeView();
                return;
            }
            applyOffset(t);
        } catch (Throwable ignored) {
        }
    }
}
