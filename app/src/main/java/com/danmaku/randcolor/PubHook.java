package com.danmaku.randcolor;

import android.app.Activity;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
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
 * 文案改色的第二条路（钩子 cap.draw，不碰文案内容，只在「画」的时候改色）：
 *  - 依据（TextRec，抖音 40.7.0，textrec-tasks-20261021-103536 的 #1）：文案控件是 ScrollMentionTextView（id=desc）；绘制栈
 *    DmtTextView.onDraw（栈第 1 层）← MentionTextView.onDraw（栈第 2 层，TextRec 标 [稳定]，类名方法名都没混淆）← View.draw，
 *    所以 MentionTextView 里声明了 onDraw(Canvas)，ScrollMentionTextView 和 MentionTextView 的实例都会经过它。
 *    画笔颜色记录：Paint(color=0xE6FFFFFF FILL)，同一次记录里「文字颜色(最近一次被设置)」也是 0xE6FFFFFF。
 *  - TextView.onDraw 每次开头都用内部字段 mCurTextColor 给画笔上色，所以只改画笔没用；只在 onDraw 前把 mCurTextColor 临时换成 capColor、
 *    onDraw 后换回去。不调 setTextColor（它会 invalidate，在 onDraw 里调会逼着每帧重画），文案内容不动，
 *    抖音之后怎么重设字色都不影响。话题 / @ 这类 span 自己带颜色，仍保持抖音原来的颜色。
 *  - 只改「认出是视频文案」的控件：styleCaption 认出时给控件打一个 tag，onDraw 回调里只看 tag（评论等其他 MentionTextView 不动）。
 *  - mCurTextColor 在安装阶段按名字找好并缓存（TextView 是系统类，字段名不会被抖音混淆）；读不到就 miss，改色退回 setText 那条路。
 *
 * 发布信息并进文案（1.20.4，取代下面「自己画一行 + 窗口坐标」的做法）：
 *  - 问题（用户截图）：浮层是 decor 里的独立 TextView，位置靠文案的窗口坐标算。文案控件认不出时退到屏幕正中（截图 1：压在视频人脸上），
 *    认出了也会因为文案的高度 / 祖先位置变化没跟上而压在文案第二行上（截图 2）。坐标是在文案外面算的，只要文案动了就可能对不上。
 *  - 做法：不再算坐标。文案控件（MentionTextView / ScrollMentionTextView，依据同上）每次 setText，在 DmtTextView#setText 的 before 回调里
 *    把参数换成「原文案 + 换行 + 日期地点」，信息是文案自己的最后一行，位置、展开 / 收起、跟着页面滑动都由抖音的布局负责。
 *  - 这条 setText 的设置栈（TextRec，textrec-tasks-20261021-103536 的 #1）：DmtTextView.setText ← ScrollMentionTextView.setText ← TextView.setText
 *    ← lambda ← Handler（异步投递，不在 bind 里同步调用），所以不能靠「bind 期间」认视频，只能按文案文字认：文字开头和某个视频的 Aweme.desc 相同。
 *    比较时忽略空白和竖线（截图 1 的文案「第42集 | 敦亲王…」带空格和竖线，只比前 16 个字符会对不上），只比前 12 个有效字符。
 *  - 抖音点「展开」时自己会在文案末尾加「日期 地点」（见上面 pub.text 的依据）。1.21.0 起发布信息是单独一行，不再往文案里追加，展开与否都显示。
 *  - 没有证据的部分：①文案控件有没有 maxLines 限制（有的话收起状态下多出来的这一行可能被截掉）；②图中「抖音精选内容」那种视频的文案
 *    是不是同样的两个类；③第一个视频如果在 pub.bind 挂上之前就设好了文案，要等下一次 setText（划走再划回、展开 / 收起）才会带上这一行。
 *  - 字号：用 RelativeSizeSpan(0.9)，跟着文案走；位置浮窗里的位置和字号不再对这行起作用（浮窗只留着一个示例）。
 *
 * 1.20.5：发布信息只剩「并进文案」这一条路。单独画的浮层、位置浮窗（TimePanel 的 PUB 目标）、pubX / pubY 都删了；
 * 下面几段里讲「自己画一行 / 窗口坐标 / 浮窗调位置」的是历史依据，只有「认视频」「文案控件是哪两个类」「pub.anchor 用来改文案样式」仍在用。
 * 发布信息那一行的字号 = pubSize（sp），在设置页调（1.21.0 起是单独一行的 TextView.setTextSize）。
 *
 * 1.21.0：发布信息改成「单独一行」，放在作者声明那一行的位置（取代上面「并进文案」的做法，文案文字不再被改）。
 *  - 依据（TextRec 视图树导出，40.7.0，textrec-tasks-20261021-143719 的视图树 #2 / #4，各取一个有声明和一个没声明的视频）：
 *    文案（FeedDescRootLayout，类名没混淆）和作者声明那一行（LinearLayout#jbz，里面是 FeedWarnForceCenterTextView）都是
 *    com.ss.android.ugc.aweme.feed.ui.AwemeIntroInfoLayout（竖直 LinearLayout，类名没混淆）的子控件，文案在前、声明行在后，
 *    两者之间只有高度 0 的空容器。有声明的视频比没声明的多一个子控件（就是 #jbz，没声明时它不存在，不是被隐藏）。
 *  - 做法：文案控件每次 setText（DmtTextView#setText 钩子的 after），沿父级往上找 AwemeIntroInfoLayout，在文案所在的那个直接子控件
 *    后面插入一个 TextView，显示 format()。有声明时它就在声明行上方；没声明时声明行不存在，它就在声明行本来的位置。
 *    这一行按文案文字认视频（和 desc 开头相同），认不出 / 开关关了 / 文案被清空就设成 GONE；同一个 AwemeIntroInfoLayout 里有两个文案控件时，
 *    只有最后设置这一行的那个控件才能把它撤掉（owner）。
 *  - 没有证据的部分：①抖音往这个容器里加声明行时用的是绝对下标还是相对某个控件的位置（这次导出里没有 addView 记录）。本模块的这一行插在文案后面第一个位置，
 *    声明行（原来在文案后面第 7 个）无论哪种方式都还在它后面；②抖音有没有按下标取这个容器的子控件。
 *
 * 1.22.0：发布信息那一行可以自定义（模板 pubFormat，见 PubFormat：{日期} {时间} {地点} {省} {市} 随意排顺序、夹文字，不限长度、不截断，
 *    放不下就自己换行）和颜色（pubColor，和文案颜色互不影响）。视频时长不再画在整个界面中间，改成同一个 AwemeIntroInfoLayout 里的另一行
 *    TextView，放在发布信息那一行的正下方（没显示发布信息就放在文案下面）；位置浮窗和 timeX / timeY 都删了，字号 timeSize、颜色 timeColor 保留。
 *    时长行放在「当前视频」的容器里：容器是 onPlay 时按「在屏幕上 + 文案和当前视频 desc 对得上」从已见过的容器里挑的（见 locateCurrent）。
 *    依据：TextRec 40.7.0（textrec-tasks-20261021-150205）里每个视频页都有自己的一个 AwemeIntroInfoLayout，邻近页的容器也在、但在屏幕外。
 *
 * 没有定时器 / 轮询：文字只在上面的回调里更新或隐藏。
 */
final class PubHook {
    private PubHook() {}

    /** 版本相关（40.7.0）。 */
    private static final String DMT = "com.bytedance.ies.dmt.ui.widget.DmtTextView";
    private static final String MENTION = "com.ss.android.ugc.aweme.views.MentionTextView";
    private static final String CAPTION = "com.ss.android.ugc.aweme.feed.desc.ui.ScrollMentionTextView";
    /** pub.row：作者声明和文案所在的竖直容器（40.7.0，类名没混淆，见文件头的依据）。 */
    private static final String INTRO = "com.ss.android.ugc.aweme.feed.ui.AwemeIntroInfoLayout";
    private static final int ROW_TAG = 0x7e0b0c02, REG_TAG = 0x7e0b0c03; // 应用自己的 tag key
    private static final String EMPTY = "";
    /** 发布信息 / 时长默认字色（配置里 pubColor / timeColor 为 0 时用）。 */
    private static final int ROW_COLOR = 0xCCFFFFFF, TIME_COLOR = 0xFFFFFFFF;
    private static volatile Class<?> introCls;

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

    // cap.draw：onDraw 前后临时换 mCurTextColor（都在主线程的绘制里用，不重入）
    private static final int CAP_TAG = 0x7e0b0c01; // 应用自己的 tag key（高字节不小于 2）
    private static final XC_MethodHook DRAW_HOOK = new DrawHook();
    private static volatile Field fCurColor;
    private static boolean drawSwapped;
    private static int drawSaved;
    private static final HookGuard.Filter ON_DRAW = new HookGuard.Filter() {
        @Override
        public boolean ok(Method m) {
            if (!m.getName().equals("onDraw")) return false;
            Class<?>[] t = m.getParameterTypes();
            return t.length == 1 && t[0] == android.graphics.Canvas.class;
        }
    };

    /** 安装阶段找好的 Aweme 字段（回调里只取值）。ip 可以没有。 */
    private static volatile Field fAid, fTime, fIp, fCity, fDesc;

    /** 一个视频的发布时间和地点。 */
    private static final class Info {
        final long sec;
        final String ip, city, desc;
        String line, lineTpl; // PubFormat 的结果和算它用的模板（模板变了才重算）

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

    // 状态（都在主线程用）
    private static String curId;

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
        tryRow(cl, ctx);
        tryDraw(cl, ctx);
    }

    /**
     * cap.draw：挂 MentionTextView 里的 onDraw(Canvas)（见文件头的依据）。TextView.mCurTextColor 必须先找到并放好再挂钩：
     * 类没找到走 waitFail（不放弃）；字段读不到或类型不是 int、或类里没有 onDraw(Canvas) 都算 miss（立刻放弃并记住）。
     */
    private static void tryDraw(ClassLoader cl, ClassLoader ctx) {
        if (HookGuard.settled(HookGuard.CAP_DRAW)) return;
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

    /** 画文案之前换色、之后换回来。只处理 styleCaption 认出并打过 tag 的文案控件；任何异常都不能抛回抖音。 */
    private static final class DrawHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            drawSwapped = false;
            try {
                Object self = p.thisObject;
                if (!(self instanceof TextView) || ((View) self).getTag(CAP_TAG) == null) return;
                int bg = DanmakuHook.cfg.lineBg;
                if (bg > 0 && p.args != null && p.args.length > 0 && p.args[0] instanceof android.graphics.Canvas) {
                    LineBg.drawBars((android.graphics.Canvas) p.args[0], (TextView) self, bg); // 文字灰色底：画在字之前
                }
                int want = DanmakuHook.cfg.capColor;
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

    /** pub.row：只找 AwemeIntroInfoLayout 这个类（不挂钩）；找不到就不显示这一行，抖音原来的界面不变。 */
    private static void tryRow(ClassLoader cl, ClassLoader ctx) {
        if (HookGuard.settled(HookGuard.PUB_ROW)) return;
        Class<?> c = HookGuard.find(HookGuard.PUB_ROW, INTRO, cl, ctx);
        if (c == null) return;
        introCls = c;
        HookGuard.ok(HookGuard.PUB_ROW, "");
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

    // ------------------------------------------------------------ 钩子

    private static final class Hook extends XC_MethodHook {
        /** 文案控件刚设了文字：更新发布信息那一行、记下当前视频的容器（给时长用），再按需要设文案的字色 / 黑边。 */
        @Override
        protected void afterHookedMethod(MethodHookParam p) {
            try {
                Object self = p.thisObject;
                if (self == null || p.args == null || p.args.length == 0 || !(self instanceof TextView)) return;
                Class<?> sk = self.getClass();
                if (sk != capMention && sk != captionCls) return;
                if (!isMain()) return;
                CharSequence cs = p.args[0] instanceof CharSequence ? (CharSequence) p.args[0] : EMPTY;
                onCaption((TextView) self, cs);
                if (styleOn()) onCaptionText((TextView) self, cs);
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------ 发布信息和时长：同一个容器里的两行（见文件头 1.22.0）

    /** 一个 AwemeIntroInfoLayout 上挂的东西（tag ROW_TAG，主线程用）。 */
    private static final class Rows {
        TextView pub;       // 发布信息那一行（要显示时才建）
        TextView cap;       // 这个容器里的文案控件
        CharSequence text;  // 文案控件最近一次设的文字（认视频用）
    }

    private static final int MAX_INTROS = 16;
    /** 见过的 AwemeIntroInfoLayout（弱引用，主线程用）；每个视频页一个，邻近页的也在。 */
    private static final ArrayList<WeakReference<ViewGroup>> INTROS = new ArrayList<>();
    private static final WeakReference<ViewGroup> NO_INTRO = new WeakReference<>(null);
    /** 当前视频所在的容器（时长行放进它里面）；还没认出来就是空的。 */
    private static WeakReference<ViewGroup> curIntro = NO_INTRO;
    private static WeakReference<TextView> timeRef = new WeakReference<>(null);
    private static int timeSzApplied = -1, timeColApplied;
    /** 当前视频的容器没认出来时，开始播放后头几次显示时长再试几次（翻页动画完了位置才准）。 */
    private static int locateTries;

    /**
     * 文案控件 cap 刚设了文字 cs：找到它所在的 AwemeIntroInfoLayout，更新发布信息那一行；
     * 文案对得上当前视频，就把这个容器记成当前视频的容器（时长行用）。主线程调用。
     */
    private static void onCaption(TextView cap, CharSequence cs) {
        Class<?> ic = introCls;
        if (ic == null) return;
        ViewGroup intro = null;
        View child = cap;
        for (int d = 0; d < 8; d++) {
            ViewParent par = child.getParent();
            if (par == null) return;
            if (ic.isInstance(par)) {
                intro = (ViewGroup) par;
                break;
            }
            if (!(par instanceof View)) return;
            child = (View) par;
        }
        if (intro == null) return;
        Rows r = rowsOf(intro, true);
        if (cs.length() == 0 && r.cap != null && r.cap != cap) return; // 容器里别的控件设了空串，不算文案变了
        r.cap = cap;
        r.text = cs;
        updatePub(intro, r);
        String cur = curDesc;
        if (cur != null && DanmakuHook.cfg.timeAlways && cs.length() > 0 && curIntro.get() != intro && sameStart(cs, cur)) {
            curIntro = new WeakReference<>(intro);
            TimeHook.onIntroReady();
        }
    }

    /** 取这个容器上的 Rows；create 为真时没有就新建并登记。 */
    private static Rows rowsOf(ViewGroup intro, boolean create) {
        Object o = intro.getTag(ROW_TAG);
        if (o instanceof Rows) return (Rows) o;
        if (!create) return null;
        Rows r = new Rows();
        intro.setTag(ROW_TAG, r);
        INTROS.add(new WeakReference<>(intro));
        if (INTROS.size() > MAX_INTROS) {
            for (int i = INTROS.size() - 1; i >= 0; i--) if (INTROS.get(i).get() == null) INTROS.remove(i);
        }
        return r;
    }

    /** cap 在 intro 下面的那个直接子控件；找不到返回 null。 */
    private static View childOf(ViewGroup intro, View cap) {
        View child = cap;
        for (int d = 0; d < 8; d++) {
            ViewParent par = child.getParent();
            if (par == intro) return child;
            if (!(par instanceof View)) return null;
            child = (View) par;
        }
        return null;
    }

    /**
     * 更新发布信息那一行：能认出是哪个视频、开关开着 → 套模板显示（字号、颜色用配置）；否则撤掉。
     * 行放在文案所在的直接子控件后面（有作者声明时就在声明上方）。不限长度、不截断，一行放不下由 TextView 自己换行。
     */
    private static void updatePub(ViewGroup intro, Rows r) {
        DanmakuConfig c = DanmakuHook.cfg;
        TextView cap = r.cap;
        CharSequence cs = r.text;
        TextView row = r.pub;
        String line = null;
        if (c.pubAlways && cap != null && cs != null && cs.length() > 0) {
            line = lineFor(cs, c.pubFormat);
            if (line == null) line = nativeLine(intro, r, c.pubFormat); // bind 时没记到这个视频：读抖音自己放的那一行
        }
        if (line == null) {
            if (row != null && row.getVisibility() != View.GONE) row.setVisibility(View.GONE);
            return;
        }
        if (row == null || row.getParent() != intro) {
            View child = childOf(intro, cap);
            if (child == null) return;
            row = newRow(cap);
            r.pub = row;
            intro.addView(row, intro.indexOfChild(child) + 1);
        }
        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, c.pubSize);
        fitSpacing(row, cap);
        int color = c.pubColor != 0 ? c.pubColor : ROW_COLOR;
        if (row.getCurrentTextColor() != color) row.setTextColor(color);
        if (!line.contentEquals(row.getText())) row.setText(line);
        if (row.getVisibility() != View.VISIBLE) row.setVisibility(View.VISIBLE);
        row.invalidate(); // 灰色底透明度可能变了
    }

    /** 新建发布信息这一行：一圈黑边；左边距和文案对齐，宽度按文字、放不下就换行，不截断。 */
    private static TextView newRow(TextView cap) {
        TextView t = new LineBg.Row(cap.getContext());
        float d = t.getResources().getDisplayMetrics().density;
        t.setShadowLayer(5f * d, 0f, 0f, 0xFF000000); // 和时长行的字描边一样
        t.setLayoutParams(rowLp(cap, d));
        return t;
    }

    /**
     * 行距对齐文案：文案（抖音）把行距压得比字体默认的小。按文案「行高 ÷ 字号」的比例算出这一行该有的行高，
     * 字体默认行高比它大的部分，从上下外边距里各减一半（外边距本来就是 −pad，抵消内边距）。
     * 只在边距要变时才改并重新布局，已经对齐就什么都不做。
     */
    private static void fitSpacing(TextView row, TextView cap) {
        try {
            float cs = cap.getTextSize();
            if (cs <= 0f || !(row.getLayoutParams() instanceof LinearLayout.LayoutParams)) return;
            float want = cap.getLineHeight() * row.getTextSize() / cs;
            int excess = Math.max(0, Math.round(row.getLineHeight() - want));
            int m = -LineBg.pad(row.getContext()) - excess / 2;
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) row.getLayoutParams();
            if (lp.topMargin != m || lp.bottomMargin != m) {
                lp.topMargin = m;
                lp.bottomMargin = m;
                row.setLayoutParams(lp);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 两行（发布信息 / 时长）共用的布局参数：左边距取文案父控件的 leftMargin。 */
    private static LinearLayout.LayoutParams rowLp(TextView cap, float d) {
        int left = 0;
        ViewParent cp = cap.getParent();
        if (cp instanceof View) {
            ViewGroup.LayoutParams lp = ((View) cp).getLayoutParams();
            if (lp instanceof ViewGroup.MarginLayoutParams) left = ((ViewGroup.MarginLayoutParams) lp).leftMargin;
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = (left > 0 ? left : Math.round(12f * d)) - LineBg.pad(cap.getContext()); // 减掉行自己的内边距，字和文案对齐
        int pad = LineBg.pad(cap.getContext());
        lp.topMargin = -pad; // 行自己有 pad 的内边距（给灰色底留地方），用负边距抵消：行与行之间的间距 = 文字本身的行距
        lp.bottomMargin = -pad;
        return lp;
    }

    /**
     * 认不出是哪个视频（INFO 里没有）时的备用数据：抖音自己在 AwemeIntroInfoLayout 里放了一个普通 TextView（平时隐藏，展开文案时才显示），
     * 内容是「2026-10-20 11:26:00 安徽 安庆」（TextRec 40.7.0，textrec-tasks-20261021-150205 里每个视频的容器都有）。
     * 认得出这个格式就拆成日期、时间、省、市再套模板。自己放的两行（发布信息、时长）也是 TextView，要跳过。
     * 局限：容器被复用时，抖音自己的这一行可能比文案晚设，短暂显示上一个视频的数据；开始播放时会按最新文字重新套一遍。
     */
    private static String nativeLine(ViewGroup intro, Rows r, String tpl) {
        TextView mine = timeRef.get();
        for (int i = 0, n = intro.getChildCount(); i < n; i++) {
            View v = intro.getChildAt(i);
            if (v == r.pub || v == mine || v.getClass() != TextView.class) continue;
            String l = nativeParse(((TextView) v).getText(), tpl);
            if (l != null) return l;
        }
        return null;
    }

    private static String nativeParse(CharSequence t, String tpl) {
        if (t == null || t.length() < 19) return null;
        for (int k = 0; k < 19; k++) {
            char ch = t.charAt(k);
            if (k == 4 || k == 7) {
                if (ch != '-') return null;
            } else if (k == 10) {
                if (ch != ' ') return null;
            } else if (k == 13 || k == 16) {
                if (ch != ':') return null;
            } else if (ch < '0' || ch > '9') {
                return null;
            }
        }
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(num(t, 0, 4), num(t, 5, 2) - 1, num(t, 8, 2), num(t, 11, 2), num(t, 14, 2), num(t, 17, 2));
        String rest = t.subSequence(19, t.length()).toString().trim();
        String prov = rest, city = null;
        for (int i = 0; i < rest.length(); i++) {
            if (Character.isWhitespace(rest.charAt(i))) {
                prov = rest.substring(0, i);
                city = rest.substring(i).trim();
                break;
            }
        }
        return PubFormat.render(tpl, c.getTimeInMillis() / 1000L, prov, city);
    }

    private static int num(CharSequence t, int from, int len) {
        int v = 0;
        for (int i = from; i < from + len; i++) v = v * 10 + (t.charAt(i) - '0');
        return v;
    }

    /** 这段文案是哪个视频的：套那个视频的模板返回「发布信息」；认不出返回 null。结果按模板缓存在 Info 里，模板变了才重算。 */
    private static String lineFor(CharSequence cs, String tpl) {
        Info hit = null;
        synchronized (INFO) {
            for (Info i : INFO.values()) {
                if (i.desc != null && sameStart(cs, i.desc)) {
                    hit = i;
                    break;
                }
            }
        }
        if (hit == null) return null;
        String l = hit.line;
        if (l == null || hit.lineTpl == null || !hit.lineTpl.equals(tpl)) {
            hit.line = l = PubFormat.render(tpl, hit.sec, hit.ip, cityName(hit.city));
            hit.lineTpl = tpl;
        }
        return l;
    }

    // ------------------------------------------------------------ 视频时长那一行

    /**
     * 视频时长那一行：放进当前视频的 AwemeIntroInfoLayout，发布信息那一行的正下方（没显示发布信息就在文案下面）；
     * 字号、颜色用配置（timeSize / timeColor）。当前视频的容器还没认出来返回 null。主线程调用（TimeHook 用）。
     */
    static TextView timeView() {
        ViewGroup intro = curIntro.get();
        if (intro == null) {
            if (locateTries <= 0) return null;
            locateTries--;
            if (curDesc == null && locateTries == 2) return null; // 没有 desc 只能按位置认：第一次多半还在翻页动画里，等下一次
            intro = locateCurrent();
            if (intro == null) return null;
            curIntro = new WeakReference<>(intro);
        }
        if (!intro.isAttachedToWindow()) return null;
        Rows r = rowsOf(intro, false);
        if (r == null || r.cap == null) return null;
        View anchor = r.pub != null && r.pub.getParent() == intro ? r.pub : childOf(intro, r.cap);
        if (anchor == null) return null;
        TextView t = timeRef.get();
        if (t != null && t.getContext() != intro.getContext()) { // 换了界面：旧的拿掉重建
            detach(t);
            t = null;
        }
        if (t == null) {
            t = new LineBg.Row(intro.getContext());
            float d = t.getResources().getDisplayMetrics().density;
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setIncludeFontPadding(false);
            t.setShadowLayer(5f * d, 0f, 0f, 0xFF000000); // 黑色描边感，亮暗背景都看得清
            t.setClickable(false);
            t.setFocusable(false);
            try {
                t.setFontFeatureSettings("tnum"); // 等宽数字，秒数跳动时不抖
            } catch (Throwable ignored) {
            }
            t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            t.setLayoutParams(rowLp(r.cap, d));
            timeRef = new WeakReference<>(t);
            timeSzApplied = -1;
            timeColApplied = 0;
        }
        if (t.getParent() == intro) {
            if (intro.indexOfChild(t) != intro.indexOfChild(anchor) + 1) {
                intro.removeView(t);
                intro.addView(t, intro.indexOfChild(anchor) + 1);
            }
        } else {
            detach(t);
            intro.addView(t, intro.indexOfChild(anchor) + 1);
        }
        DanmakuConfig c = DanmakuHook.cfg;
        if (c.timeSize != timeSzApplied) {
            timeSzApplied = c.timeSize;
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, c.timeSize);
        }
        fitSpacing(t, r.cap);
        int color = c.timeColor != 0 ? c.timeColor : TIME_COLOR;
        if (color != timeColApplied) {
            timeColApplied = color;
            t.setTextColor(color);
        }
        t.invalidate(); // 灰色底透明度可能变了
        return t;
    }

    /** 时长行先藏起来（离开视频）；下一次显示时会再放到当前视频的容器里。 */
    static void hideTime() {
        TextView t = timeRef.get();
        if (t != null && t.getVisibility() != View.GONE) t.setVisibility(View.GONE);
    }

    /** 时长行从界面上拿掉（开关关了）。 */
    static void removeTime() {
        detach(timeRef.get());
    }

    private static void detach(View v) {
        if (v != null && v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
    }

    // ------------------------------------------------------------ 认当前视频的容器

    /**
     * 当前视频的容器：先从见过的容器里挑「在屏幕上」的（邻近页的容器也在、但在屏幕外），文案对得上当前视频 desc 的优先；
     * 一个都挑不到（hook 装得晚，容器的文案早就设过了）就扫一遍当前界面里的 AwemeIntroInfoLayout 再挑。找不到返回 null。
     */
    private static ViewGroup locateCurrent() {
        Class<?> ic = introCls;
        if (ic == null) return null;
        ViewGroup g = pickIntro();
        if (g != null) return g;
        Activity a = CommentHook.top.get();
        if (!isHostActivity(a)) return null;
        scanIntros(a.getWindow().getDecorView(), ic);
        return pickIntro();
    }

    /**
     * 两遍：第一遍只看文案对不对得上当前视频的 desc（不看位置：onPlaying 来的时候翻页动画可能还没完，新页的容器还在屏幕外）；
     * 没有 desc 或对不上，第二遍才按位置挑「在屏幕上」的（邻近页的容器在屏幕外）。
     */
    private static ViewGroup pickIntro() {
        Activity a = CommentHook.top.get();
        View decor = isHostActivity(a) ? a.getWindow().getDecorView() : null;
        String cur = curDesc;
        ViewGroup any = null;
        for (int i = INTROS.size() - 1; i >= 0; i--) {
            ViewGroup g = INTROS.get(i).get();
            if (g == null) {
                INTROS.remove(i);
                continue;
            }
            if (!g.isAttachedToWindow()) continue;
            if (decor != null && g.getRootView() != decor) continue; // 别的界面（被盖在下面的）
            if (cur != null) {
                Rows r = rowsOf(g, false);
                if (r != null && r.text != null && r.text.length() > 0 && sameStart(r.text, cur)) return g;
            }
            if (any == null && onScreen(g)) any = g;
        }
        return any;
    }

    /** 容器左上角在屏幕里：翻页时邻近页的容器差一个屏幕高度（上下）或宽度（左右标签页）。 */
    private static boolean onScreen(ViewGroup g) {
        g.getLocationOnScreen(LOC);
        DisplayMetrics dm = g.getResources().getDisplayMetrics();
        return LOC[1] >= 0 && LOC[1] < dm.heightPixels && LOC[0] > -dm.widthPixels / 2 && LOC[0] < dm.widthPixels / 2;
    }

    private static void scanIntros(View v, Class<?> ic) {
        if (v == null) return;
        if (ic.isInstance(v)) {
            ViewGroup g = (ViewGroup) v;
            Rows r = rowsOf(g, true);
            if (r.cap == null) {
                View f = findCap(g);
                if (f instanceof TextView) {
                    r.cap = (TextView) f;
                    r.text = ((TextView) f).getText();
                }
            }
            if (r.cap != null) updatePub(g, r);
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0, n = g.getChildCount(); i < n; i++) scanIntros(g.getChildAt(i), ic);
        }
    }

    private static View findCap(View v) {
        Class<?> k = v.getClass();
        if (k == captionCls || k == capMention) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0, n = g.getChildCount(); i < n; i++) {
                View f = findCap(g.getChildAt(i));
                if (f != null) return f;
            }
        }
        return null;
    }

    /** 当前界面里的 AwemeIntroInfoLayout 全扫一遍并登记（hook 装得晚，容器的文案早就设过了）。 */
    private static void scanAll() {
        Class<?> ic = introCls;
        Activity a = CommentHook.top.get();
        if (ic == null || !isHostActivity(a)) return;
        scanIntros(a.getWindow().getDecorView(), ic);
    }

    /**
     * 开始播放时：已经见过的容器按它们现在的文字重新套一遍发布信息。文案可能在 bind 之前设过（当时认不出视频），
     * 抖音自己的发布时间那一行也可能刚设好；每个容器只看自己的文案和自己的那一行，所以邻近页的也一起更新、不会串。
     */
    private static void syncAll() {
        for (int i = INTROS.size() - 1; i >= 0; i--) {
            ViewGroup g = INTROS.get(i).get();
            if (g == null) {
                INTROS.remove(i);
                continue;
            }
            Rows r = rowsOf(g, false);
            if (r == null || r.cap == null) continue;
            CharSequence now = r.cap.getText();
            if (now != null && now.length() > 0) r.text = now;
            updatePub(g, r);
        }
    }

    /** 回到前台（配置刚重读过）：已经见过的容器按新配置（开关、模板、字号、颜色）重新套一遍。 */
    private static void refreshRows() {
        for (int i = INTROS.size() - 1; i >= 0; i--) {
            ViewGroup g = INTROS.get(i).get();
            if (g == null) {
                INTROS.remove(i);
                continue;
            }
            Rows r = rowsOf(g, false);
            if (r != null && r.cap != null && r.text != null) updatePub(g, r);
        }
    }

    /**
     * 文案文字 a 是不是 desc（b）这个视频的文案。三步，都忽略空白、竖线、零宽字符，都不分配对象（在 setText 回调里调用）：
     * ①开头对齐：前 SAME_N 个有效字符相同（至少比了 4 个，或者一方整段比完且完全相同）；
     * ②文案开头是「第N集」之类的前缀：跳过它再按①比（desc 再短也行）；
     * ③还是对不上：desc 的前 SAME_N 个有效字符，出现在文案前 SCAN 个有效字符之内（desc 至少 MIN_IN 个有效字符，兜底别的前缀）。
     *    依据：TextRec 40.7.0（textrec-tasks-20261021-150205）里系列剧的文案控件先设「小燕子成功见到皇上 #还珠格格」（和 desc 开头相同），
     *    再设「第11集 | 小燕子成功见到皇上 #还珠格格」（抖音在前面加了「第N集 | 」）；只比开头的话第二次就认不出，这一行会被撤掉。
     */
    private static final int SAME_N = 12, SCAN = 16, MIN_IN = 6;

    private static boolean sameStart(CharSequence a, String b) {
        if (sameHead(a, 0, b)) return true;
        int p = seriesPrefixEnd(a);
        if (p > 0 && sameHead(a, p, b)) return true; // 跳过「第N集」再比开头：短文案也能精确认出
        return containsHead(a, b);
    }

    /** 文案开头是「第N集 / 话 / 期 / 章」（N 是数字或中文数字）：返回这个前缀结束的位置；不是返回 0。不分配对象。 */
    private static int seriesPrefixEnd(CharSequence a) {
        int la = a.length(), i = 0;
        while (i < la && skip(a.charAt(i))) i++;
        if (i >= la || a.charAt(i) != '\u7B2C') return 0; // 第
        i++;
        int n = 0;
        while (i < la && n < 6 && isNum(a.charAt(i))) {
            i++;
            n++;
        }
        if (n == 0 || i >= la) return 0;
        char c = a.charAt(i);
        return c == '\u96C6' || c == '\u8BDD' || c == '\u671F' || c == '\u7AE0' ? i + 1 : 0; // 集 话 期 章
    }

    private static boolean isNum(char c) {
        return Character.isDigit(c) || "\u4E00\u4E8C\u4E09\u56DB\u4E94\u516D\u4E03\u516B\u4E5D\u5341\u767E\u96F6\u3007\u4E24".indexOf(c) >= 0;
    }

    private static boolean sameHead(CharSequence a, int from, String b) {
        int i = from, j = 0, n = 0;
        int la = a.length(), lb = b.length();
        while (n < SAME_N) {
            while (i < la && skip(a.charAt(i))) i++;
            while (j < lb && skip(b.charAt(j))) j++;
            if (i >= la || j >= lb) return n > 0 && i >= la && j >= lb || n >= 4;
            if (a.charAt(i) != b.charAt(j)) return false;
            i++;
            j++;
            n++;
        }
        return true;
    }

    private static boolean containsHead(CharSequence a, String b) {
        int la = a.length(), lb = b.length();
        int need = 0;
        for (int j = 0; j < lb && need < SAME_N; j++) if (!skip(b.charAt(j))) need++;
        if (need < MIN_IN) return false;
        int seen = 0;
        for (int s = 0; s < la && seen < SCAN; s++) {
            if (skip(a.charAt(s))) continue;
            seen++;
            int x = s, y = 0, n = 0;
            while (n < need) {
                while (x < la && skip(a.charAt(x))) x++;
                while (y < lb && skip(b.charAt(y))) y++;
                if (x >= la || y >= lb || a.charAt(x) != b.charAt(y)) break;
                x++;
                y++;
                n++;
            }
            if (n == need) return true;
        }
        return false;
    }

    private static boolean skip(char c) {
        return Character.isWhitespace(c) || c == '|' || c == '\uFF5C' || c == '\u4E28' || c == '\u200B' || c == '\u00A0';
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
                if (!DanmakuHook.cfg.pubAlways && !styleOn()) return;
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
                // onPlaying 比 bind 先到的情况（这个视频已经在播）：补上 desc；预加载的邻近视频 id 对不上，什么都不做
                if (isMain() && aid.equals(curId) && curDesc == null) curDesc = known.desc;
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
        return c.capOutline || c.capColor != 0 || c.lineBg > 0;
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

    // ------------------------------------------------------------ 视频切换（由 TimeHook 里已有的播放器监听转进来，主线程）

    /**
     * 某个视频开始播放：记下它的 id 和 desc（认文案用），并认出它所在的容器（开了时长常显才认，时长行要放进去）。
     * 查不到（hook 装得晚、不是 feed 里的视频）就什么都不记。onPlaying 每个视频会来很多次，同一个 id 第二次起直接返回。
     */
    static void onPlay(String id) {
        if (id == null || id.equals(curId)) return;
        curId = id;
        Info r;
        synchronized (INFO) {
            r = INFO.get(id);
        }
        curDesc = r == null ? null : r.desc;
        // 时长行要放进当前视频的容器：有 desc 才在这里认（靠文案对得上）；没有的等头几次显示时长时再按位置认（见 timeView）
        ViewGroup g = DanmakuHook.cfg.timeAlways && curDesc != null ? locateCurrent() : null;
        curIntro = g == null ? NO_INTRO : new WeakReference<>(g);
        locateTries = 3;
        if (DanmakuHook.cfg.pubAlways) {
            if (INTROS.isEmpty()) scanAll();
            syncAll();
        }
        refreshCaption(CommentHook.top.get());
    }

    /** 离开这个视频（划走 / 播放器释放）：别的视频迟到的事件不动当前文字。 */
    static void onStop(String id) {
        if (id == null || !id.equals(curId)) return;
        curId = null;
        curDesc = null;
    }

    // ------------------------------------------------------------ 文案样式

    private static boolean isHostActivity(Activity a) {
        return a != null && !a.isFinishing() && !a.isDestroyed();
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
    }

    /** setText 钩子里认出了当前视频的文案：直接当锚点（这时还没布局，布局好之后监听会重放位置）。 */
    private static void adoptAnchor(TextView t) {
        styleCaption(t);
        setAnchor(t);
    }

    /** 视频文案的字色和黑边（只有这两项里有开着的才动；有变化才设，布局变化时反复调用也没有开销）。 */
    private static void styleCaption(View v) {
        DanmakuConfig c = DanmakuHook.cfg;
        if (!c.capOutline && c.capColor == 0 && c.lineBg == 0) return;
        if (!(v instanceof TextView)) return;
        TextView t = (TextView) v;
        if (t.getTag(CAP_TAG) == null) t.setTag(CAP_TAG, Boolean.TRUE); // 认出是视频文案：cap.draw 只改打过 tag 的
        if (c.capColor != 0 && t.getCurrentTextColor() != c.capColor) t.setTextColor(c.capColor);
        if (c.capOutline) {
            float r = 3f * t.getResources().getDisplayMetrics().density;
            if (t.getShadowRadius() != r) t.setShadowLayer(r, 0f, 0f, 0xFF000000);
        }
    }

    /** 没有在显示发布信息、只开了文案样式时：新视频开始播放 / 回到前台时找一次文案控件并设样式。 */
    private static void refreshCaption(Activity a) {
        DanmakuConfig c = DanmakuHook.cfg;
        if (!c.capOutline && c.capColor == 0 && c.lineBg == 0) return;
        if (!isHostActivity(a)) return;
        bindAnchor((ViewGroup) a.getWindow().getDecorView());
    }

    // ------------------------------------------------------------ 生命周期

    /** 回到前台（配置刚重读过）：给当前视频的文案设一次样式。 */
    static void onResume(Activity a) {
        try {
            if (!isMain()) return;
            refreshCaption(a);
            refreshRows();
        } catch (Throwable ignored) {
        }
    }
}
