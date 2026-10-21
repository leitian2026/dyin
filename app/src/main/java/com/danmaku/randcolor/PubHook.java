package com.danmaku.randcolor;

import android.app.Activity;
import android.graphics.Rect;
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
 *  - 字段名的依据（TextRec 字段快照，textrec-tasks-20261020-150752 的 Aweme#toString 记录，this 就是 Aweme）：
 *      this.aid = "7698029777320581952"（String）
 *      this.createTime = 1792381800（数值，秒级，= 2026-10-19 11:50:00 +08:00）
 *      this.ipAttribution = "广西"（String，省）
 *      this.city = "450100"（String，6 位行政区划代码 = 南宁市；author.city 是空串）
 *    字段在安装阶段按名字找好并缓存（找不到 aid / createTime 就 miss，不猜）；回调里只做取值。
 *  - city 是代码不是名字，转城市名靠下面的 CITIES 表（只有 4 位地级代码；查不到就只显示省）。
 *
 * 位置（钩子 pub.anchor，不挂钩，只找类）：
 *  - 视频文案是 com.ss.android.ugc.aweme.feed.desc.ui.ScrollMentionTextView（id 2131369754；textrec-tasks-20261020-150752 /
 *    143514 的 SETTEXT 记录里，它的文字就是视频文案，类名没混淆）。
 *  - 显示文字时在窗口里找屏幕上可见面积最大的那个 ScrollMentionTextView（翻页预加载的邻近视频在屏幕外，可见面积是 0），
 *    文字放在它的左下角：x = 文案左边 + pubX，y = 文案下边 + pubY。文案变长 / 点「展开」导致文案布局变化时，
 *    由挂在文案上的 OnLayoutChangeListener 把文字重新放一次（事件触发，没有定时器）。
 *  - 找不到类或屏幕上没有文案控件时，退回以前的位置：屏幕中心 + (pubX, pubY)。
 *
 * 文案控件有两种类（依据 textrec-tasks-20261020-150752 / 143514 的 SETTEXT 记录）：
 *  - HorizonPhotosViewHolder（第一个视频，bind this=HorizonPhotosViewHolder）：文案 SETTEXT 在
 *    com.ss.android.ugc.aweme.feed.desc.ui.ScrollMentionTextView#2131369754；
 *  - 其他视频：SETTEXT 在 com.ss.android.ugc.aweme.views.MentionTextView#2131401418（「玉米，加州，等各种🐍宝宝」「找个帮忙喂鸡的」……），
 *    同一界面还有一个 MentionTextView#2131412706，导出里只见到它被清空、没见到有文字。
 *  所以文案控件 = 这两个类的实例（不写死 id，id 每个版本都会变）。有好几个候选时按文案文字认：文字的开头和 Aweme.desc 的开头相同
 *  （desc 是 String 字段，快照里有 this.desc）。bind 比文案的 setText 早（setText 在 bind 里），所以 bind 钩子挂在 before，先记下 desc。
 *  - 文案文字一设上（DmtTextView#setText 钩子里认出这个控件）就立刻改字色 / 黑边，并且如果它是正在播放的视频的文案就把它当锚点
 *    （收起 / 展开 / 重设文字都会走到这里），布局好之后由文案上的 OnLayoutChangeListener 重放位置。
 *
 * 文案样式（同一个文案控件，同一个 pub.anchor；配置 capOutline / capColor）：
 *  - 找到屏幕上的文案控件时（新视频开始播放、回到前台、文案布局变化如展开），把它的字色设成 capColor（0 = 不动）、
 *    把阴影层设成「半径 3dp、偏移 0、黑色」（= 一圈黑边，和时长 / 发布信息文字的描边感一样）。有变化才设；
 *    话题、@ 这类带 span 的文字保持抖音自己的颜色。关掉功能后不会恢复原样（不知道抖音原来的值），要重启抖音。
 *
 * 没有定时器 / 轮询：文字只在上面的回调里更新或隐藏。
 */
final class PubHook {
    private PubHook() {}

    /** 版本相关（40.7.0）。 */
    private static final String DMT = "com.bytedance.ies.dmt.ui.widget.DmtTextView";
    private static final String MENTION = "com.ss.android.ugc.aweme.views.MentionTextView";
    private static final String CAPTION = "com.ss.android.ugc.aweme.feed.desc.ui.ScrollMentionTextView";
    private static final String PLACEHOLDER = "2026-10-19 11:50:00 广西 南宁";
    /** 结尾「日期 地点」最多看多少个字符。 */
    private static final int MAX_TAIL = 40;

    // pub.bind：版本相关（40.7.0），类名没混淆（TextRec 的钩点签名里就是这两个名字）
    private static final String HOLDER = "com.ss.android.ugc.aweme.feed.adapter.VideoViewHolder";
    private static final String AWEME = "com.ss.android.ugc.aweme.feed.model.Aweme";
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
    private static volatile Field fAid, fTime, fIp, fCity, fDesc;

    /** 一个视频的发布时间和地点。 */
    private static final class Info {
        final long sec;
        final String ip, city, desc;

        Info(long sec, String ip, String city, String desc) {
            this.sec = sec;
            this.ip = ip;
            this.city = city;
            this.desc = desc;
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
    private static boolean lastAnch;
    private static int lastSize;
    private static int lastGx = Integer.MIN_VALUE, lastGy = Integer.MIN_VALUE;

    /** 文案开头比较多少个字符。 */
    private static final int DESC_CMP = 16;
    /** 正在播放的视频的 desc（主线程写）；最近 bind 过的视频的 desc 快照（bind 时整个换一份，回调里只读）。 */
    private static volatile String curDesc;
    private static volatile String[] DESCS = new String[0];

    // 位置锚点（主线程用）
    private static volatile Class<?> captionCls, capMention;
    private static WeakReference<View> anchorRef = new WeakReference<>(null);
    private static final int[] LOC = new int[2];
    private static final Rect RECT = new Rect();
    private static View bestMatch, bestAny;
    private static int areaMatch, areaAny;
    private static final View.OnLayoutChangeListener ANCHOR_LISTENER = new View.OnLayoutChangeListener() {
        @Override
        public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
            if (l == ol && t == ot && r == or && b == ob) return;
            styleCaption(v);
            TextView tv = viewRef.get();
            if (tv != null && tv.getParent() != null) applyOffset(tv);
        }
    };

    // ------------------------------------------------------------ 安装

    /** 启动时和每次回到前台都会被调用；HookGuard 保证已有结论后立刻返回。 */
    static void tryHook(ClassLoader cl) {
        if (cl == null) return;
        ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        tryText(cl, ctx);
        tryBind(cl, ctx);
        tryAnchor(cl, ctx);
    }

    /**
     * pub.text：挂 DmtTextView 里 setText(CharSequence, TextView$BufferType)（类名、方法名都没混淆，TextRec 40.7.0，见文件头的依据）。
     * 回调里要按引用比较 this 的类是不是 MentionTextView，所以 MentionTextView 类也要找到：找不到算「类没找到」（waitFail，不放弃）；
     * DmtTextView 在、但没有这个签名的 setText 才算「方法没匹配」（miss）。
     */
    private static void tryText(ClassLoader cl, ClassLoader ctx) {
        if (HookGuard.settled(HookGuard.PUB)) return;
        Class<?> m = HookGuard.find(HookGuard.PUB, MENTION, cl, ctx);
        if (m == null) return;
        mentionCls = m;
        HookGuard.hook(HookGuard.PUB, DMT, SET_TEXT, HOOK,
                "DmtTextView 里没有 setText(CharSequence, BufferType)（文案控件的设置方法变了，需要重新追踪）", cl, ctx);
    }

    /** pub.anchor：只找视频文案控件的两个类（ScrollMentionTextView 和 MentionTextView），不挂钩；找不到就一直用屏幕中心的位置、不改样式。 */
    private static void tryAnchor(ClassLoader cl, ClassLoader ctx) {
        if (HookGuard.settled(HookGuard.PUB_ANCHOR)) return;
        Class<?> m = HookGuard.find(HookGuard.PUB_ANCHOR, MENTION, cl, ctx);
        if (m == null) return;
        Class<?> c = HookGuard.find(HookGuard.PUB_ANCHOR, CAPTION, cl, ctx);
        if (c == null) return;
        capMention = m;
        captionCls = c;
        HookGuard.ok(HookGuard.PUB_ANCHOR, "");
    }

    /**
     * pub.bind：挂 VideoViewHolder 里第一个参数是 Aweme 的 bind（bind(Aweme) 和 bind(Aweme, int) 都挂，处理是幂等的）。
     * Aweme 类按名字找，aid / createTime / ipAttribution / city 四个字段按名字找并缓存；aid（String）和 createTime（long / int）必须有，
     * 缺了就 miss（字段被混淆了，需要重新用 TextRec 看 Aweme 的字段快照）；ipAttribution、city 没有或不是 String 就少显示一段。
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
        Field city = null;
        try {
            city = aw.getDeclaredField("city");
            if (city.getType() != String.class) city = null;
        } catch (Throwable ignored) {
            city = null;
        }
        Field desc = null;
        try {
            desc = aw.getDeclaredField("desc");
            if (desc.getType() != String.class) desc = null;
        } catch (Throwable ignored) {
            desc = null;
        }
        int n = 0;
        try {
            a.setAccessible(true);
            t.setAccessible(true);
            if (ip != null) ip.setAccessible(true);
            if (city != null) city.setAccessible(true);
            if (desc != null) desc.setAccessible(true);
            for (Method m : vh.getDeclaredMethods()) {
                if (!m.getName().equals("bind")) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length == 0 || pt[0] != aw) continue;
                if (n == 0) { // 字段先放好再挂钩，回调一来就能用
                    fAid = a;
                    fTime = t;
                    fIp = ip;
                    fCity = city;
                    fDesc = desc;
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
        if (n > 0) HookGuard.ok(HookGuard.PUB_BIND, ip == null ? "没有地点字段，只显示日期" : (city == null ? "没有城市字段，只显示到省" : (desc == null ? "没有 desc 字段，文案控件按最大可见面积找" : "")));
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
                Object self = p.thisObject;
                if (self == null) return;
                Class<?> sk = self.getClass();
                if ((sk == capMention || sk == captionCls) && p.args[0] instanceof CharSequence && self instanceof TextView
                        && (styleOn() || enabled()) && isMain()) {
                    onCaptionText((TextView) self, (CharSequence) p.args[0]);
                }
                if (!enabled()) return;
                Class<?> k = mentionCls;
                if (k == null || sk != k) return; // 只处理 MentionTextView 本身
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
        // before：文案的 setText 就发生在 bind 里面，desc 要在它之前记下，setText 的钩子才认得出这是哪个视频的文案
        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            try {
                if (!enabled() && !styleOn()) return;
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
                    Field fc = fCity;
                    Object ct = fc == null ? null : fc.get(aw);
                    Field fd = fDesc;
                    Object ds = fd == null ? null : fd.get(aw);
                    known = new Info(sec, ip instanceof String ? (String) ip : null, ct instanceof String ? (String) ct : null,
                            ds instanceof String && !((String) ds).isEmpty() ? (String) ds : null);
                    synchronized (INFO) {
                        INFO.put(aid, known);
                        rebuildDescs();
                    }
                }
                // onPlaying 比 bind 先到的情况（这个视频已经在播）：补显示；预加载的邻近视频 id 对不上，什么都不做
                if (isMain() && aid.equals(curId)) {
                    if (curDesc == null) curDesc = known.desc;
                    if (!TimePanel.isOpen()) showInfo(known);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** INFO 里所有 desc 的快照（INFO 的锁里调用）。 */
    private static void rebuildDescs() {
        int n = 0;
        for (Info i : INFO.values()) if (i.desc != null) n++;
        String[] d = new String[n];
        int k = 0;
        for (Info i : INFO.values()) if (i.desc != null) d[k++] = i.desc;
        DESCS = d;
    }

    private static boolean styleOn() {
        DanmakuConfig c = DanmakuHook.cfg;
        return c.capOutline || c.capColor != 0;
    }

    /** 文案文字的开头和 desc 的开头相同（只比前 DESC_CMP 个字符）。 */
    private static boolean startsWith(CharSequence cs, String desc) {
        int n = Math.min(desc.length(), DESC_CMP);
        if (n == 0 || cs.length() < n) return false;
        for (int i = 0; i < n; i++) if (cs.charAt(i) != desc.charAt(i)) return false;
        return true;
    }

    private static boolean matchesAny(CharSequence cs) {
        String[] d = DESCS;
        for (int i = 0; i < d.length; i++) if (startsWith(cs, d[i])) return true;
        return false;
    }

    /**
     * 文案控件刚设了文字（DmtTextView#setText 的钩子里，主线程）：是正在播放的视频的文案 → 设样式并当锚点；
     * 是别的（邻近的）视频的文案 → 只设样式，翻过去时就已经是新样式。认不出是文案的（评论等）什么都不动。
     */
    private static void onCaptionText(TextView t, CharSequence cs) {
        String cur = curDesc;
        if (cur != null && startsWith(cs, cur)) {
            adoptAnchor(t);
            return;
        }
        if (styleOn() && matchesAny(cs)) styleCaption(t);
    }

    private static void showInfo(Info r) {
        if (!enabled()) return;
        show(CommentHook.top.get(), format(r));
    }

    /** 发布时间 → 「2026-10-19 11:50:00 广西 南宁」（createTime 是秒级时间戳，按手机时区；后面是省和市，有才写）。 */
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
        } else {
            ip = "";
        }
        String city = cityName(r.city);
        if (city != null && !ip.contains(city) && !city.contains(ip)) b.append(' ').append(city); // 北京 / 北京 这种重复只写一次
        return b.toString();
    }

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
        Info r;
        synchronized (INFO) {
            r = INFO.get(id);
        }
        curDesc = r == null ? null : r.desc;
        boolean shown = false;
        if (!TimePanel.isOpen()) {
            hideNow();
            if (r != null && enabled()) {
                showInfo(r); // show() 里已经找过文案控件并设过样式
                shown = true;
            }
        }
        if (!shown) refreshCaption(CommentHook.top.get());
    }

    /** 离开这个视频（划走 / 播放器释放）：别的视频迟到的事件不动当前文字。 */
    static void onStop(String id) {
        if (id == null || !id.equals(curId)) return;
        curId = null;
        curDesc = null;
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
            clearAnchor();
            detach(t);
            t = null;
        }
        if (t == null) {
            t = new TextView(a);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setTextColor(0xFFFFFFFF);
            t.setShadowLayer(5f * d, 0f, 0f, 0xFF000000); // 黑色描边感，亮暗背景都看得清
            t.setIncludeFontPadding(false);
            t.setClickable(false);
            t.setFocusable(false);
            t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            viewRef = new WeakReference<>(t);
            lastText = "";
            lastGx = lastGy = Integer.MIN_VALUE;
            lastSize = 0;
        }
        View panel = TimePanel.view();
        boolean moved = false;
        if (t.getParent() != decor) {
            detach(t);
            decor.addView(t, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
            lastGx = lastGy = Integer.MIN_VALUE; // 新的布局参数，下一次 applyOffset 一定重放
            moved = true;
        } else {
            View last = decor.getChildAt(decor.getChildCount() - 1);
            if (last != t && last != panel) {
                t.bringToFront();
                moved = true;
            }
        }
        if (moved && panel != null && panel.getParent() == decor) panel.bringToFront(); // 浮窗始终压在文字上面
        bindAnchor(decor);
        applySize(t);
        applyOffset(t);
        if (!text.equals(lastText)) {
            lastText = text;
            t.setText(text);
        }
        if (t.getVisibility() != View.VISIBLE) t.setVisibility(View.VISIBLE);
    }

    /** 字号（sp）：有变化才设。 */
    private static void applySize(TextView t) {
        int sz = DanmakuHook.cfg.pubSize;
        if (sz == lastSize) return;
        lastSize = sz;
        t.setTextSize(sz);
    }

    /** 位置浮窗里点 − / + 或拖字号滑动条时实时生效。 */
    static void applySizeNow() {
        TextView t = viewRef.get();
        if (t != null) applySize(t);
    }

    /** 有文案锚点：放在文案左下角 + (pubX, pubY)（窗口坐标，用 margin）；没有：屏幕中心 + (pubX, pubY)（用 translation）。 */
    private static void applyOffset(TextView t) {
        DanmakuConfig c = DanmakuHook.cfg;
        View an = anchorRef.get();
        boolean anch = an != null && an.isAttachedToWindow() && an.getVisibility() == View.VISIBLE;
        int gx = c.pubX, gy = c.pubY;
        if (anch) {
            an.getLocationInWindow(LOC);
            gx += LOC[0] + an.getPaddingLeft();
            gy += LOC[1] + an.getHeight();
        }
        if (anch == lastAnch && gx == lastGx && gy == lastGy) return;
        ViewGroup.LayoutParams p = t.getLayoutParams();
        if (!(p instanceof FrameLayout.LayoutParams)) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) p;
        lastAnch = anch;
        lastGx = gx;
        lastGy = gy;
        if (anch) {
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            lp.leftMargin = gx;
            lp.topMargin = gy;
            t.setTranslationX(0f);
            t.setTranslationY(0f);
        } else {
            lp.gravity = Gravity.CENTER;
            lp.leftMargin = 0;
            lp.topMargin = 0;
            t.setTranslationX(gx);
            t.setTranslationY(gy);
        }
        t.setLayoutParams(lp);
    }

    /**
     * 在窗口里找当前视频的文案控件：优先文字开头和当前视频 desc 相同的（不要求可见，邻近视频的文案文字不同）；
     * 当前视频没有 desc 时退而求其次，选屏幕上可见面积最大的非空文案控件。找到了就设样式、把布局监听挪过去。
     */
    private static void bindAnchor(ViewGroup decor) {
        bestMatch = null;
        bestAny = null;
        areaMatch = 0;
        areaAny = 0;
        if (capMention != null || captionCls != null) scan(decor);
        View best = bestMatch != null ? bestMatch : (curDesc == null ? bestAny : null);
        bestMatch = null;
        bestAny = null;
        if (best != null) styleCaption(best);
        setAnchor(best);
    }

    private static boolean isCaptionView(View v) {
        if (!(v instanceof TextView)) return false;
        Class<?> m = capMention, c = captionCls;
        return (m != null && m.isInstance(v)) || (c != null && c.isInstance(v));
    }

    private static void scan(View v) {
        if (v.getVisibility() != View.VISIBLE) return;
        if (isCaptionView(v)) {
            TextView t = (TextView) v;
            if (t.length() > 0) {
                int area = v.getGlobalVisibleRect(RECT) ? RECT.width() * RECT.height() : 0;
                String cur = curDesc;
                if (cur != null && startsWith(t.getText(), cur)) {
                    if (bestMatch == null || area > areaMatch) {
                        bestMatch = v;
                        areaMatch = area;
                    }
                } else if (area > areaAny) {
                    bestAny = v;
                    areaAny = area;
                }
            }
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0, n = g.getChildCount(); i < n; i++) scan(g.getChildAt(i));
        }
    }

    private static void setAnchor(View best) {
        View old = anchorRef.get();
        if (best == old) return;
        if (old != null) old.removeOnLayoutChangeListener(ANCHOR_LISTENER);
        anchorRef = new WeakReference<>(best);
        if (best != null) best.addOnLayoutChangeListener(ANCHOR_LISTENER);
        lastGx = lastGy = Integer.MIN_VALUE; // 锚点变了，下一次 applyOffset 一定重放
    }

    /** setText 钩子里认出了当前视频的文案：直接当锚点（这时还没布局，布局好之后监听会重放位置）。 */
    private static void adoptAnchor(TextView t) {
        styleCaption(t);
        setAnchor(t);
        TextView tv = viewRef.get();
        if (tv != null && tv.getParent() != null) applyOffset(tv);
    }

    /** 视频文案的字色和黑边（只有这两项里有开着的才动；有变化才设，布局变化时反复调用也没有开销）。 */
    private static void styleCaption(View v) {
        DanmakuConfig c = DanmakuHook.cfg;
        if (!c.capOutline && c.capColor == 0) return;
        if (!(v instanceof TextView)) return;
        TextView t = (TextView) v;
        if (c.capColor != 0 && t.getCurrentTextColor() != c.capColor) t.setTextColor(c.capColor);
        if (c.capOutline) {
            float r = 3f * t.getResources().getDisplayMetrics().density;
            if (t.getShadowRadius() != r) t.setShadowLayer(r, 0f, 0f, 0xFF000000);
        }
    }

    /** 没有在显示发布信息、只开了文案样式时：新视频开始播放 / 回到前台时找一次文案控件并设样式。 */
    private static void refreshCaption(Activity a) {
        DanmakuConfig c = DanmakuHook.cfg;
        if (!c.capOutline && c.capColor == 0) return;
        if (!isHostActivity(a)) return;
        bindAnchor((ViewGroup) a.getWindow().getDecorView());
    }

    private static void clearAnchor() {
        View old = anchorRef.get();
        if (old != null) old.removeOnLayoutChangeListener(ANCHOR_LISTENER);
        anchorRef = new WeakReference<>(null);
        lastGx = lastGy = Integer.MIN_VALUE;
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
            refreshCaption(a);
            TextView t = viewRef.get();
            if (t == null) return;
            if (!enabled() || !isHostActivity(a) || t.getContext() != a) {
                removeView();
                return;
            }
            applySize(t);
            applyOffset(t);
        } catch (Throwable ignored) {
        }
    }
}
