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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

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
 * 第二条数据来源（不用点「展开」，钩子 pub.bind）：
 *  - 依据 TextRec（抖音 40.7.0，textrec-tasks-20261020-143514）：VideoViewHolder#bind(Aweme) / bind(Aweme, int) 是翻页时的绑定入口，
 *    ForwardFeedReversionVideoViewHolder 和 PlayletVideoViewHolder 的 bind 都经过它（#5 的调用栈）；参数 Aweme 的 toString 是
 *    Aweme{aid='7698182854795525988'…}；播放器 onPlaying 的第一个参数（以及 PlayerEvent 的 id）就是同一个字符串：三个视频
 *    （7698182854795525988 / 7698386214974175347 / 7687788879000112430）bind 的 aid 和 onPlaying 的 id 逐个相同（#226/#244 等）。
 *  - bind 是翻页预加载触发的，邻近视频会提前 bind（36874ms bind 了 7696206519011937599，36892ms onPlaying 的还是 7687788879000112430，
 *    之后也没播过它），所以不能拿「最后一次 bind」当当前视频：bind 时按 aid 记下 createTime / ipAttribution，onPlaying(id) 来了再按 id 查出来显示。
 *  - Aweme 里的字段 createTime（秒级时间戳）和 ipAttribution（省名）取自 TextRec 的字段快照导出（这个仓库和 143514 那份里都没有，见 README 局限）。
 *    字段在安装阶段按名字找好并缓存（找不到就 miss，不猜）；回调里只做取值。
 *
 * 没有定时器 / 轮询：文字只在上面的回调里更新或隐藏。
 */
final class PubHook {
    private PubHook() {}

    /** 版本相关（40.7.0）。 */
    private static final String DMT = "com.bytedance.ies.dmt.ui.widget.DmtTextView";
    private static final String MENTION = "com.ss.android.ugc.aweme.views.MentionTextView";
    private static final String PLACEHOLDER = "2026-10-17 14:32:08 北京";
    /** 结尾「日期 地点」最多看多少个字符。 */
    private static final int MAX_TAIL = 40;

    // pub.bind：版本相关（40.7.0），类名没混淆（TextRec 的钩点签名里就是这两个名字）
    private static final String HOLDER = "com.ss.android.ugc.aweme.feed.adapter.VideoViewHolder";
    private static final String AWEME = "com.ss.android.ugc.aweme.feed.model.Aweme";
    /** 最多记多少个视频（feed 无限滚动，只留最近的，当前视频和左右预加载的都在里面）。 */
    private static final int MAX_INFO = 48;

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
    private static final XC_MethodHook BIND_HOOK = new BindHook();

    /** 安装阶段找好的 Aweme 字段（回调里只取值）。ip 可以没有。 */
    private static volatile Field fAid, fTime, fIp;

    /** 一个视频的发布时间和地点。 */
    private static final class Info {
        final long sec;
        final String ip;

        Info(long sec, String ip) {
            this.sec = sec;
            this.ip = ip;
        }
    }

    /** aid → Info，按访问顺序，超过 MAX_INFO 丢最久没用的。任何线程访问都要 synchronized。 */
    private static final Map<String, Info> INFO = new LinkedHashMap<String, Info>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Info> e) {
            return size() > MAX_INFO;
        }
    };
    private static WeakReference<TextView> viewRef = new WeakReference<>(null);

    // 状态（都在主线程用）
    private static String curId;
    private static String lastText = "";
    private static int lastDx = Integer.MIN_VALUE, lastDy = Integer.MIN_VALUE;

    // ------------------------------------------------------------ 安装

    /** 启动时和每次回到前台都会被调用；HookGuard 保证已有结论后立刻返回。 */
    static void tryHook(ClassLoader cl) {
        if (cl == null) return;
        ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        tryText(cl, ctx);
        tryBind(cl, ctx);
    }

    /** pub.text：「展开」文案结尾的日期地点。 */
    private static void tryText(ClassLoader cl, ClassLoader ctx) {
        if (HookGuard.settled(HookGuard.PUB)) return;
        Class<?> mc = HookGuard.find(HookGuard.PUB, MENTION, cl, ctx); // 类没找到：记一次等待，下次回到前台再试
        if (mc == null) return;
        mentionCls = mc;
        HookGuard.hook(HookGuard.PUB, DMT, SET_TEXT, HOOK,
                "DmtTextView 里没有 setText(CharSequence, BufferType)（抖音改了文字控件，需要重新追踪）", cl, ctx);
    }

    /**
     * pub.bind：挂 VideoViewHolder 里第一个参数是 Aweme 的 bind（bind(Aweme) 和 bind(Aweme, int) 都挂，处理是幂等的）。
     * Aweme 类按名字找，aid / createTime / ipAttribution 三个字段按名字找并缓存；aid（String）和 createTime（long / int）必须有，
     * 缺了就 miss（字段被混淆了，需要重新用 TextRec 看 Aweme 的字段快照）；ipAttribution 没有或不是 String 就只显示日期。
     */
    private static void tryBind(ClassLoader cl, ClassLoader ctx) {
        if (HookGuard.settled(HookGuard.PUB_BIND)) return;
        Class<?> vh = HookGuard.find(HookGuard.PUB_BIND, HOLDER, cl, ctx);
        if (vh == null) return;
        Class<?> aw = HookGuard.find(HookGuard.PUB_BIND, AWEME, cl, ctx);
        if (aw == null) return;
        Field a, t, ip = null;
        try {
            a = aw.getDeclaredField("aid");
            t = aw.getDeclaredField("createTime");
        } catch (NoSuchFieldException e) {
            HookGuard.miss(HookGuard.PUB_BIND, "Aweme 里没有 aid / createTime 字段（字段名变了，需要重新用 TextRec 看 Aweme 的字段快照）");
            return;
        } catch (Throwable e) { // 类的依赖还没就绪
            HookGuard.waitFail(HookGuard.PUB_BIND);
            return;
        }
        if (a.getType() != String.class || (t.getType() != long.class && t.getType() != int.class)) {
            HookGuard.miss(HookGuard.PUB_BIND, "Aweme.aid 不是 String 或 createTime 不是 long / int（字段类型变了，需要重新追踪）");
            return;
        }
        try {
            ip = aw.getDeclaredField("ipAttribution");
            if (ip.getType() != String.class) ip = null;
        } catch (Throwable ignored) {
            ip = null;
        }
        int n = 0;
        try {
            a.setAccessible(true);
            t.setAccessible(true);
            if (ip != null) ip.setAccessible(true);
            for (Method m : vh.getDeclaredMethods()) {
                if (!m.getName().equals("bind")) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length == 0 || pt[0] != aw) continue;
                if (n == 0) { // 字段先放好再挂钩，回调一来就能用
                    fAid = a;
                    fTime = t;
                    fIp = ip;
                }
                try {
                    XposedBridge.hookMethod(m, BIND_HOOK);
                    n++;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable e) { // 方法签名里引用的类还没就绪
            HookGuard.waitFail(HookGuard.PUB_BIND);
            return;
        }
        if (n > 0) HookGuard.ok(HookGuard.PUB_BIND, ip == null ? "没有地点字段，只显示日期" : "");
        else HookGuard.miss(HookGuard.PUB_BIND, "VideoViewHolder 里没有 bind(Aweme, …)（翻页绑定入口变了，需要重新追踪）");
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

    /**
     * 翻页时的绑定（主线程，邻近视频会提前 bind）：按 aid 记下发布时间和地点。已经记过的 aid 直接跳过（bind 会成对出现、也会重复）。
     * 回调里的取值是反射的 Field 读取（Field 在安装阶段缓存好，每个视频一次，不是每帧）；只在开关开着时才记。
     */
    private static final class BindHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam p) {
            try {
                if (!enabled()) return;
                Field fa = fAid, ft = fTime;
                Object aw = p.args[0];
                if (fa == null || ft == null || aw == null) return;
                Object o = fa.get(aw);
                if (!(o instanceof String)) return;
                String aid = (String) o;
                Info known;
                synchronized (INFO) {
                    known = INFO.get(aid);
                }
                if (known == null) {
                    long sec = ft.getLong(aw);
                    if (sec <= 0) return;
                    Field fi = fIp;
                    Object ip = fi == null ? null : fi.get(aw);
                    known = new Info(sec, ip instanceof String ? (String) ip : null);
                    synchronized (INFO) {
                        INFO.put(aid, known);
                    }
                }
                // onPlaying 比 bind 先到的情况（这个视频已经在播）：补显示；预加载的邻近视频 id 对不上，什么都不做
                if (isMain() && aid.equals(curId) && !TimePanel.isOpen()) showInfo(known);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void showInfo(Info r) {
        if (!enabled()) return;
        show(CommentHook.top.get(), format(r));
    }

    /** 发布时间 → 「2026-10-17 14:32:08」（createTime 是秒级时间戳，按手机时区），有地点就接在后面。 */
    private static String format(Info r) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(r.sec * 1000L);
        StringBuilder b = new StringBuilder(32);
        b.append(c.get(Calendar.YEAR)).append('-');
        two(b, c.get(Calendar.MONTH) + 1).append('-');
        two(b, c.get(Calendar.DAY_OF_MONTH)).append(' ');
        two(b, c.get(Calendar.HOUR_OF_DAY)).append(':');
        two(b, c.get(Calendar.MINUTE)).append(':');
        two(b, c.get(Calendar.SECOND));
        String ip = r.ip;
        if (ip != null) {
            ip = ip.trim();
            if (!ip.isEmpty()) b.append(' ').append(ip);
        }
        return b.toString();
    }

    private static StringBuilder two(StringBuilder b, int v) {
        if (v < 10) b.append('0');
        return b.append(v);
    }

    private static void onCaptured(String text) {
        show(CommentHook.top.get(), text);
    }

    // ------------------------------------------------------------ 视频切换（由 TimeHook 里已有的播放器监听转进来，主线程）

    /**
     * 某个视频开始播放：换了视频就把上一个视频的文字撤掉（位置浮窗开着时保留示例），再按 id 查 bind 时记下的发布时间地点显示出来；
     * 查不到（hook 装得晚、不是 feed 里的视频）就什么都不显示。onPlaying 每个视频会来很多次，同一个 id 第二次起直接返回。
     */
    static void onPlay(String id) {
        if (id == null || id.equals(curId)) return;
        curId = id;
        if (TimePanel.isOpen()) return;
        hideNow();
        Info r;
        synchronized (INFO) {
            r = INFO.get(id);
        }
        if (r != null) showInfo(r);
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
