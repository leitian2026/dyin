package com.danmaku.randcolor;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 发布日期地点、视频时长：每个视频页自己带着自己的两行（1.27.0 重做，取代 1.18～1.26 的「文案文字对 desc、播放事件猜当前视频、
 * 屏幕上扫容器」那一整套）。核心只有一个钩子 pub.bind，时长再加一个 time.progress（TimeHook）。
 *
 * 为什么能这样做（依据：TextRec，抖音 40.7.0，textrec-tasks-20261022-160215 / 160556 / 160823）：
 *  - 翻页入口：VideoViewHolder#bind(Aweme) / bind(Aweme, int)（类名、方法名没混淆）。this 是这一页的 holder
 *    （ForwardFeedReversionVideoViewHolder / PlayletVideoViewHolder 都经过它），参数 Aweme 是这一页的视频。
 *    Aweme 字段名没混淆：aid（String）、createTime（秒）、ipAttribution（省）、city（6 位行政区划代码）、duration（毫秒，快照里 17857）。
 *  - 抖音的竖滑是 VerticalViewPager + PagerAdapter（不是 RecyclerView，没有证据说 holder 有 itemView），页面根是
 *    com.ss.android.ugc.aweme.ad.feed.VideoViewHolderRootView（类名没混淆，RTViewPager 的直接子控件，常驻 2～3 页，划动时复用）。
 *  - holder 里有几个直接持有 View 的字段（字段名是混淆的：k0 / r / x / z，每个版本会变），其中值是 VideoViewHolderRootView 的那个
 *    就是这一页的根（快照：mID=2131416270、11 个子控件，和视图树里的 VideoViewHolderRootView 逐项一致）。所以不认字段名，
 *    按「运行时值是 VideoViewHolderRootView」找，找到后把字段缓存起来。
 *  - 文案和作者声明所在的竖直容器 AwemeIntroInfoLayout（类名没混淆）在页面根往下 4 层
 *    （AwemeIntroInfoLayout ← MeasureOnceCheckDrawChildFrameLayout ← PenetrateTouchRelativeLayout ← MonitorScrollFrameLayout ← VideoViewHolderRootView），
 *    每个页面根里一个。
 *  - 顺序（冷启动 +1340 holder 构造 → +1365 bind → +1384 AwemeIntroInfoLayout#onAttachedToWindow；划一下 23013 → 23022 → 23028）：
 *    容器先在预加载线程里建好（+951），bind 在它挂到窗口之前。bind 返回时页面根和子控件都在，但还没布局、没挂窗口
 *    （根的 mMeasuredWidth = 0、mWindowAttachCount = 0）。「容器在 bind 时已经是根的后代」这一条没有直接抓到（只有 33 个子控件对 34 个的差别），
 *    所以 bind 里找不到容器时不放弃：页面登记着，等这一页的第一个进度回调再找一次（见 onProgress）；
 *    只有空白行和文案样式要用容器，发布信息、时长浮层只要页面根，不受这一条影响。
 *  - 播放器进度回调 onPlayProgressChange(id, 位置ms, 总时长ms) 的 id 就是 Aweme.aid（三个视频逐个相同，见旧版注释的记录）。
 *
 * 做法（1.28.0）：
 *  - bind（after）：取 Aweme 的发布时间 / 地点 / 总时长；按类型取这一页的根。容器不在 bind 里找（1.30.0 起，见下面「容器怎么来」）。
 *    ① 文案上顶（设置页「文案下方空白行」，配置键 blankH，0 = 不动，-1 = 默认 37 sp 高，其余每档 2 px）：把整个 AwemeIntroInfoLayout
 *       往上平移（translationY），作者声明、文案一起上移，下面留出空位给发布地和时长（两个浮层是浮在页面上的，本身不占位置）。
 *       不往容器里加 View（1.28.0 ～ 1.28.3 试过，没有效果）。
 *    ② 发布信息、时长两个文字浮层直接盖在页面根（VideoViewHolderRootView）上，位置可以在全屏任意调（左 / 上，单位 dp，
 *       在抖音里用位置浮窗拖）；不再放进容器、也不再有行距 / 平移的逻辑。每页的根各带一份，换视频就是同一页再 bind 一次，覆盖就行。
 *       页面根的子控件都叠在 (0,0)、带 FrameLayout 才有的 gravity（视图树 lp[matchxmatch 重力17]），按 FrameLayout 往里加；加在最后一个，
 *       不改已有子控件的下标；浮层不可点，不吃触摸。
 *    aid → 这一页的 Rows 登记在 PAGES 里，进度回调按 id 找页面。
 *  - 进度回调：按 id 找到页面，更新这一页的时长文字。没有「当前视频」「划走」的概念：划走的页面本来就在屏幕外。
 *
 * 容器怎么来（1.30.0）：只有一个入口——文案控件。TextRec 40.7.0 的记录（普通视频 / 短剧 / 图集三类 holder）：
 *  - 文案控件 ScrollMentionTextView（id=desc）由 FeedDescModule 在所有信息流页面里创建，它的 setText 每次 bind 都会走，类名、方法名都没混淆；
 *  - 1.29.x 以前容器有两个找法（AwemeIntroInfoLayout#onAttachedToWindow 回调、从页面根往下找 8 层），文案却已经在 setText 里顺着父级认出了容器
 *    （insideIntro）然后丢掉；图文帖上文案有灰色底、容器却没被接手（没上顶、发布地不在文案下方），两个找法都没走通。
 *  现在：文案 setText → 沿父级找到 AwemeIntroInfoLayout → 往上找 VideoViewHolderRootView 登记这一页 → 接手容器（上顶 + 文案下方位置）。
 *  不依赖 holder 是哪个子类、页面根怎么嵌套、回调先后。没有 TextRec 记录的是首页推荐里的图文帖（从历史记录打开显示正常，没抓到）。
 *
 * 文案样式（描边 / 改色 / 灰色底，钩子 pub.text / pub.anchor / cap.draw）和发布信息、时长互不相干，只共用页面容器（空白行也在里面）：
 *  - pub.anchor：只找文案控件的两个类（ScrollMentionTextView 和 MentionTextView，类名没混淆，TextRec 40.7.0 的 SETTEXT / 绘制栈记录）；
 *  - pub.text：DmtTextView#setText(CharSequence, BufferType) 的 after；文案控件（上面两个类、并且在 AwemeIntroInfoLayout 里面，
 *    不再靠文字对 desc）每次设文字：第一次认出时接手它的容器（上顶 / 文案下方位置），再重设一次样式；
 *  - cap.draw：MentionTextView#onDraw 前后临时换 mCurTextColor、画灰色底（依据见 DrawHook）。
 *  - 页面 bind 时也给这一页的文案控件设一次样式，回到前台时给所有见过的页面重设一次（设置改了马上生效）。
 *
 * 没有定时器 / 轮询；钩子回调里只在每页第一次 bind 时用一次反射找根字段（之后是缓存的 Field 读取）。
 * 钩子没挂上时功能安静失效，抖音原来的界面不变。
 */
final class PubHook {
    private PubHook() {}

    /** 版本相关（40.7.0），类名都没混淆。 */
    private static final String DMT = "com.bytedance.ies.dmt.ui.widget.DmtTextView";
    private static final String MENTION = "com.ss.android.ugc.aweme.views.MentionTextView";
    private static final String CAPTION = "com.ss.android.ugc.aweme.feed.desc.ui.ScrollMentionTextView";
    private static final String INTRO = "com.ss.android.ugc.aweme.feed.ui.AwemeIntroInfoLayout";
    private static final String ROOT = "com.ss.android.ugc.aweme.ad.feed.VideoViewHolderRootView";
    private static final String HOLDER = "com.ss.android.ugc.aweme.feed.adapter.VideoViewHolder";
    private static final String AWEME = "com.ss.android.ugc.aweme.feed.model.Aweme";

    /** 应用自己的 tag key（高字节不小于 2），互不相同。 */
    private static final int CAP_TAG = 0x7e0b0c01;    // 认出是视频文案（cap.draw 只改打过 tag 的）
    private static final int ROOT_TAG = 0x7e0b0c04;   // VideoViewHolderRootView 上挂这一页的 Rows
    private static final int CAPW_TAG = 0x7e0b0c07;   // 文案控件上挂了布局监听
    private static final int NAME_TAG = 0x7e0b0c0b;   // 作者名认过了：TRUE = 是（在 AwemeIntroInfoLayout 里的 id=title），FALSE = 不是

    /** 发布信息 / 时长默认字色（配置里 pubColor / timeColor 为 0 时用）。 */
    private static final int ROW_COLOR = 0xCCFFFFFF, TIME_COLOR = 0xFFFFFFFF;

    /** 安装阶段找好的类（回调里只读）。 */
    private static volatile Class<?> introCls, rootCls, capMention, captionCls;
    /** 安装阶段找好的 Aweme 字段（回调里只取值）。ip / city / dur 可以没有。 */
    private static volatile Field fAid, fTime, fIp, fCity, fDur;

    private static final XC_MethodHook TEXT_HOOK = new TextHook();
    private static final XC_MethodHook BIND_HOOK = new BindHook();
    private static final XC_MethodHook DRAW_HOOK = new DrawHook();
    private static final XC_MethodHook NAME_HOOK = new NameHook();

    /** 资源 id「title」的整数值（第一次绘制回调里查一次；0 = 还没查 / 没有）。nameIdTried：查过了（查不到就不再查）。主线程用。 */
    private static int nameId;
    private static boolean nameIdTried;
    // name.draw：onDraw 前后临时换 mCurTextColor（主线程绘制，不重入；和 cap.draw 各用各的）
    private static boolean nameSwapped;
    private static int nameSaved;

    private static final HookGuard.Filter SET_TEXT = new HookGuard.Filter() {
        @Override
        public boolean ok(Method m) {
            if (!m.getName().equals("setText")) return false;
            Class<?>[] t = m.getParameterTypes();
            return t.length == 2 && t[0] == CharSequence.class && t[1] == TextView.BufferType.class;
        }
    };

    private static final HookGuard.Filter ON_DRAW = new HookGuard.Filter() {
        @Override
        public boolean ok(Method m) {
            if (!m.getName().equals("onDraw")) return false;
            Class<?>[] t = m.getParameterTypes();
            return t.length == 1 && t[0] == android.graphics.Canvas.class;
        }
    };

    // cap.draw：onDraw 前后临时换 mCurTextColor（都在主线程的绘制里用，不重入）
    private static volatile Field fCurColor;
    private static boolean drawSwapped;
    private static int drawSaved;

    /** 一个视频的发布时间、地点、总时长。 */
    private static final class Info {
        final long sec, durMs;
        final String ip, city;
        String line, lineTpl; // PubFormat 的结果和算它用的模板（模板变了才重算）

        Info(long sec, String ip, String city, long durMs) {
            this.sec = sec;
            this.ip = ip;
            this.city = city;
            this.durMs = durMs;
        }
    }

    /** 一个视频页（一个 VideoViewHolderRootView）上的东西，主线程用。 */
    private static final class Rows {
        View root;
        ViewGroup intro;     // 这一页的 AwemeIntroInfoLayout；bind 时还没找到就是 null
        TextView cap;        // 容器里的文案控件
        TextView pub, time;  // 盖在页面根上的两个文字浮层（要显示时才建）
        float slotX, slotY;  // 文案下方固定位置（页面根坐标，像素）
        boolean slotOk;      // 上面这个位置算出来了
        int timeLen;         // 时长文字现在的字符数
        boolean timeLocked;  // 时长浮层已经锁成固定大小（文字字符数不变时每秒换字不再重新布局）
        String aid;          // 这一页现在绑的视频
        Info info;
        long lastSec = -1, lastDsec = -2; // 时长现在显示的秒数（-1 = 还没显示过）
        int timeSz = -1, timeCol;
        boolean registered;
    }

    private static final int MAX_PAGES = 48;
    /** aid → 页面。弱引用（Rows 挂在页面根的 tag 上，页面没了它就没了）；主线程用。 */
    private static final HashMap<String, WeakReference<Rows>> PAGES = new HashMap<>();
    /** 找到了容器的页面（给「回到前台重套」和位置浮窗用）；主线程用。 */
    private static final ArrayList<WeakReference<Rows>> LIVE = new ArrayList<>();
    /** holder 类 → 它里面装页面根的那个字段；找不到的类记在 NO_ROOT 里，不再每次都扫。主线程用。 */
    private static final HashMap<Class<?>, Field> ROOT_FIELD = new HashMap<>();
    private static final HashSet<Class<?>> NO_ROOT = new HashSet<>();

    private static final View.OnLayoutChangeListener CAP_LISTENER = new View.OnLayoutChangeListener() {
        @Override
        public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
            if (l == ol && t == ot && r == or && b == ob) return;
            styleCaption(v);
        }
    };

    /** 页面根布局了（第一次布局、尺寸变了）：两个浮层按配置重新放一次。 */
    private static final View.OnLayoutChangeListener ROOT_LISTENER = new View.OnLayoutChangeListener() {
        @Override
        public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
            try {
                Object o = v.getTag(ROOT_TAG);
                if (o instanceof Rows) {
                    Rows rs = (Rows) o;
                    boolean same = l == ol && t == ot && r == or && b == ob;
                    if (!same) placeAll(rs); // 尺寸没变什么都不做（加 / 拿浮层也会让根重新布局一次）
                }
            } catch (Throwable ignored) {
            }
        }
    };

    /** 浮层自己布局了（文字变了宽高变了）：只重新放它自己。 */
    private static final View.OnLayoutChangeListener OVL_LISTENER = new View.OnLayoutChangeListener() {
        @Override
        public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
            try {
                boolean same = l == ol && t == ot && r == or && b == ob; // 大小位置没变（锁定大小后每秒换字走这里）
                ViewParent p = v.getParent();
                if (!(p instanceof View)) return;
                Object o = ((View) p).getTag(ROOT_TAG);
                if (!(o instanceof Rows)) return;
                Rows rs = (Rows) o;
                if (v == rs.pub) {
                    if (!same) {
                        place(rs, v, true);
                        if (rs.time != null) place(rs, rs.time, false); // 时长接在发布地下面时，跟着它的高度走
                    }
                } else if (v == rs.time) {
                    if (!same) place(rs, v, false);
                    lockTime(rs, v);
                }
            } catch (Throwable ignored) {
            }
        }
    };

    // ------------------------------------------------------------ 安装

    /** 启动时和每次回到前台都会被调用；HookGuard 保证已有结论后立刻返回。 */
    static void tryHook(ClassLoader cl) {
        if (cl == null) return;
        ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        tryBind(cl, ctx);
        tryAnchor(cl, ctx);
        tryText(cl, ctx);
        tryDraw(cl, ctx);
        tryName(cl, ctx);
    }

    /**
     * pub.bind：挂 VideoViewHolder 里第一个参数是 Aweme 的 bind（bind(Aweme) 和 bind(Aweme, int) 都挂，处理是幂等的）。
     * 三个类（VideoViewHolder / Aweme / VideoViewHolderRootView）都按名字找，缺哪个都算「类没找到」（等，不放弃）；
     * Aweme 的 aid（String）和 createTime（long / int）必须有，缺了就 miss（字段被混淆了，需要重新用 TextRec 看 Aweme 的字段快照）；
     * ipAttribution、city、duration 没有或类型不对就少显示一段。
     */
    private static void tryBind(ClassLoader cl, ClassLoader ctx) {
        if (HookGuard.settled(HookGuard.PUB_BIND)) return;
        Class<?> vh = HookGuard.find(HookGuard.PUB_BIND, HOLDER, cl, ctx);
        if (vh == null) return;
        Class<?> aw = HookGuard.find(HookGuard.PUB_BIND, AWEME, cl, ctx);
        if (aw == null) return;
        Class<?> rc = HookGuard.find(HookGuard.PUB_BIND, ROOT, cl, ctx);
        if (rc == null) return;
        Field a, t;
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
        Field ip = stringField(aw, "ipAttribution");
        Field city = stringField(aw, "city");
        Field dur = durationField(aw);
        int n = 0;
        try {
            a.setAccessible(true);
            t.setAccessible(true);
            if (ip != null) ip.setAccessible(true);
            if (city != null) city.setAccessible(true);
            if (dur != null) dur.setAccessible(true);
            for (Method m : vh.getDeclaredMethods()) {
                if (!m.getName().equals("bind")) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length == 0 || pt[0] != aw) continue;
                if (n == 0) { // 类和字段先放好再挂钩，回调一来就能用
                    rootCls = rc;
                    fAid = a;
                    fTime = t;
                    fIp = ip;
                    fCity = city;
                    fDur = dur;
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
        if (n > 0) HookGuard.ok(HookGuard.PUB_BIND, ip == null ? "没有地点字段，只显示日期" : (city == null ? "没有城市字段，只显示到省" : (dur == null ? "没有 duration 字段，时长要等第一个进度回调才显示" : "")));
        else HookGuard.miss(HookGuard.PUB_BIND, "VideoViewHolder 里没有 bind(Aweme, …)（翻页绑定入口变了，需要重新追踪）");
    }

    /** Aweme.duration（毫秒，TextRec 快照 17857）：本类找不到就往父类找；long / int / Long / Integer 都收。 */
    private static Field durationField(Class<?> aw) {
        for (Class<?> k = aw; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField("duration");
                Class<?> t = f.getType();
                return t == long.class || t == int.class || t == Long.class || t == Integer.class ? f : null;
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable e) {
                return null;
            }
        }
        return null;
    }

    private static Field stringField(Class<?> c, String name) {
        try {
            Field f = c.getDeclaredField(name);
            return f.getType() == String.class ? f : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * pub.anchor：只找类，不挂钩：视频文案控件的两个类（ScrollMentionTextView 和 MentionTextView）、文案容器 AwemeIntroInfoLayout、
     * 页面根 VideoViewHolderRootView（都是类名没混淆，TextRec 40.7.0 的记录）。文案上顶和文案下方位置靠这四个类，
     * 不再靠 pub.bind 装成功（1.30.0 的容器入口依赖 introCls / rootCls，它们原来是 tryBind 顺带记的）。
     * 找不到就不生效，发布信息 / 时长照常。rootCls 同时是 tryBind 的前提，两边找到的是同一个类。
     */
    private static void tryAnchor(ClassLoader cl, ClassLoader ctx) {
        if (HookGuard.settled(HookGuard.PUB_ANCHOR)) return;
        Class<?> m = HookGuard.find(HookGuard.PUB_ANCHOR, MENTION, cl, ctx);
        if (m == null) return;
        Class<?> c = HookGuard.find(HookGuard.PUB_ANCHOR, CAPTION, cl, ctx);
        if (c == null) return;
        Class<?> ic = HookGuard.find(HookGuard.PUB_ANCHOR, INTRO, cl, ctx);
        if (ic == null) return;
        Class<?> rc = HookGuard.find(HookGuard.PUB_ANCHOR, ROOT, cl, ctx);
        if (rc == null) return;
        capMention = m;
        captionCls = c;
        introCls = ic;
        rootCls = rc;
        HookGuard.ok(HookGuard.PUB_ANCHOR, "");
    }

    /**
     * pub.text：挂 DmtTextView 里 setText(CharSequence, TextView$BufferType)（类名、方法名都没混淆，TextRec 40.7.0：
     * 文案的设置栈 DmtTextView.setText ← ScrollMentionTextView.setText ← TextView.setText）。
     * 这个方法是通用文字控件的，回调里先看开关，再比较 this 的类，其他控件的调用在第一次类比较就返回。
     * DmtTextView 找不到走 waitFail（不放弃）；在、但没有这个签名的 setText 才算 miss。
     */
    private static void tryText(ClassLoader cl, ClassLoader ctx) {
        if (HookGuard.settled(HookGuard.PUB)) return;
        if (!capHookOn()) return; // 文案样式、上顶、文案下方位置全关：不挂这个通用文字控件的钩子（没有结论，设置里打开后回到前台再挂）
        HookGuard.hook(HookGuard.PUB, DMT, SET_TEXT, TEXT_HOOK,
                "DmtTextView 里没有 setText(CharSequence, BufferType)（文案控件的设置方法变了，需要重新追踪）", cl, ctx);
    }

    /**
     * cap.draw：挂 MentionTextView 里的 onDraw(Canvas)（TextRec 40.7.0，textrec-tasks-20261021-103536 的 #1：
     * 绘制栈 DmtTextView.onDraw ← MentionTextView.onDraw ← View.draw，类名方法名都没混淆）。
     * TextView.mCurTextColor 必须先找到并放好再挂钩：读不到或类型不是 int、或类里没有 onDraw(Canvas) 都算 miss（立刻放弃并记住）。
     */
    private static void tryDraw(ClassLoader cl, ClassLoader ctx) {
        if (HookGuard.settled(HookGuard.CAP_DRAW)) return;
        if (!styleOn()) return; // 同上：文案样式全关时不挂每帧都会走的绘制钩子
        if (fCurColor == null) {
            try {
                Field f = TextView.class.getDeclaredField("mCurTextColor");
                if (f.getType() != int.class) throw new NoSuchFieldException();
                f.setAccessible(true);
                fCurColor = f;
            } catch (Throwable e) {
                HookGuard.miss(HookGuard.CAP_DRAW, "读不到 TextView.mCurTextColor（系统限制了反射访问），绘制时改色用不了，文案改色只走 setText 那条路");
                return;
            }
        }
        HookGuard.hook(HookGuard.CAP_DRAW, MENTION, ON_DRAW, DRAW_HOOK,
                "MentionTextView 里没有 onDraw(Canvas)（文案的绘制方法变了，需要重新用 TextRec 看绘制栈）", cl, ctx);
    }

    /**
     * name.draw：挂 DmtTextView 里的 onDraw(Canvas)，给视频作者名换字色、画灰色底（1.34.0）。
     * 依据（TextRec 追踪结果，作者名那一行）：作者名是 DmtTextView，资源 id 名 title（可读，没混淆），在 AwemeIntroInfoLayout 里面；
     * 它的绘制栈第 1 层是 DmtTextView#onDraw（和 cap.draw 记录的 DmtTextView.onDraw ← MentionTextView.onDraw 是同一个方法）。
     * 同一行里别的控件（nsj、l+b 之类）名字是混淆的，不用。没有记下追踪的任务编号和抖音版本，换版本后要再看一次作者名的 id 和祖先。
     * 这个钩子是通用文字控件的 onDraw，每个 DmtTextView 每次绘制都会进回调，所以：功能没开（nameColor == 0 且 lineBg <= 0）不挂；
     * 回调里先看开关、再比 id 整数、再看 tag，不是作者名的控件在第三步之内就返回，没有分配、反射、日志。
     * mCurTextColor 读不到时只少「改色」，灰色底照常；DmtTextView 找不到走 waitFail，在但没有 onDraw(Canvas) 才 miss。
     */
    private static void tryName(ClassLoader cl, ClassLoader ctx) {
        if (HookGuard.settled(HookGuard.NAME_DRAW)) return;
        if (!nameOn()) return; // 作者名改色 / 灰色底都没开：不挂（没有结论，设置里打开后回到前台再挂）
        if (fCurColor == null) {
            try {
                Field f = TextView.class.getDeclaredField("mCurTextColor");
                if (f.getType() == int.class) {
                    f.setAccessible(true);
                    fCurColor = f;
                }
            } catch (Throwable ignored) { // 少「改色」，灰色底照常
            }
        }
        HookGuard.hook(HookGuard.NAME_DRAW, DMT, ON_DRAW, NAME_HOOK,
                "DmtTextView 里没有 onDraw(Canvas)（作者名的绘制方法变了，需要重新用 TextRec 看作者名的绘制栈）", cl, ctx);
    }

    private static boolean nameOn() {
        DanmakuConfig c = DanmakuHook.cfg;
        return c.nameColor != 0 || c.lineBg > 0;
    }

    private static boolean isMain() {
        return Looper.myLooper() == Looper.getMainLooper();
    }

    // ------------------------------------------------------------ 钩子

    /**
     * 翻页绑定（主线程，邻近视频会提前 bind；同一页会 bind 两次，处理是幂等的）。
     * after：bind 里文案的 setText 已经发生过了，文案样式的钩子这时认不出的文案控件，下面按页面容器补一次。
     */
    private static final class BindHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam p) {
            try {
                if (!isMain() || p.thisObject == null || p.args == null || p.args.length == 0 || p.args[0] == null) return;
                DanmakuConfig c = DanmakuHook.cfg;
                if (!c.pubAlways && !c.timeAlways && !styleOn()) return; // 全关着：回到前台时已经把现有的行藏起来了
                Field fa = fAid;
                if (fa == null || fTime == null) return;
                Object o = fa.get(p.args[0]);
                if (!(o instanceof String)) return;
                String aid = (String) o;
                View root = rootOf(p.thisObject);
                if (root == null) return;
                Rows r = rowsOfRoot(root);
                Info info = aid.equals(r.aid) ? r.info : readInfo(p.args[0]);
                bindPage(r, aid, info);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 读 Aweme 的发布时间、地点、总时长；没有发布时间返回 null。 */
    private static Info readInfo(Object aw) throws Exception {
        long sec = fTime.getLong(aw);
        if (sec <= 0) return null;
        Field fi = fIp, fc = fCity, fd = fDur;
        Object ip = fi == null ? null : fi.get(aw);
        Object ct = fc == null ? null : fc.get(aw);
        Object dv = fd == null ? null : fd.get(aw);
        long dur = dv instanceof Number ? ((Number) dv).longValue() : -1;
        return new Info(sec, ip instanceof String ? (String) ip : null, ct instanceof String ? (String) ct : null, dur);
    }

    /**
     * 文案控件刚设了文字：是视频文案（在 AwemeIntroInfoLayout 里面）就接手它的容器（第一次认出时），再重设一次样式。
     * 其他 MentionTextView（评论等）不动。
     */
    private static final class TextHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam p) {
            try {
                Object self = p.thisObject;
                if (!(self instanceof TextView)) return;
                Class<?> sk = self.getClass();
                if (sk != capMention && sk != captionCls) return;
                if (!capHookOn() || !isMain()) return;
                TextView t = (TextView) self;
                if (t.getTag(CAP_TAG) == null) {
                    ViewGroup intro = introOf(t);
                    if (intro == null) return;
                    markCaption(t);
                    takeIntro(intro);
                }
                if (styleOn()) styleCaption(t);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 画文案之前换色、之后换回来。只处理打过 CAP_TAG 的文案控件；任何异常都不能抛回抖音。 */
    private static final class DrawHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            drawSwapped = false;
            try {
                DanmakuConfig cf = DanmakuHook.cfg;
                if (cf.lineBg <= 0 && cf.capColor == 0) return; // 都没开：评论等所有 MentionTextView 的每次绘制在这里就返回
                Object self = p.thisObject;
                if (!(self instanceof TextView) || ((View) self).getTag(CAP_TAG) == null) return;
                int bg = cf.lineBg;
                if (bg > 0 && p.args != null && p.args.length > 0 && p.args[0] instanceof android.graphics.Canvas) {
                    LineBg.drawBars((android.graphics.Canvas) p.args[0], (TextView) self, bg); // 文字灰色底：画在字之前
                }
                int want = cf.capColor;
                Field f = fCurColor;
                if (want == 0 || f == null) return;
                int old = f.getInt(self);
                if (old == want) return;
                drawSaved = old;
                f.setInt(self, want);
                drawSwapped = true;
            } catch (Throwable ignored) {
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam p) {
            if (!drawSwapped) return;
            drawSwapped = false;
            try {
                Field f = fCurColor;
                if (f != null && p.thisObject != null) f.setInt(p.thisObject, drawSaved);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 画作者名之前换字色、画灰色底，画完换回来。DmtTextView 的每次绘制都会进来，所以顺序是：
     * 开关 → 是不是 TextView → id 整数 → tag（第一次才沿父级找容器）。只处理 AwemeIntroInfoLayout 里 id=title 的那个控件。
     * 任何异常都不能抛回抖音。
     */
    private static final class NameHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            nameSwapped = false;
            try {
                DanmakuConfig cf = DanmakuHook.cfg;
                if (cf.nameColor == 0 && cf.lineBg <= 0) return;
                Object self = p.thisObject;
                if (!(self instanceof TextView)) return;
                TextView t = (TextView) self;
                int id = nameId;
                if (id == 0) {
                    if (nameIdTried) return;
                    nameIdTried = true; // 只查一次：资源名 title 在抖音自己的包里
                    nameId = id = t.getResources().getIdentifier("title", "id", t.getContext().getPackageName());
                    if (id == 0) return;
                }
                if (t.getId() != id) return;
                Object tag = t.getTag(NAME_TAG);
                if (tag == null) {
                    if (introCls == null) return; // 容器类还没找到：先不下结论，下次绘制再认
                    tag = introOf(t) != null ? Boolean.TRUE : Boolean.FALSE; // 不在视频信息容器里的 title（别处的标题）打 FALSE，以后一次 tag 比较就返回
                    t.setTag(NAME_TAG, tag);
                }
                if (tag != Boolean.TRUE) return;
                int bg = cf.lineBg;
                if (bg > 0 && p.args != null && p.args.length > 0 && p.args[0] instanceof android.graphics.Canvas) {
                    LineBg.drawBars((android.graphics.Canvas) p.args[0], t, bg); // 灰色底：画在字之前
                }
                int want = cf.nameColor;
                Field f = fCurColor;
                if (want == 0 || f == null) return;
                int old = f.getInt(t);
                if (old == want) return;
                nameSaved = old;
                f.setInt(t, want);
                nameSwapped = true;
            } catch (Throwable ignored) {
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam p) {
            if (!nameSwapped) return;
            nameSwapped = false;
            try {
                Field f = fCurColor;
                if (f != null && p.thisObject != null) f.setInt(p.thisObject, nameSaved);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 回到前台：这一页的容器里的作者名重画一次（设置改了马上生效，包括改回「不改」和关灰色底）。没有分配，只在回到前台时走。 */
    private static void invalidateName(View v, int depth) {
        if (v.getId() == nameId && v instanceof TextView) v.invalidate();
        if (depth <= 0 || !(v instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0, n = g.getChildCount(); i < n; i++) invalidateName(g.getChildAt(i), depth - 1);
    }

    // ------------------------------------------------------------ 页面：根、容器、登记

    /**
     * 这一页的根：holder 里值是 VideoViewHolderRootView 的那个字段（字段名是混淆的，不认名字，按运行时值的类型找；
     * 每个 holder 类只扫一次，之后读缓存的 Field）。找不到返回 null。
     */
    private static View rootOf(Object holder) {
        Class<?> rc = rootCls;
        if (rc == null) return null;
        Class<?> hk = holder.getClass();
        Field f = ROOT_FIELD.get(hk);
        if (f != null) {
            try {
                Object v = f.get(holder);
                if (rc.isInstance(v)) return (View) v;
            } catch (Throwable ignored) {
            }
            return null;
        }
        if (NO_ROOT.contains(hk)) return null;
        for (Class<?> c = hk; c != null && c != Object.class; c = c.getSuperclass()) {
            Field[] fs;
            try {
                fs = c.getDeclaredFields();
            } catch (Throwable t) {
                continue;
            }
            for (Field x : fs) {
                Class<?> ft = x.getType();
                if (Modifier.isStatic(x.getModifiers()) || ft.isPrimitive() || ft == String.class) continue;
                try {
                    x.setAccessible(true);
                    Object v = x.get(holder);
                    if (rc.isInstance(v)) {
                        ROOT_FIELD.put(hk, x);
                        return (View) v;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        NO_ROOT.add(hk);
        return null;
    }

    private static Rows rowsOfRoot(View root) {
        Object o = root.getTag(ROOT_TAG);
        if (o instanceof Rows) return (Rows) o;
        Rows r = new Rows();
        r.root = root;
        root.setTag(ROOT_TAG, r);
        root.addOnLayoutChangeListener(ROOT_LISTENER); // 每个根只挂一次（Rows 也只建一次）
        return r;
    }

    /** 往下找文案控件（两个类之一）。 */
    private static TextView findCap(View v, int depth) {
        Class<?> k = v.getClass();
        if ((k == captionCls || k == capMention) && v instanceof TextView) return (TextView) v;
        if (depth <= 0 || !(v instanceof ViewGroup)) return null;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0, n = g.getChildCount(); i < n; i++) {
            TextView f = findCap(g.getChildAt(i), depth - 1);
            if (f != null) return f;
        }
        return null;
    }

    /** t 所在的 AwemeIntroInfoLayout（往上最多 8 层）；不在任何容器里（评论等其他 MentionTextView）返回 null。 */
    private static ViewGroup introOf(View t) {
        Class<?> ic = introCls;
        if (ic == null) return null;
        ViewParent par = t.getParent();
        for (int d = 0; d < 8 && par != null; d++) {
            if (ic.isInstance(par)) return (ViewGroup) par;
            par = par.getParent();
        }
        return null;
    }

    /**
     * 这个容器所在的页面：往上已经有登记的就用它，否则往上找最近的 VideoViewHolderRootView 登记一页（bind 随后会补上 aid / 发布信息）。
     * 找不到根（类没找到、容器不在页面里）返回 null：上顶照常，文案下方位置算不出来就退回自由位置。
     */
    private static Rows rowsOfIntro(View intro) {
        Rows r = rowsAbove(intro);
        if (r != null) return r;
        Class<?> rc = rootCls;
        if (rc == null) return null;
        ViewParent p = intro.getParent();
        for (int d = 0; d < 30 && p != null; d++) {
            if (rc.isInstance(p)) return rowsOfRoot((View) p);
            p = p.getParent();
        }
        return null;
    }

    /** 这一页现在绑的是 aid：登记，套空白行、文案样式和两个浮层（容器由文案控件那边接手，见 takeIntro）。 */
    private static void bindPage(Rows r, String aid, Info info) {
        if (!aid.equals(r.aid)) {
            if (r.aid != null) {
                WeakReference<Rows> w = PAGES.get(r.aid);
                if (w != null && w.get() == r) PAGES.remove(r.aid);
            }
            r.aid = aid;
            r.info = info;
            r.lastSec = -1;
            r.lastDsec = -2;
            PAGES.put(aid, new WeakReference<>(r));
            if (PAGES.size() > MAX_PAGES) trimPages();
        }
        register(r);
        applyAll(r);
    }

    private static void trimPages() {
        java.util.Iterator<java.util.Map.Entry<String, WeakReference<Rows>>> it = PAGES.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().get() == null) it.remove();
        }
    }

    /** 登记这一页（给「回到前台重套」和位置浮窗用）。 */
    private static void register(Rows r) {
        if (r.registered) return;
        r.registered = true;
        LIVE.add(new WeakReference<>(r));
        if (LIVE.size() > MAX_PAGES) {
            for (int i = LIVE.size() - 1; i >= 0; i--) if (LIVE.get(i).get() == null) LIVE.remove(i);
        }
    }

    /** 容器里的文案控件（找到过就用缓存的，没找到就再找；类没找到或容器里没有就是 null）。 */
    private static TextView capOf(Rows r) {
        if (r.cap != null) return r.cap;
        if (r.intro != null && (capMention != null || captionCls != null)) r.cap = findCap(r.intro, 6);
        return r.cap;
    }

    /** 按现在的配置套一遍这一页：文案样式和空白行（要容器）、发布信息和时长浮层（只要根）。主线程调用。 */
    private static void applyAll(Rows r) {
        if (r.intro != null) {
            TextView cap = capOf(r);
            if (cap != null && styleOn()) {
                if (cap.getTag(CAP_TAG) == null) markCaption(cap);
                styleCaption(cap);
            }
        }
        updatePub(r);
        updateTimeInit(r);
    }

    /** 回到前台（配置刚重读过）：见过的页面按新配置（开关、模板、字号、颜色、空白行高度、位置）重新套一遍。 */
    static void onResume(Activity a) {
        try {
            if (!isMain()) return;
            for (int i = LIVE.size() - 1; i >= 0; i--) {
                Rows r = LIVE.get(i).get();
                if (r == null) {
                    LIVE.remove(i);
                    continue;
                }
                applyAll(r);
                if (nameId != 0 && r.intro != null) invalidateName(r.intro, 8);
            }
            for (int i = INTROS.size() - 1; i >= 0; i--) {
                ViewGroup g = INTROS.get(i).get();
                if (g == null) {
                    INTROS.remove(i);
                    continue;
                }
                applyBlank(g); // 上顶高度 / 开关变了：见过的容器按新配置重套
            }
            if (DanmakuHook.cfg.vidAdjust) VidPosPanel.show(a); // 位置调节模式：盖上浮窗
            else VidPosPanel.end();
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ 浮层的位置（全屏任意，单位 dp，左 / 上）

    /** 配置里这个浮层的位置：-1 = 默认（左 12 dp；上 = 页面高度的 60% / 65%）。 */
    private static void place(Rows r, View v, boolean pub) {
        View root = r.root;
        if (root == null) return;
        int rw = root.getWidth(), rh = root.getHeight(), w = v.getWidth(), h = v.getHeight();
        if (rw <= 0 || rh <= 0 || w <= 0 || h <= 0) return; // 还没布局，布局完监听会再来
        DanmakuConfig c = DanmakuHook.cfg;
        float d = v.getResources().getDisplayMetrics().density;
        int px = pub ? c.pubX : c.timeX, py = pub ? c.pubY : c.timeY;
        float x = px >= 0 ? px * d : 12f * d;
        float y = py >= 0 ? py * d : rh * (pub ? 0.60f : 0.65f);
        if (r.slotOk && anchored(c, pub)) { // 放在文案下方：发布地在上，时长接在它下面（两个都选时叠着放）
            x = r.slotX;
            y = r.slotY;
            if (!pub && anchored(c, true) && r.pub != null && r.pub.getVisibility() == View.VISIBLE) y += r.pub.getHeight();
        }
        x = Math.max(0f, Math.min(x, rw - w));
        y = Math.max(0f, Math.min(y, rh - h));
        if (v.getTranslationX() != x) v.setTranslationX(x);
        if (v.getTranslationY() != y) v.setTranslationY(y);
    }

    private static void placeAll(Rows r) {
        if (r.pub != null) place(r, r.pub, true);
        if (r.time != null) place(r, r.time, false);
    }

    /** 位置浮窗里位置变了：所有见过的页面的两个浮层都重新放一次（邻近页的也一起，划过去就在对的位置）。 */
    static void reapplyPositions() {
        try {
            for (int i = LIVE.size() - 1; i >= 0; i--) {
                Rows r = LIVE.get(i).get();
                if (r == null) {
                    LIVE.remove(i);
                    continue;
                }
                placeAll(r);
            }
        } catch (Throwable ignored) {
        }
    }

    private static final int[] LOC = new int[2];

    /**
     * 位置浮窗用：屏幕上这一页的这个浮层现在的位置（dp，左 / 上，已经套过默认值和边界）；
     * 屏幕上没有（开关没开、还没布局）返回 null。
     */
    static float[] shownPos(boolean pub) {
        for (int i = LIVE.size() - 1; i >= 0; i--) {
            Rows r = LIVE.get(i).get();
            if (r == null) {
                LIVE.remove(i);
                continue;
            }
            View root = r.root, v = pub ? r.pub : r.time;
            if (root == null || v == null || v.getVisibility() != View.VISIBLE || !root.isAttachedToWindow() || v.getWidth() <= 0) continue;
            root.getLocationOnScreen(LOC);
            if (Math.abs(LOC[0]) > root.getWidth() / 2 || Math.abs(LOC[1]) > root.getHeight() / 2) continue; // 邻近页
            float d = v.getResources().getDisplayMetrics().density;
            return new float[]{v.getTranslationX() / d, v.getTranslationY() / d};
        }
        return null;
    }



    /**
     * 地级行政区划代码（前 4 位）→ 城市简称。Aweme.city 是 6 位代码（450100 = 南宁），取前 4 位查。
     * 只收大陆地级单位；表里没有的（省直辖县级、港澳台、新代码）查不到，就只显示省。格式 ";4 位代码名字" 一个接一个，末尾有分号。
     */
    private static final String CITIES = ";1101北京;1201天津"
            + ";1301石家庄;1302唐山;1303秦皇岛;1304邯郸;1305邢台;1306保定;1307张家口;1308承德;1309沧州;1310廊坊;1311衡水"
            + ";1401太原;1402大同;1403阳泉;1404长治;1405晋城;1406朔州;1407晋中;1408运城;1409忻州;1410临汾;1411吕梁"
            + ";1501呼和浩特;1502包头;1503乌海;1504赤峰;1505通辽;1506鄂尔多斯;1507呼伦贝尔;1508巴彦淖尔;1509乌兰察布;1522兴安盟;1525锡林郭勒盟;1529阿拉善盟"
            + ";2101沈阳;2102大连;2103鞍山;2104抚顺;2105本溪;2106丹东;2107锦州;2108营口;2109阜新;2110辽阳;2111盘锦;2112铁岭;2113朝阳;2114葫芦岛"
            + ";2201长春;2202吉林;2203四平;2204辽源;2205通化;2206白山;2207松原;2208白城;2224延边"
            + ";2301哈尔滨;2302齐齐哈尔;2303鸡西;2304鹤岗;2305双鸭山;2306大庆;2307伊春;2308佳木斯;2309七台河;2310牡丹江;2311黑河;2312绥化;2327大兴安岭"
            + ";3101上海"
            + ";3201南京;3202无锡;3203徐州;3204常州;3205苏州;3206南通;3207连云港;3208淮安;3209盐城;3210扬州;3211镇江;3212泰州;3213宿迁"
            + ";3301杭州;3302宁波;3303温州;3304嘉兴;3305湖州;3306绍兴;3307金华;3308衢州;3309舟山;3310台州;3311丽水"
            + ";3401合肥;3402芜湖;3403蚌埠;3404淮南;3405马鞍山;3406淮北;3407铜陵;3408安庆;3410黄山;3411滁州;3412阜阳;3413宿州;3415六安;3416亳州;3417池州;3418宣城"
            + ";3501福州;3502厦门;3503莆田;3504三明;3505泉州;3506漳州;3507南平;3508龙岩;3509宁德"
            + ";3601南昌;3602景德镇;3603萍乡;3604九江;3605新余;3606鹰潭;3607赣州;3608吉安;3609宜春;3610抚州;3611上饶"
            + ";3701济南;3702青岛;3703淄博;3704枣庄;3705东营;3706烟台;3707潍坊;3708济宁;3709泰安;3710威海;3711日照;3713临沂;3714德州;3715聊城;3716滨州;3717菏泽"
            + ";4101郑州;4102开封;4103洛阳;4104平顶山;4105安阳;4106鹤壁;4107新乡;4108焦作;4109濮阳;4110许昌;4111漯河;4112三门峡;4113南阳;4114商丘;4115信阳;4116周口;4117驻马店"
            + ";4201武汉;4202黄石;4203十堰;4205宜昌;4206襄阳;4207鄂州;4208荆门;4209孝感;4210荆州;4211黄冈;4212咸宁;4213随州;4228恩施"
            + ";4301长沙;4302株洲;4303湘潭;4304衡阳;4305邵阳;4306岳阳;4307常德;4308张家界;4309益阳;4310郴州;4311永州;4312怀化;4313娄底;4331湘西"
            + ";4401广州;4402韶关;4403深圳;4404珠海;4405汕头;4406佛山;4407江门;4408湛江;4409茂名;4412肇庆;4413惠州;4414梅州;4415汕尾;4416河源;4417阳江;4418清远;4419东莞;4420中山;4451潮州;4452揭阳;4453云浮"
            + ";4501南宁;4502柳州;4503桂林;4504梧州;4505北海;4506防城港;4507钦州;4508贵港;4509玉林;4510百色;4511贺州;4512河池;4513来宾;4514崇左"
            + ";4601海口;4602三亚;4603三沙;4604儋州"
            + ";5001重庆;5002重庆"
            + ";5101成都;5103自贡;5104攀枝花;5105泸州;5106德阳;5107绵阳;5108广元;5109遂宁;5110内江;5111乐山;5113南充;5114眉山;5115宜宾;5116广安;5117达州;5118雅安;5119巴中;5120资阳;5132阿坝;5133甘孜;5134凉山"
            + ";5201贵阳;5202六盘水;5203遵义;5204安顺;5205毕节;5206铜仁;5223黔西南;5226黔东南;5227黔南"
            + ";5301昆明;5303曲靖;5304玉溪;5305保山;5306昭通;5307丽江;5308普洱;5309临沧;5323楚雄;5325红河;5326文山;5328西双版纳;5329大理;5331德宏;5333怒江;5334迪庆"
            + ";5401拉萨;5402日喀则;5403昌都;5404林芝;5405山南;5406那曲;5425阿里"
            + ";6101西安;6102铜川;6103宝鸡;6104咸阳;6105渭南;6106延安;6107汉中;6108榆林;6109安康;6110商洛"
            + ";6201兰州;6202嘉峪关;6203金昌;6204白银;6205天水;6206武威;6207张掖;6208平凉;6209酒泉;6210庆阳;6211定西;6212陇南;6229临夏;6230甘南"
            + ";6301西宁;6302海东;6322海北;6323黄南;6325海南;6326果洛;6327玉树;6328海西"
            + ";6401银川;6402石嘴山;6403吴忠;6404固原;6405中卫"
            + ";6501乌鲁木齐;6502克拉玛依;6504吐鲁番;6505哈密;6523昌吉;6527博尔塔拉;6528巴音郭楞;6529阿克苏;6530克孜勒苏;6531喀什;6532和田;6540伊犁;6542塔城;6543阿勒泰;";


    // ------------------------------------------------------------ 文案样式

    private static boolean styleOn() {
        DanmakuConfig c = DanmakuHook.cfg;
        return c.capColor != 0 || c.lineBg > 0;
    }

    /** 文案的 setText 钩子要不要挂：样式、上顶、文案下方位置，有一个开着就要（上顶和位置也靠它接手容器）。 */
    private static boolean capHookOn() {
        DanmakuConfig c = DanmakuHook.cfg;
        return c.capColor != 0 || c.lineBg > 0 || c.blankH != 0 || c.barOn;
    }

    /** 认出是视频文案：打 tag（cap.draw 只改打过 tag 的），挂一次布局监听（展开 / 收起让布局变了，重设样式）。 */
    private static void markCaption(TextView t) {
        t.setTag(CAP_TAG, Boolean.TRUE);
        if (t.getTag(CAPW_TAG) == null) {
            t.setTag(CAPW_TAG, Boolean.TRUE);
            t.addOnLayoutChangeListener(CAP_LISTENER);
        }
    }

    /** 视频文案的字色（有变化才设，布局变化时反复调用也没有开销）。 */
    private static void styleCaption(View v) {
        DanmakuConfig c = DanmakuHook.cfg;
        if (c.capColor == 0 && c.lineBg == 0) return;
        if (!(v instanceof TextView)) return;
        TextView t = (TextView) v;
        if (c.capColor != 0 && t.getCurrentTextColor() != c.capColor) t.setTextColor(c.capColor);
    }

    // ------------------------------------------------------------ 空白行（在容器里，文案后面）

    /** AwemeIntroInfoLayout 上挂的东西（tag，主线程用）：登记过 / 被我们上移过。 */
    private static final int BLANK_TAG = 0x7e0b0c08, INTRO_TAG = 0x7e0b0c09, CAPA_TAG = 0x7e0b0c0a;
    private static final int MAX_INTROS = 16;
    /** 见过的 AwemeIntroInfoLayout（弱引用，主线程用）；每个视频页一个，邻近页的也在。回到前台时按新配置重套上顶高度。 */
    private static final ArrayList<WeakReference<ViewGroup>> INTROS = new ArrayList<>();
    /**
     * 接手一个容器（上顶 + 文案下方位置）：第一次见就登记（回到前台按新配置重套）、挂布局监听，然后套一遍。唯一入口是 takeIntro。
     */
    private static void adopt(ViewGroup intro) {
        if (intro.getTag(INTRO_TAG) == null) {
            intro.setTag(INTRO_TAG, Boolean.TRUE);
            intro.addOnLayoutChangeListener(INTRO_LISTENER); // 布局变了（展开 / 收起文案）位置跟着重算
            INTROS.add(new WeakReference<>(intro));
            if (INTROS.size() > MAX_INTROS) {
                for (int i = INTROS.size() - 1; i >= 0; i--) if (INTROS.get(i).get() == null) INTROS.remove(i);
            }
        }
        applyBlank(intro);
    }

    /** 文案 → 容器：这一页没登记过就先按容器往上的页面根登记一页（算文案下方位置要用），然后接手容器。 */
    private static void takeIntro(ViewGroup intro) {
        if (intro.getTag(INTRO_TAG) == null) rowsOfIntro(intro);
        adopt(intro);
    }

    /**
     * 文案上顶（原来叫「空白行」）：把整个 AwemeIntroInfoLayout 往上平移 blankH × 2 像素（-1 = 默认，等于 37 sp 的字高；0 = 不动），
     * 作者声明、文案一起上移，下面留出空位。用 translationY（只改绘制和触摸位置，不引起重新布局），所以不靠容器是什么布局、会不会重新测量。
     * 只动自己动过的容器：BLANK_TAG 记着我们设过，0 时才还原，抖音自己对这个容器的平移（如果有）不去碰。
     */
    private static void applyBlank(ViewGroup intro) {
        int bh = DanmakuHook.cfg.blankH;
        boolean ours = intro.getTag(BLANK_TAG) != null;
        if (bh == 0) {
            if (ours) {
                intro.setTranslationY(0f);
                intro.setTag(BLANK_TAG, null);
            }
            applyAnchor(intro);
            return;
        }
        float h = bh < 0
                ? TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, DanmakuConfig.BLANK_DEFAULT_SP, intro.getResources().getDisplayMetrics())
                : bh * 2f;
        float ty = -Math.round(h);
        if (intro.getTranslationY() != ty) intro.setTranslationY(ty);
        if (!ours) intro.setTag(BLANK_TAG, Boolean.TRUE);
        applyAnchor(intro);
    }

    /** 容器自己布局了：色条按它新的位置重放。 */
    private static final View.OnLayoutChangeListener INTRO_LISTENER = new View.OnLayoutChangeListener() {
        @Override
        public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
            try {
                if (l == ol && t == ot && r == or && b == ob) return;
                if (v instanceof ViewGroup) applyAnchor((ViewGroup) v);
            } catch (Throwable ignored) {
            }
        }
    };

    /** 往上走到挂着 Rows 的页面根；没有（容器不在已登记的页面里）返回 null。 */
    private static Rows rowsAbove(View v) {
        View x = v;
        for (int d = 0; d < 30; d++) {
            Object o = x.getTag(ROOT_TAG);
            if (o instanceof Rows) return (Rows) o;
            ViewParent p = x.getParent();
            if (!(p instanceof View)) return null;
            x = (View) p;
        }
        return null;
    }

    /**
     * 文案下方的固定位置（1.28.8，替换 1.28.6 / 1.28.7 的色条，色条已经没有了）：容器（上顶之后）的左边、底边，在页面根里的位置，
     * 也就是上顶留出的空位的顶上。沿父级累加 left / top / 平移算出，存在 Rows 里；被选中的浮层（发布地 / 时长）不用自己的自由位置，
     * 直接放在这里（见 place）。没上机，算不出来（容器不在这个根里、还没布局）就不用这个位置，退回自由位置。
     */
    private static void applyAnchor(ViewGroup intro) {
        Rows r = rowsAbove(intro);
        if (r == null) return;
        View root = r.root;
        if (root == null) return;
        if (r.intro != intro) { // 这一页的容器就是它（pub.bind 时还没找到的补上）
            r.intro = intro;
            r.cap = null;
        }
        boolean ok = intro.getWidth() > 0 && intro.getHeight() > 0;
        float x = 0f, y = 0f;
        if (ok) {
            View v = intro;
            while (v != root) {
                x += v.getLeft() + v.getTranslationX();
                y += v.getTop() + v.getTranslationY();
                ViewParent p = v.getParent();
                if (!(p instanceof View)) {
                    ok = false; // 不在这个根里
                    break;
                }
                v = (View) p;
            }
        }
        y += intro.getHeight();
        if (ok) { // 左边对齐文案的字：取文案控件文字的左边（在页面根里的位置），再减掉浮层自己的内边距；文案还没布局 / 找不到就用容器的左边
            TextView cap = capOf(r);
            if (cap != null && cap.getWidth() > 0) {
                if (cap.getTag(CAPA_TAG) == null) { // 文案布局变了（展开 / 收起、换字）要重新对齐
                    cap.setTag(CAPA_TAG, Boolean.TRUE);
                    cap.addOnLayoutChangeListener(CAPA_LISTENER);
                }
                float cx = cap.getTotalPaddingLeft();
                View v = cap;
                while (v != root && v != null) {
                    cx += v.getLeft() + v.getTranslationX();
                    ViewParent p = v.getParent();
                    v = p instanceof View ? (View) p : null;
                }
                if (v == root) x = cx;
            }
            x -= LineBg.pad(root.getContext());
        }
        if (ok == r.slotOk && (!ok || (x == r.slotX && y == r.slotY))) return; // 没变
        r.slotOk = ok;
        r.slotX = x;
        r.slotY = y;
        placeAll(r);
    }

    /** 文案布局变了：重新对齐文案下方的位置。 */
    private static final View.OnLayoutChangeListener CAPA_LISTENER = new View.OnLayoutChangeListener() {
        @Override
        public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
            try {
                if (l == ol && t == ot && r == or && b == ob) return;
                Rows rs = rowsAbove(v);
                if (rs != null && rs.intro != null) applyAnchor(rs.intro);
            } catch (Throwable ignored) {
            }
        }
    };

    /** 这个浮层是不是放在文案下方（开关开着、选中了它）。 */
    private static boolean anchored(DanmakuConfig c, boolean pub) {
        return c.barOn && (c.barSel & (pub ? 1 : 2)) != 0;
    }

    // ------------------------------------------------------------ 发布信息浮层（盖在页面根上）

    /** 这一页的浮层：没有就建（加在页面根最后一个，不改已有子控件的下标）。页面根不是 ViewGroup 返回 null。 */
    private static TextView overlay(Rows r, boolean pub) {
        TextView t = pub ? r.pub : r.time;
        View root = r.root;
        if (!(root instanceof ViewGroup)) return null;
        if (t != null && t.getParent() == root) return t;
        detach(t);
        if (t == null) {
            t = new LineBg.Row(root.getContext());
            float d = t.getResources().getDisplayMetrics().density;
            t.setShadowLayer(5f * d, 0f, 0f, 0xFF000000); // 黑色描边感，亮暗背景都看得清
            t.setClickable(false);
            t.setFocusable(false);
            t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            if (!pub) {
                t.setTypeface(Typeface.DEFAULT_BOLD);
                t.setIncludeFontPadding(false);
                try {
                    t.setFontFeatureSettings("tnum"); // 等宽数字，秒数跳动时不抖
                } catch (Throwable ignored) {
                }
                r.timeSz = -1;
                r.timeCol = 0;
            }
            t.addOnLayoutChangeListener(OVL_LISTENER);
            if (pub) r.pub = t;
            else r.time = t;
        }
        ((ViewGroup) root).addView(t, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));
        return t;
    }

    /** 更新发布信息浮层：开关开着、这一页有发布时间 → 套模板显示（字号、颜色用配置）；否则藏起来。不截断，放不下由 TextView 自己换行。 */
    private static void updatePub(Rows r) {
        DanmakuConfig c = DanmakuHook.cfg;
        String line = c.pubAlways && r.info != null ? lineOf(r.info, c.pubFormat) : null;
        TextView t = r.pub;
        if (line == null) {
            if (t != null && t.getVisibility() != View.GONE) t.setVisibility(View.GONE);
            return;
        }
        t = overlay(r, true);
        if (t == null) return;
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, c.pubSize);
        int color = c.pubColor != 0 ? c.pubColor : ROW_COLOR;
        if (t.getCurrentTextColor() != color) t.setTextColor(color);
        if (!line.contentEquals(t.getText())) t.setText(line);
        if (t.getVisibility() != View.VISIBLE) t.setVisibility(View.VISIBLE);
        t.invalidate(); // 灰色底透明度可能变了
        place(r, t, true);
    }

    /** 这个视频的发布信息：套模板，结果按模板缓存在 Info 里，模板变了才重算。 */
    private static String lineOf(Info i, String tpl) {
        String l = i.line;
        if (l == null || i.lineTpl == null || !i.lineTpl.equals(tpl)) {
            i.line = l = PubFormat.render(tpl, i.sec, i.ip, cityName(i.city));
            i.lineTpl = tpl;
        }
        return l;
    }

    // ------------------------------------------------------------ 视频时长浮层

    /**
     * 这一页刚绑了视频（或配置变了）：时长浮层按配置套一遍。关着就藏起来；开着、还没有进度时，用 Aweme.duration
     * 先显示「00:00 / 总时长」（没有 duration 就等第一个进度回调）。
     */
    private static void updateTimeInit(Rows r) {
        TextView t = r.time;
        if (!DanmakuHook.cfg.timeAlways) {
            if (t != null && t.getVisibility() != View.GONE) t.setVisibility(View.GONE);
            return;
        }
        if (r.lastSec >= 0) {
            showTime(r, r.lastSec, r.lastDsec); // 配置变了：按新的字号、颜色重新套
            return;
        }
        Info i = r.info;
        if (i != null && i.durMs >= 1000 && i.durMs <= 36000000L) {
            showTime(r, 0, i.durMs / 1000);
        } else if (t != null && t.getVisibility() != View.GONE) {
            t.setVisibility(View.GONE);
        }
    }

    private static void showTime(Rows r, long sec, long dsec) {
        TextView t = timeRow(r);
        if (t == null) return;
        String text = fmt(sec) + " / " + (dsec >= 0 ? fmt(dsec) : "--:--");
        if (r.timeLocked && text.length() != r.timeLen) unlockTime(r, t); // 字符数变了（例如进了 1 小时）：放开，重新量
        r.timeLen = text.length();
        if (!text.contentEquals(t.getText())) t.setText(text);
        if (t.getVisibility() != View.VISIBLE) t.setVisibility(View.VISIBLE);
        r.lastSec = sec;
        r.lastDsec = dsec;
    }

    /** 这一页的时长浮层（没有就建），并按配置设字号、颜色、位置。 */
    private static TextView timeRow(Rows r) {
        TextView t = overlay(r, false);
        if (t == null) return null;
        DanmakuConfig c = DanmakuHook.cfg;
        if (c.timeSize != r.timeSz) {
            r.timeSz = c.timeSize;
            if (r.timeLocked) unlockTime(r, t); // 字号变了：放开，重新量
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, c.timeSize);
        }
        int color = c.timeColor != 0 ? c.timeColor : TIME_COLOR;
        if (color != r.timeCol) {
            r.timeCol = color;
            t.setTextColor(color);
        }
        t.invalidate(); // 灰色底透明度可能变了
        place(r, t, false);
        return t;
    }

    /**
     * 时长浮层每秒换一次字，宽高都是 WRAP_CONTENT 时每次换字都会让整个窗口重新布局一遍。第一次布局完（文字字符数没变）就把它锁成固定大小：
     * 宽度按「所有数字都换成 8」的文字量一次（数字不等宽的字体也放得下），之后同样字符数的换字只重绘、不重新布局。
     * 字符数变了 / 字号变了就放开（WRAP_CONTENT）重新量，再锁。
     */
    private static void lockTime(Rows r, View v) {
        if (r.timeLocked || !(v instanceof TextView) || v.getWidth() <= 0 || v.getHeight() <= 0 || v.getVisibility() != View.VISIBLE) return;
        TextView t = (TextView) v;
        CharSequence cs = t.getText();
        if (cs == null || cs.length() != r.timeLen) return;
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp == null) return;
        char[] ch = cs.toString().toCharArray();
        for (int i = 0; i < ch.length; i++) if (ch[i] >= '0' && ch[i] <= '9') ch[i] = '8';
        int w = Math.max(v.getWidth(), (int) Math.ceil(t.getPaint().measureText(new String(ch))) + t.getTotalPaddingLeft() + t.getTotalPaddingRight());
        lp.width = w;
        lp.height = v.getHeight();
        r.timeLocked = true;
        v.setLayoutParams(lp);
    }

    private static void unlockTime(Rows r, View v) {
        r.timeLocked = false;
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp == null) return;
        lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        v.setLayoutParams(lp);
    }

    /**
     * 播放进度（TimeHook 的 time.progress 转进来，主线程，每个事件会经过几个包装监听器重复到达）：按视频 id（= Aweme.aid）找到页面，更新这一页的时长浮层。
     * 认不出的 id（不是 feed 里的视频）什么都不做。bind 时容器还没在根里的页面，改由页面根第一次布局时（ROOT_LISTENER）补找。
     */
    static void onProgress(String id, long pos, long dur) {
        if (id == null || !DanmakuHook.cfg.timeAlways) return; // 没开时长：每个回调在这里就返回，不查表
        WeakReference<Rows> w = PAGES.get(id);
        Rows r = w == null ? null : w.get();
        if (r == null) return;
        long sec = pos > 0 ? pos / 1000 : 0;
        long dsec = dur > 0 ? dur / 1000 : -1;
        if (dsec >= 0 && sec > dsec) sec = dsec;
        TextView t = r.time;
        if (t != null && sec == r.lastSec && dsec == r.lastDsec && t.getVisibility() == View.VISIBLE) return; // 秒数没变
        showTime(r, sec, dsec);
    }

    private static String fmt(long s) {
        long h = s / 3600, m = (s / 60) % 60, sec = s % 60;
        StringBuilder b = new StringBuilder(8);
        if (h > 0) b.append(h).append(':');
        if (m < 10) b.append('0');
        b.append(m).append(':');
        if (sec < 10) b.append('0');
        b.append(sec);
        return b.toString();
    }

    private static void detach(View v) {
        if (v != null && v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
    }

    // ------------------------------------------------------------ 城市

    /** Aweme.city（6 位行政区划代码，如 "450100"）→ 城市简称；不是 6 位数字或表里没有就返回 null。 */
    private static String cityName(String code) {
        if (code == null || code.length() != 6) return null;
        for (int i = 0; i < 6; i++) {
            char ch = code.charAt(i);
            if (ch < '0' || ch > '9') return null;
        }
        int i = CITIES.indexOf(";" + code.substring(0, 4));
        if (i < 0) return null;
        int s0 = i + 5;
        int e = CITIES.indexOf(';', s0);
        return e > s0 ? CITIES.substring(s0, e) : null;
    }
}
