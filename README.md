# dyin（DanmakuColor）— 抖音弹幕随机色 + 点赞改打开评论 + 视频时长常显 + 发布日期地点常显（LSPosed 模块）

## 功能
1. **弹幕随机色**：每条弹幕随机颜色，保留黑色描边。
2. **拦截点赞**：右侧爱心按钮 / 双击视频不再点赞，也不出双击红心。
3. **打开评论**：上面两种操作改为打开当前视频的评论。双击这一路（1.20.0 起）优先走名字没混淆的 `onDoubleTap` + `MonitorScrollFrameLayout#onInterceptTouchEvent`，没挂上时由混淆名的 `LLLIILIL` 兜底。
4. **视频时长常显**（1.15）：自己画一行 `00:12 / 03:45`，默认在屏幕正中，随播放走动；1.16 起抖音任何界面都显示，位置用抖音里的浮窗（两条滑动条）调整。

5. **无操作自动退出**（1.17.8）：连续 N 分钟没有操作抖音（触摸、按键、回到前台都算操作），就结束抖音进程；后台也计时。设置页里填分钟数，0 = 关闭。

6. **首页顶部标签隐藏**（1.17.9）：设置页里列出标签行的 13 个标签，勾选哪个就隐藏哪个。

7. **发布日期和地点常显**（1.18.0）：自己画一行 `3天前 北京` / `10月10日 江西`，位置和视频时长一样用抖音里的浮窗调整。1.18.1 起数据取自视频本身（`Aweme` 的发布时间和 IP 属地），不用再点「展开」；点「展开」时抖音页面自己的文字仍会覆盖显示。

## 开发规范
新增钩子 / 新功能前先读 [`HOOKS.md`](HOOKS.md)（钩子自检的 6 步和硬性规则）；AI 助手的入口是 [`AGENTS.md`](AGENTS.md)。

## 构建（手动）
仓库根目录放好所有文件 → Actions → **Build Release APK (manual)** → Run workflow → 下载 `DanmakuColor-release`。

## 使用
1. LSPosed 启用模块，作用域勾选抖音，强杀抖音重开。
2. 打开模块设置，点「保存并应用」（需要 root），抖音回到前台即生效。不推送则用默认：开启 + 鲜艳随机。

## 1.20.5：删掉发布信息的位置调节，只留字号
- **删**：发布信息已经是文案的最后一行，位置由抖音布局决定，位置微调没有东西可调。去掉 `pubX` / `pubY` / `pubAdjust`、设置页的「打开浮窗调整位置」和「归零」、`TimePanel` 的 `PUB` 目标、`PubHook` 里单独画的浮层（`show` / `applyOffset` / 示例文字）。位置浮窗现在只调时长。
- **留**：字号。追加的那一行改用 `AbsoluteSizeSpan(pubSize, true)`（原来是 `RelativeSizeSpan(0.9)`，字号滑动条其实没起作用）。设置页的发布信息卡片里是 − / + 步进（8～40 sp，默认 16），点「保存并应用」后，抖音回到前台、文案下一次 `setText`（划到下一个视频）时生效，已经显示的文案不会马上变。
- **没动**：`pub.anchor`（文案描边 / 改色要用）、`pub.bind`、`pub.text` 的匹配规则，`RULES_REV` 不变。旧配置里的 `pubX` / `pubY` / `pubAdjust` 读取时直接忽略。
- **局限**：没有编译、没有上机。

## 1.20.4：发布日期地点并进文案，在文案正下方
- **问题**（用户截图）：发布日期地点那一行的位置一直变：有的视频压在视频人脸中间（文案控件没认出，退到屏幕正中），有的视频压在文案第二行上（位置是在文案外面按窗口坐标算的，文案高度或祖先位置变了没跟上）。
- **做法**：不再算坐标。文案控件（`MentionTextView` / `ScrollMentionTextView`）每次 `setText`，在已有的 `pub.text` 钩子（`DmtTextView#setText`）的 `before` 回调里，把参数换成「原文案 + 换行 + 日期地点」，信息就是文案自己的最后一行，在文案正下方；位置、展开 / 收起、跟着页面滑动都由抖音的布局负责。没有新钩子，匹配规则不变，`RULES_REV` 不变。
- **认视频**：设置栈（TextRec，`textrec-tasks-20261021-103536` 的 #1）是 `DmtTextView.setText ← ScrollMentionTextView.setText ← TextView.setText ← lambda ← Handler`，是异步投递的，不在 bind 里同步调用，所以按文案文字认：开头和某个视频的 `Aweme.desc` 相同。比较忽略空白和竖线、只比前 12 个有效字符（截图 1 的「第42集 | 敦亲王…」带空格和竖线，原来只比前 16 个字符会对不上）。预加载的邻近视频的文案也同样带上这一行。
- **不重复**：抖音点「展开」时自己会在文案末尾加「日期 地点」（`tail()` 认得出），这时不再追加。
- **取代**：原来 decor 里单独画的浮层只在「打开浮窗调整位置」时画一个示例；位置微调和字号滑动条对这一行不再起作用，这一行用 `RelativeSizeSpan(0.9)` 跟着文案字号。`onPlay` 里不再 `showInfo`，只刷新文案样式。
- **局限**：①没有编译、没有上机；②文案控件如果有 `maxLines` 限制，收起状态下多出来的这一行可能被截掉（没有证据说明有没有）；③「抖音精选内容」那种视频的文案是不是同样的两个类没有证据，不是的话不会带这一行（以前会画在屏幕正中，现在不显示）；④冷启动后第一个视频如果文案在 `pub.bind` 挂上之前就设好了，要等下一次 `setText`（划走再划回、展开 / 收起）才会带上；⑤回调里要新建一个带这一行的文字（只在文案 `setText` 时，不在绘制里），和「回调里不分配对象」的规矩有一点出入。

## 1.20.3：双击打开评论后视频不再被暂停（dtap.click）
- **问题**：双击打开评论后，视频停在暂停状态。
- **依据**（TextRec，40.7.0，`textrec-tasks-20261021-105717`，开着双击改评论）：①单击是延迟处理的：单击暂停（touch 18413）→ `onPlayPause` 18674，隔 261ms；单击继续（touch 32279）→ 继续播放 32467，隔 188ms。继续播放那次的栈：`WeakHandler.handleMessage ← BaseListFragmentPanel.handleMsg ← X.06vk.run ← FeedComponentsRoot.handleSingleClick ← handleSingleClickInner ← handleVideoPauseAndPlay(Aweme)`，暂停 / 继续是同一个切换，`FeedComponentsRoot` 这几个方法 TextRec 都标 [稳定]。②双击（任务 4）：第一下 touch 46492，评论页 46671 打开，第二下 touch 46763，`onPlayPause` 46844，比第二下还晚，调用栈和单击暂停完全相同。
- **推断**：抖音自己的双击处理会取消那条延迟的单击；dyin 拦掉了双击，取消没发生，单击在双击之后照常执行，把视频切成暂停。（任务 4 的记录里没有直接出现 `handleSingleClick` 的栈，这是按时间推的。）
- **新增钩子**：`dtap.click`（`HookGuard` 编号 14，设置页「双击改评论：不让单击暂停视频」，挂在 `CommentHook.tryHook` 里）：`com.ss.android.ugc.aweme.feed.plato.core.FeedComponentsRoot#handleSingleClick`（所有返回 void 的重载，类名方法名都没混淆）。`dtap.cut` / `comment.dtap` 拦住双击时记下时刻；之后 800ms 内 `handleSingleClick` 被调到就直接跳过，视频不暂停。不改已有钩子的匹配规则，`RULES_REV` 不变。回调里只有一次静态读和一次时间比较，整体 `try/catch Throwable`。
- **降级**：`dtap.click` 没挂上时什么都不做，行为和 1.20.2 一样（双击后暂停）。`dtapToComment` 关掉时也不生效。
- **局限**：①没有编译、没有上机；②如果单击处理比第二下点击还早执行（双击两下间隔很长），暂停在拦住双击之前已经发生，这个钩子管不了；③800ms 内的真正单击也会被跳过（双击刚结束就马上单击，几乎不会发生）；④推断没有被直接证实，如果装上后仍然暂停，需要再记一次 `FeedComponentsRoot#handleSingleClick*` 在双击里有没有被调到。

## 1.20.2：视频文案改色多一条「绘制时改色」的路径（cap.draw）
- **新增钩子**：`cap.draw`（`HookGuard` 编号 13，设置页「视频文案：绘制时改色」，挂在 `PubHook.tryHook` → `tryDraw` 里，它本来就在 `DanmakuHook.init` 和 `hookResume` 两处调用）：`com.ss.android.ugc.aweme.views.MentionTextView#onDraw(Canvas)`（类名、方法名都没混淆）。
- **依据**（TextRec，40.7.0，`textrec-tasks-20261021-103536` 的 #1）：文案控件是 `ScrollMentionTextView`（id=`desc`）；绘制栈是 `DmtTextView.onDraw`（栈第 1 层）← `MentionTextView.onDraw`（栈第 2 层，TextRec 标 [稳定]）。`MentionTextView` 里声明了 `onDraw`，`ScrollMentionTextView` 和 `MentionTextView` 的实例都经过它。画笔颜色 `0xE6FFFFFF`，同一条记录里「文字颜色(最近一次被设置)」也是 `0xE6FFFFFF`。
- **做法**：`TextView.onDraw` 每次开头都用 `mCurTextColor` 给画笔上色，只改画笔会被覆盖。所以在 `onDraw` 前把 `mCurTextColor` 临时换成 `capColor`，`onDraw` 后换回去；不调 `setTextColor`（它会 invalidate，在 `onDraw` 里调会逼着每帧重画），文案内容不动。只改 `styleCaption` 认出并打过 tag 的文案控件，评论等其他 `MentionTextView` 不动。`mCurTextColor` 在安装阶段按名字找好并缓存；回调里只有 `Field.getInt / setInt`，没有查找、没有分配、没有日志，整体 `try/catch Throwable`。
- **和 `setText` 那条路的关系**：没动已有钩子（`pub.text` / `pub.anchor` / `pub.bind` 的匹配规则不变，`RULES_REV` 不变）。`setText` 那条路仍负责认出文案控件、打 tag、设黑边；`cap.draw` 负责「画的时候」用 `capColor`，抖音之后怎么重设字色都不影响。
- **降级**：读不到 `mCurTextColor` 或类里没有 `onDraw(Canvas)` 就 `miss`，功能安静失效，文案改色退回 1.19.x 的 `setText` 路径，抖音原有行为不变。
- **局限**：①没有编译（没有 Android SDK）、没有上机；②话题、@ 这类带 span 的文字自己带颜色，仍是抖音原来的颜色；③`capColor` 是不透明色，抖音原来的字色带 90% 透明度（`0xE6`），改后文案会比原来更实；④`mCurTextColor` 是系统的隐藏字段，LSPosed 下一般能读，读不到时设置页「钩子状态」会显示 `cap.draw` 未适配；⑤`MentionTextView` 子类如果重写 `onDraw` 又不调 `super.onDraw`，这个钩子不会走到。

## 1.20.1：修构建错误（PubHook 缺 tryText）
- **问题**：构建报 `PubHook.java:208: cannot find symbol: method tryText(ClassLoader,ClassLoader)`。`PubHook.tryHook` 里调用了 `tryText`，但 `PubHook.java` 里没有它的定义（上传的 1.19.1 压缩包里就已经是这样，不是 1.20.0 的改动引起的）。
- **做法**：补上 `tryText`：先找 `MentionTextView`（找不到走 `waitFail`），记下 `mentionCls`，再用 `HookGuard.hook` 挂 `DmtTextView` 里 `setText(CharSequence, TextView$BufferType)`（没匹配走 `miss`）。写法照 1.18.0 的说明（见下面 1.18.0 一节）。
- **局限**：这是按 README 和 `PubHook` 里现有的常量（`DMT` / `MENTION` / `SET_TEXT` / `HOOK`）还原的，没有编译、没有上机；如果你仓库里原来有 `tryText`，以你的为准。

## 1.20.0：双击改评论多一条名字没混淆的路径
- **新增两个钩子**（`HookGuard` 编号 11 / 12，设置页「双击改评论：识别双击（系统方法）」「双击改评论：拦在点赞之前」，挂在 `CommentHook.tryHook` 里，它本来就在 `DanmakuHook.init` 和 `hookResume` 两处调用）：
  - `dtap.seen`：`android.view.GestureDetector$SimpleOnGestureListener#onDoubleTap(MotionEvent)`（系统方法）；
  - `dtap.cut`：`MonitorScrollFrameLayout#onInterceptTouchEvent(MotionEvent)`（`com.ss.android.ugc.aweme.feed.plato.business.contentconsumption.interestselection`，类名、方法名都没混淆）。
- **依据**（TextRec，40.7.0，`textrec-tasks-20261020-203447` / `20261021-032906` / `20261021-093511`）：
  ①双击是在 `MonitorScrollFrameLayout.onInterceptTouchEvent` 里被识别的：第二下 DOWN 时 `onDoubleTap` 的栈是 `onInterceptTouchEvent → ATListener.onTouch → FeedPinchPresenterV2 → LiveData → VideoPinchUtil$eventObserver$1 → GestureDetector.onTouchEvent → onDoubleTap`；监听器 `AdS2S0100000_29` 没重写 `onDoubleTap`（命中的是 `SimpleOnGestureListener` 自己的方法，返回 false）；`onDoubleTap` 在两次双击里各命中 1 次（首页和个人主页视频流都有），单击、上滑 0 次。
  ②识别完 `onInterceptTouchEvent` 才返回，之后第二下 DOWN 才往下发给 `LongPressLayout`（红心和点赞在那里面）；「已点赞」界面更新在 `onDoubleTap` 之后约 42ms。
  ③`VideoViewHolderRootView#onInterceptTouchEvent` 在第二下的 MOVE 上返回 true 是抖音自己的取消，发生在点赞之后，拦不住，所以没用它。
- **做法**：`dtap.cut` 的钩子进来时记下「正在拦截判断」，`dtap.seen` 在这个期间被调到就记一笔；`onInterceptTouchEvent` 出来时如果记了，就把结果改成 `true`（子视图收不到第二下 DOWN，`LongPressLayout` 里的红心和点赞都不会发生），同时打开评论（找评论按钮的方法和 `LLLIILIL` 那条一样，800ms 内只开一次）。找不到评论按钮也照样拦：双击不点赞。开关还是 `dtapToComment`，在回调里判断。两个静态变量都只在主线程的触摸分发里读写；回调里没有反射、没有对象分配、没有日志。
- **和 `LLLIILIL` 的关系**：新路径拦住了，第二下 DOWN 到不了 `LongPressLayout`，`comment.dtap` 那个混淆名钩子就不会被走到；新路径没挂上或没生效（抖音改了监听器、类名变了）时，仍由 `comment.dtap` 兜底。没有改任何已有钩子的匹配规则，`RULES_REV` 不变。
- **降级**：两个钩子缺一个就什么都不做（`dtap.seen` 没挂上永远不会标记；`dtap.cut` 没挂上不会改结果），抖音原有行为不变。
- **局限**：①没有在真机验证；②`LongPressLayout` 是 `MonitorScrollFrameLayout` 的后代（按栈上的 `X.07Ut.dispatchTouchEvent` 推断）、「这里返回 true 之后点赞确实不会发生」（按安卓的拦截规则推断）都没有记录证明；③第一下点击已经让视频暂停（描述变成「播放视频」），这一步拦不住，双击之后视频会不会保持暂停要看实际；④如果抖音以后在监听器里重写了 `onDoubleTap`，`dtap.seen` 就命中不了，新路径失效，由 `comment.dtap` 兜底；⑤这条路径靠 `onDoubleTap` 触发，抖音的双击判定（间隔、位置容差）就是系统 `GestureDetector` 的，不是模块自己算的。

## 1.19.1：文案控件认全，改色 / 位置不再慢
- **问题**：1.18.3 / 1.19.0 只认 `ScrollMentionTextView` 一种文案控件。导出里（`150752` / `143514`）只有第一个视频（`bind this=HorizonPhotosViewHolder`）的文案在它上面（SETTEXT `ScrollMentionTextView#2131369754`）；其他视频的文案 SETTEXT 在 `com.ss.android.ugc.aweme.views.MentionTextView#2131401418`（「玉米，加州，等各种🐍宝宝」「找个帮忙喂鸡的」……）。所以普通视频上既找不到锚点（退回屏幕中心 + 偏移，容易压在文案上）也不会设样式。另外文案的 `setText` 发生在 `onPlaying` 之后（第一个视频：`onPlaying` 1379ms，文案 SETTEXT 1501ms），开始播放那一刻去找文案控件时它还是空的，要等下一次触发才变色。
- **做法**：文案控件 = `MentionTextView` 和 `ScrollMentionTextView` 两个类的实例（`pub.anchor` 两个类都要找到，不写死 id）。①`pub.bind` 现在挂在 bind **之前**，先记下 `Aweme.desc`（String 字段，快照里有 `this.desc`）；②`DmtTextView#setText` 钩子里（已有的钩子，只多了一个类判断）：文字开头（前 16 个字符）和某个视频的 `desc` 相同就是文案——正在播放的视频的文案立刻改字色 / 黑边并当锚点（收起、展开、重设文字都走这里），邻近视频的文案也提前改好；评论等认不出的不动；③开始播放 / 回到前台时的扫描也按 `desc` 认，当前视频的文案不要求在屏幕上可见；当前视频没有 `desc` 时才退回「可见面积最大的非空文案控件」。文案布局好之后（含展开 / 收起），文案上的布局监听把发布信息重放到它的左下角。
- **局限**：①没在真机验证；②截图里那个视频用的是哪个文案类，是我按导出推断的；③`ScrollMentionTextView` 的 `setText` 是不是走 `DmtTextView#setText`（决定它能不能被即时设样式）没有证据，走不到时它只在开始播放 / 回到前台 / 布局变化时设样式；④`pubX` / `pubY` 现在是相对文案左下角的偏移，以前在「找不到文案、屏幕中心 + 偏移」时手调的值要在浮窗里点「归零」重来；⑤展开时如果抖音换了另一个控件显示全文，靠它的 `setText` 才能切锚点（文字开头仍是 `desc`，应该能认出）。

## 1.19.0：文案描边 / 改色，时长和发布信息可调字号
- **字号**：位置浮窗（「打开浮窗调整位置」）里多了第三条滑动条「字号」：左端 − 是小一号、右端 + 是大一号，每点一下增减 1 sp，范围 8～40 sp；时长文字默认 20 sp、发布信息默认 16 sp（就是以前写死的值）。配置键 `timeSize` / `pubSize`。浮窗的「归零」只归位置，不动字号；设置页「当前位置」那行会显示字号，从抖音回到本页时同步。
- **视频文案描边 / 改色**：设置页新增「视频文案样式」：开关「视频文案加黑色描边」（`capOutline`），和「文案颜色」的色表（`capColor`，24 色，0 = 不改）。用的是 1.18.3 的 `pub.anchor`（视频文案控件 `ScrollMentionTextView`，依据见 1.18.3），没有新钩子：新视频开始播放、回到前台、文案布局变化（如点「展开」）时，找到屏幕上的文案控件，字色设成 `capColor`，阴影层设成「半径 3dp、偏移 0、黑色」（一圈黑边，和时长 / 发布信息文字的描边感一样）；有变化才设，没有定时器。`HOOK_NAMES` 里 `pub.anchor` 的显示名改成「视频文案：位置 / 描边 / 改色」。
- **局限**：①没有在真机上验证：我没有证据说明抖音会不会在我们设完之后又自己改字色 / 阴影（比如暂停、展开时）；②描边是阴影层做的（软黑边），不是真正的描边；③改色只改文案的基础字色，话题、@ 这类带 span 的文字保持抖音自己的颜色；④关掉描边 / 把颜色改回「不改」后不会恢复原样（不知道抖音原来的值），要重启抖音；⑤文案样式依赖 `time.playing` 把换视频的事件转进来，没挂上时只在回到前台和文案布局变化时生效。

## 1.18.3：发布日期地点放在视频文案下方
- 新增 `pub.anchor`（`HookGuard` 编号 10，设置页「发布日期地点：文案位置」）：只找类、不挂钩。
- **依据**（TextRec，40.7.0，`textrec-tasks-20261020-150752` / `143514`）：视频文案是 `com.ss.android.ugc.aweme.feed.desc.ui.ScrollMentionTextView`（id 2131369754），`SETTEXT` 记录里它的文字就是视频文案，类名没混淆。
- **做法**：显示文字时在窗口里找屏幕上可见面积最大的那个 `ScrollMentionTextView`（翻页预加载的邻近视频在屏幕外，可见面积是 0），文字放在它的左下角：x = 文案左边 + `pubX`，y = 文案下边 + `pubY`（窗口坐标，用 margin）。文案变长或点「展开」让文案布局变化时，挂在文案上的 `OnLayoutChangeListener` 会把文字重放一次（事件触发，没有定时器）。找不到类或屏幕上没有文案控件时，退回以前的位置：屏幕正中 + 偏移。
- **偏移的含义变了**：`pubX` / `pubY` 现在是相对文案左下角的偏移，默认值从 `0, 90` 改成 `0, 0`（只影响还没存过配置的情况）；以前存过的值（比如 90）会变成「文案下方 90px」，需要在「打开浮窗调整位置」里重新调一次。
- **局限**：①没有真机验证：文案点「展开」后它的下边缘是否真的往下走、文案下面的空间够不够放这行字、窗口坐标和 decor 坐标是否有系统栏偏移，都是推断；②翻页滑动的过程中文字不跟着页面动（和以前一样，在 `onStop` / 新视频 `onPlaying` 时才换）；③「展开」如果是弹出另一个窗口（Dialog / PopupWindow），盖在最上面，这个做法仍然盖不过它（没有看到证据说明是不是）。

## 1.18.2：地点精确到市
- **依据**（TextRec 字段快照，`textrec-tasks-20261020-150752` 的 `Aweme#toString` 记录，`this` 就是 `Aweme`）：`aid = "7698029777320581952"`、`createTime = 1792381800`（秒，= 2026-10-19 11:50:00 +08:00）、`ipAttribution = "广西"`、`city = "450100"`（String，6 位行政区划代码，450100 = 南宁市；`author.city` 是空串）。这也确认了 1.18.1 里用的 `aid` / `createTime` / `ipAttribution` 三个字段名。
- **做法**：`pub.bind` 在安装阶段多找一个可选字段 `Aweme.city`（String）；`PubHook.CITIES` 是「地级代码前 4 位 → 城市简称」的表（338 项，大陆地级单位），显示成 `2026-10-19 11:50:00 广西 南宁`。表里查不到、`city` 为空或不是 6 位数字时只显示省；城市名和省重复（北京 / 北京）只写一次。没有 `city` 字段时设置页状态里写「没有城市字段，只显示到省」，不影响其他。
- **局限**：①只看到了一个视频的快照，`Aweme.city` 是不是「发布地」没有和抖音页面上的文字对照过（页面本身只显示省），请在真机上多看几个视频；②省直辖县级单位（如仙桃、济源）、港澳台、以后新增的代码不在表里，只显示省；③`CITIES` 表是我按行政区划代码整理的，没有拿官方表逐项核对；④没有在真机上验证过。

## 1.18.1：发布日期和地点不用再点「展开」
- 新增钩子 `pub.bind`（`HookGuard` 编号 9；挂在 `PubHook.tryHook` 里，它本来就在 `DanmakuHook.init` 和 `hookResume` 两处调用；设置页名字「发布日期地点：视频数据」）。原来的 `pub.text`（展开文案）保留。
- **依据**（TextRec，抖音 40.7.0，`textrec-tasks-20261020-143514`）：`VideoViewHolder#bind(Aweme)` / `bind(Aweme, int)` 是翻页绑定入口，`ForwardFeedReversionVideoViewHolder` 和 `PlayletVideoViewHolder` 的 bind 都经过它；参数 `Aweme` 的 `aid` 和播放器 `onPlaying` 的第一个参数（也是 `PlayerEvent` 的 id）是同一个字符串，三个视频逐个对上。bind 是预加载触发的（36874ms 邻近视频 `7696206519011937599` 先 bind，36892ms 的 `onPlaying` 还是上一个视频，之后也没播过它），所以不能用「最后一次 bind」当当前视频。
- **怎么做**：bind 时按 `aid` 记下 `createTime`（秒）和 `ipAttribution`，最多 48 个（LRU）；已经记过的 `aid` 跳过；`onPlaying` 经 `TimeHook` 转进来的 `PubHook.onPlay(id)` 再按 id 查出来显示，查不到就什么都不显示；如果 `onPlaying` 比 bind 先到，bind 时发现 `aid` 等于当前视频再补显示。换视频 / 离开的撤掉逻辑不变。
- **怎么匹配**：`VideoViewHolder` 里第一个参数是 `Aweme` 的 `bind` 全挂（类没找到走 `waitFail`；`Aweme` 里没有 `aid` / `createTime` 字段、字段类型不对、没有 `bind(Aweme…)` 走 `miss`，原因写明要重新追踪什么）。`Field` 在安装阶段按名字找好并缓存；`ipAttribution` 没有就只显示日期。
- **时间显示**：`createTime` 是秒级时间戳，直接显示成 `2026-10-17 14:32:08`（手机时区），后面接 `ipAttribution`（省名）。不再换算成「N天前」。
- **局限**：①规范有一点出入：bind 回调里读字段是反射的 `Field.get` / `getLong`（`Field` 缓存好了，每个视频一次，不是每帧），已记过的视频直接跳过；记新视频时有一个 `Info` 对象分配；②开关没开时 bind 不记，中途打开开关要等下一个视频才有；③依赖 `time.playing`（和 `time.stop`）挂上；没挂上时 `onPlay` 不会被调到，文字安静地不显示；④字段名 `aid` / `createTime` / `ipAttribution` 已被 `150752` 的字段快照确认（见 1.18.2）；字段名变了时 `pub.bind` 会 `miss`，设置页显示未适配；⑤没有在真机上验证。

## 1.18.0：发布日期和地点常显
- 新增 `PubHook` 和钩子 `pub.text`（已登记到 `HookGuard` 编号 8 / `DanmakuHook.init` / `hookResume` / `MainActivity.HOOK_NAMES`）。
- **依据**（TextRec，抖音 40.7.0，`textrec-tasks-20261020-084058` 的 #424）：点文案末尾「展开」时，`com.ss.android.ugc.aweme.views.MentionTextView`（id `g=q`，页面 `UltraDetailActivity > C2FeedFragment`）被设置成完整文案，结尾是「4 个空格 + 日期 + 空格 + 地点」，例如 `…#历史剧解说    3天前 北京`；没有地点的视频结尾只有日期。设置侧的稳定钩点是 `com.bytedance.ies.dmt.ui.widget.DmtTextView#setText(CharSequence, TextView$BufferType)`（类名、方法名都没混淆）。
- **怎么匹配**：只挂 `DmtTextView` 里签名是 `setText(CharSequence, BufferType)` 的那一个方法（类找不到走 `waitFail`，类在但没有这个方法走 `miss`，`MentionTextView` 类没找到也算类没找到）。回调里：先看开关（没开直接返回），再比较 `this` 的类是不是 `MentionTextView`（引用比较，不是子类），再从文字结尾往前找最靠后的 4 个连续空格，空格后第一个字符必须是数字、日期这一段的最后一个字符必须是「前」或「日」（认 `3天前`、`39分钟前`、`10月10日`、`2025年10月10日`），确认是日期之后才取字符串；地点可有可无。不认的写法（比如「昨天」）什么都不显示。
- **显示**：和时长一样自己画一个 `TextView` 加到当前界面最上层（16sp，白字黑描边），位置存在 `pubX` / `pubY`（1.18.3 起相对视频文案的左下角，见上面 1.18.3；找不到文案时仍是屏幕正中 + 偏移）；换了界面就拿掉重建。
- **位置浮窗**：`TimePanel` 现在可以调两个文字（`TIME` / `PUB`），标题和滑动条对应各自的配置；设置页新增「发布日期和地点常显」卡片，有自己的「打开浮窗调整位置」和「归零」，推送的一次性请求是 `pubAdjust`。调发布信息位置时，时长文字不会因为浮窗开着而冒出来。
- **什么时候撤掉**：`TimeHook` 已有的播放器监听里各加了一行：`onPlaying` 里调 `PubHook.onPlay(id)`（换了视频就撤掉旧的），`onStop` 里调 `PubHook.onStop(id)`（离开当前视频就撤掉）。不依赖「视频时长一直显示」有没有打开，但依赖那几个时长钩子（`time.playing` / `time.stop` / `time.release`）挂上了；没挂上时文字会一直留到下一次捕获或换了界面。
- **没有定时器、没有轮询**，回调里除了确认是日期之后的一次字符串分配没有对象分配、反射和日志。
- **局限**：①这条文案只在「展开」时设置（TextRec 里任务 4 共 13 次，划视频的任务 0 次），所以每个视频要先点一次「展开」才有得显示，不能做到不点就常显；②`DmtTextView#setText` 是通用文字控件的方法，所以每次调用都会进回调，开关关着或类不对时只多做几次布尔 / 引用判断；③`MentionTextView` 在别的地方（评论、简介）如果也有结尾恰好是「4 个空格 + 日期」的文字，会被误取；④没有在真机上验证过，解析函数只单独用样例字符串测过。

## 1.17.9：首页顶部标签勾选隐藏
- 设置页新增"首页顶部标签"：推荐 / 关注 / 热点 / 同城 / 商城 / 经验 / 游戏 / 少儿 / 精选 / 团购 / 免费看剧 / 短剧 / 直播，勾选 = 隐藏。13 个标签来自 TextRec 追踪（40.7.0）里标签行每个 `HomeTopTabItemViewNew` 的 contentDescription；那台手机上当前显示的是推荐、关注、热点、同城、商城，其余 8 个在标签行里存在但抖音没有启用。
- 钩子 `tab.hide`（`TabHook`，已登记到 `HookGuard` / `DanmakuHook.init` / `hookResume` / `MainActivity.HOOK_NAMES`）：找到没混淆的类 `...homepage.tab.top.ui.tabstrip.item.HomeTopTabItemViewNew` 才挂；挂的是框架方法 `View.setContentDescription`，回调里只处理这个类的实例，参数形如"热点，按钮"，取第一个"，"之前的文字去匹配，命中就 `setVisibility(GONE)`。追踪里没有这个 setContentDescription 的设置栈，所以没有更靠近业务的稳定钩子。
- 为什么不会像 1.11 以前那样耗电：旧版钩的是全局 `TextView.setText` / `View.setVisibility` 并且定时扫 View 树。现在钩的 `setContentDescription` 调用很少；回调先判断有没有勾选（没勾选直接返回），再比较一次类，不分配对象；没有定时器、没有扫描；找不到这个类就根本不挂。
- 局限：取消勾选后要重启抖音才恢复；只隐藏标签，左右划动仍会翻到被隐藏标签的页面；隐藏后标签行怎么重排取决于抖音的布局；抖音如果在我们设完之后又把它设回可见就会再出现。没有在真机上验证过。

## 1.17.8：无操作自动退出
- 新增 `IdleExit`。**记**：钩 `Activity.onUserInteraction`（手指按下 / 按键按下时系统调一次，滑动过程中的移动事件不调）和已有的 `Activity.onResume` 钩子，各把一个时刻 `lastActive`（`elapsedRealtime`）覆盖成"现在"——只覆盖，不比较、不排消息，一秒划几次也只是几次赋值。**判**：回到前台时排**一条** N 分钟后的延时消息；到点醒来才做一次减法 `现在 − lastActive`：不够 N 分钟就按剩余时间再排一条，够了就 `Process.killProcess`。N 分钟里不管操作多少次，消息只在到点时醒一回。没有倒计时，没有每秒的东西。
- 后台也计时。回到前台算一次操作，重新开始。
- **晚到保护**：后台被冻结时消息会晚到；如果晚到 5 秒以上而你正好这时切回抖音，onResume / 触摸事件可能还排在消息后面，直接杀会误杀刚切回来的抖音。所以晚到时先把检查往消息队列末尾再放一次，让它们先更新 `lastActive`，再判断。
- 配置键 `idleExitMin`（0 = 关闭，上限 1440），设置页新增"无操作自动退出"卡片。
- 这是整个模块里唯一的延时消息，它是功能本身，不是 `HOOKS.md` 规则 6 禁止的"为自检加的定时器"；`HOOKS.md` / `AGENTS.md` 已补一句说明。`onUserInteraction` 是 Android 框架方法，按 `HOOKS.md` 第 5 节不走 `HookGuard`。
- 已知局限：如果抖音某个 Activity 重写了 `onUserInteraction` 且不调用 `super`，那个界面里的触摸不会被记到；如果某些弹窗（单独的 Dialog / PopupWindow）里的触摸不经过 Activity，也不会被记到。这两种情况在真机上观察：该界面操作很久也不该退出时却退出了，就是漏了。没有在真机上验证过。
- 没有改弹幕、评论、时长相关的任何逻辑。

## 1.17.7：钩子有了统一的停止条件 + 弹幕放宽匹配 + 设置页「钩子状态」
以前时长和评论的钩子在类一直找不到时，每次回到前台都会 `Class.forName` 一遍，没有上限；评论的双击入口一个方法都没匹配到时还会「继续等」。现在每个钩子（共 7 个）都由新的 `HookGuard` 管理，只有四种结果：
- **OK**：挂上了。
- **类没找到**：每个钩子最多试 6 次（每次回到前台一次），之后这次进程不再试。**不记成放弃**——类可能只是还没加载，下次启动再给它机会。
- **放弃**：类找到了，但里面没有匹配的方法（抖音改了这段代码）→ 立刻放弃，按「APK 大小 + 修改时间 + 规则版本 `RULES_REV`」记在抖音数据目录的 `files/hooks.properties`，之后每次启动直接跳过。抖音更新（APK 变了）或匹配规则改了（`RULES_REV` 加一）才会清掉重试。
- 所有钩子都有结论之后，`Activity.onResume` 不再扫描类、不再挂钩。`hooks.properties` 只在状态有变化时才写，稳态不写文件。

**弹幕 `paint` 先严格后放宽**：
- 严格：`paint(Canvas, long 颜色, Shadow, TextDecoration, DrawStyle, int)`（TextRec 追踪确认的签名，和以前一样）。
- 放宽：第一个参数是 `Canvas`、恰好一个 `long`（颜色）、有一个 `DrawStyle` 参数。颜色和 `DrawStyle` 在参数里的位置按**类型**找，不再写死 `a[1]` / `a[4]`，参数个数和顺序变了也能挂上。带 `Brush` 的重载没有 `long` 颜色，被排除。
- 都不匹配就放弃并记住，弹幕保持原色，不报错。

**评论的两个入口不做放宽匹配**：`FeedDiggPresenter#LJJJJJL` 和 `BaseListFragmentPanel#LLLIILIL(MotionEvent)` 的方法名是混淆的，换版本会变；如果只按签名 `void(MotionEvent)` 去猜，猜错了会让每次触摸都打开评论。找不到就放弃，点赞 / 双击点赞照常，设置页会显示「未适配」，这时需要用 TextRec 重新追踪新名字。

**设置页新增「钩子状态」**：点按钮用 root 读 `hooks.properties`，列出抖音版本和每个钩子的状态（✓ 可用 / ✗ 未适配及原因 / … 还没挂上）。`RootShell` 新增通用的 `readFile`。

没有在真机上验证过；这版只做过语法层面的检查。能验证的只是「名字改了之后流程走得通」，真实更新里的各种意外要等抖音真的更新了才知道。

## 1.17.6：弹幕上色热路径再减一点
- 去掉 `ThreadLocal`，改成普通静态变量（Compose 文字绘制都在主线程）。
- 加 256 项直接映射的颜色缓存：同一条弹幕每帧重绘时命中缓存，不再重算哈希混合和 HSV→RGB。缓存按 `mode` 区分，切换颜色风格后自动失效。
- 没有改钩子的位置（仍是 `MultiParagraph.paint`）；"弹幕生成时定色"的方案需要先有追踪。没有在真机上测过。

## 1.17.5：恢复 1 秒忽略旧视频进度回调的判断
- 1.17.4 把它换成按视频 id + `onPlaying` 放行，但没有任何追踪证明那个事件在所有情况下都会来，等于没有钩子就删掉了补洞的判断。已原样恢复（`STALE_MS` / `staleUntil`）。要去掉它，得先有能替代它的钩子。

## 1.17.3：位置浮窗也去掉定时器
- 1.17.2 只去掉了时长文字的看门狗，位置浮窗（`TimePanel`）里还有两个定时器：− / + 按钮长按连发（400ms 后每 50ms 一次）、数值改动后 400ms 延迟落盘。已全部删除，`TimePanel` 不再有 `Handler`。
- 现在：− / + 每点一下走 2 像素（长按不再连发，大幅度用滑动条）；滑动条松手、按钮松手、点「完成」时才写回配置。
- 整个模块里不再有 `postDelayed` / `Timer` / 轮询。仅剩 `TimeHook` 里两处 `MAIN.post`：播放器的停止 / 开始播放事件如果不在主线程到达，转回主线程处理（一次性，不是定时器）。

## 1.17.2：删掉 1 秒看门狗和进度条钩子
- 钩子已验证稳定，不再需要兜底：删除时长文字的 1 秒延时消息（看门狗）、`ProSeekBarView#setProgress` 钩子及相关字段。时长文字现在只由播放器回调驱动（进度 / 开始播放 / 停止 / 释放），没有任何定时器。
- 副作用：如果某个场景（如直播间）不触发 `onPlayStop` / `onPlayRelease`，文字不会自己消失。遇到的话告诉我是哪个场景。
- `TimeHook.SEEKBAR` 不再是版本相关项。

## 1.17.1：代码清理 + 省电（功能不变：弹幕随机色 / 拦截点赞开评论 / 视频时长常显含位置浮窗）
- 删除弹幕"按文字内容固定颜色"这套备选实现（`keyByText` + 反射读文字），只保留按弹幕对象固定颜色。
- `CommentHook` 两个入口合并成一个通用 `hook()`，回调各自独立成类。
- `TimeHook` 去掉诊断日志（`time ok` / `time stop/release ok` / `classes not found yet`）。
- 省电：所有钩子都挂上之后，`Activity.onResume` 里不再扫描类、不再尝试挂钩，只剩配置文件时间检查和时长文字的换页处理。

## 1.16.3：时长改挂播放器真实的状态回调（TextRec 1.22.4 追踪确认）
1.16.2 的关键字扫描已删除，换成追踪到的真实方法名。全部在 `OnUIPlayListenerDelegate`（`com.ss.android.ugc.aweme.video.simplayer.listener.base`，40.7.0 没混淆，第一个参数是视频 id）：
- **离开视频 → 立刻隐藏**：`onPlayStop` / `onPlayRelease`。划走时 60ms 内依次触发 `onPlayPause → onPausePlay → onPlayStop → onPlayRelease`；用户手动暂停只有 `onPlayPause` / `onPausePlay`，没有 stop / release，所以**暂停时文字保持显示**，不用靠猜。只处理当前视频的事件（旧视频迟到的 stop 不会把新视频的文字藏掉）。
- **新视频开始播放 → 立刻画出来**：`onPlaying(String id, PlayerEvent)`。PlayerEvent 的 `toString` 形如 `PlayerEvent{id='…', codecType=0, duration=26331, …}`，用正则取 `duration=`（不依赖字段名）画 `00:00 / 总时长`，比第一个进度回调早。一个参数的重载没有时长，只用来记下当前视频。
- 进度回调 `onPlayProgressChange` 照旧负责每秒更新。
- 每个事件会经过 `ManualResolutionSwitchMaskingPlayListener` / `BufferOptListener` / `PlayFailedFilterListener` / `ProgressOptListener` / `ListenerWithReporterSupplier` / `SafeOnUIPlayListenerReporter` 5~6 个包装监听器重复到达，处理都是幂等的。
- 离开后 1 秒内忽略该视频迟到的进度回调。
- **兜底**（直播间的切换还没有追踪验证过，不知道刷进直播间时视频是否也走 stop / release）：一条 1 秒的延时消息，1 秒没有进度回调才去看进度条，暂停时进度条还在屏幕上就继续显示。没有任何周期性轮询。验证过直播后可以整段删掉。
- 追踪里排除掉的方案：`Fragment#onResume` / `setUserVisibleHint` 划动期间只触发 2 / 0 次，不是每次翻页都有。
- 没有在真机上验证过；这版也只做过语法层面的检查。

## 1.16.2：（已被 1.16.3 取代）按关键字扫描状态回调

## 1.16.1：时长改成纯事件驱动（已被 1.16.2 取代）
- 去掉了 1.16.0 的 250ms 轮询；心跳 + 1 秒看门狗；翻页靠触摸 + 进度条位移推断。
- 清理无关代码：删除空的 `TabHook.java`、没人引用的 `drawable/ic_launcher.xml`、已废弃的 `timeDebug` 配置键，以及设置页里没用的 `Handler` / import。

## 1.16.0：时长任何界面都显示 / 位置改浮窗
- **任何界面都显示**：不再限定 `MainActivity`，抖音任意存活的 Activity 都会放这行时长（评论区、搜索进入的视频页等）。
- **位置改成浮窗**：设置页点「打开浮窗调整位置」→ 推送 `timeAdjust=true` 并启动抖音 → 抖音回到前台时在最上层弹出浮窗（标题栏可拖动）。浮窗里两条滑动条（水平 / 垂直），左端减、右端加，范围是半个屏幕；两端的 − / + 每点一下 2 像素，按住连续。改动实时看得到，0.4 秒后自动写回配置，点「完成」立即写回并关闭。回到模块设置页会用 root 读回数值，所以下次「保存并应用」不会把旧位置推回去。
- 位置单位由 dp 改为 **px**，配置键 `timeDx/timeDy` → `timeX/timeY`（旧值不再使用，需要重新调一次）；新增一次性键 `timeAdjust`。
- 去掉了设置页的方向键和「细/中/粗」步长。
- 清单里加了 `QUERY_ALL_PACKAGES`（Android 11+ 才能按包名找到抖音的启动入口）。
- 没有在真机上验证过；这版也只做过语法层面的检查。

## 1.15.1：时长改钩 SimPlayer 公共层
（同 1.15.0 的显示方式，数据来源见下）

## 1.15.0：视频时长改成自己画（不再借用抖音的时间控件）
- 旧做法（1.13～1.14.3）：让抖音拖动进度条时才出现的那行时间一直显示。问题：文字只在拖动时更新，播放时是不走的死数字，而且依赖大量混淆名（`X.0qVI` / `X.0qVF` 等），换版本就坏。已全部删除。
- 新做法：往当前 Activity 最上层放一个独立 TextView（白字黑描边，20sp，不拦截触摸），默认在屏幕正中，用设置页的方向键偏移（单位 dp，右、下为正）。
- 数据来源（TextRec 追踪，40.7.0，全是没混淆的名字）：`OnUIPlayListenerDelegate#onPlayProgressChange`（`com.ss.android.ugc.aweme.video.simplayer.listener.base`，SimPlayer 的公共监听层，播放时每次进度更新在主线程调用）。它有两个重载：`(float 百分比)` 和 `(String 视频id, long 位置ms, long 总时长ms)`，后者直接带时间，例如 `("7697697250122729935", 638, 25727)`。同一个位置会经过好几个包装监听器（`ManualResolutionSwitchMaskingPlayListener`、`BufferOptListener`、`ProgressOptListener`……）重复到达，按位置去重。
- 1.15.0 初版曾钩信息流专用的 `PMPlayListenerDispatcherV2` 再去认 `TTVideoEngine` 读数，有的视频不显示（认不出引擎 / 不走信息流这一层）；现在改钩上面这一层，不再需要认引擎。
- 刷新：只在显示的秒数变化时才 `setText`；没有轮询。
- 显示 / 隐藏：3 秒内收到过进度回调就显示；没有回调（暂停等）时，用 `ProSeekBarView#setProgress` 的 `this` 判断"还在视频页"——进度条还在屏幕上就继续显示，离开视频页后隐藏；每秒检查一次，隐藏后不再排队。没抓到进度条时，3 秒没有回调就隐藏。
- 去掉了诊断浮层开关（`timeDebug` 配置键保留但不再使用）。
- LSPosed 日志：第一次读到时间时写一行 `[DanmakuColor] time ok: pos=… dur=…`；类找不到时写 `time hook: classes not found yet`。
- 版本相关：`TimeHook.DELEGATE` / `PROGRESS` / `SEEKBAR`。
- 没有在真机上验证过；这版也只做过语法检查。

## 1.11：省电精简
- 删除全部检测 / 诊断代码：状态日志文件（原来每次都写磁盘）、「检查状态」、调用栈采集、EventBus / X.1DAj 诊断、手势回调日志。
- 删除首页顶部标签隐藏（TabHook）：它挂了 `TextView.setText` / `View.setVisibility` 两个全局高频方法，还会定时扫整棵 View 树。
- 删除红心的"兜底"拦截（`addView` / `setVisibility` / 动画 / `ViewStub` / Lottie 全局钩子）：1.10 已确认红心来自 `DiggLayout.LIZJ`，直接拦它即可。
- 配置只在 Activity.onResume 读一次，不再在每次绘制弹幕时检查文件时间。
- 弹幕上色热路径：DrawStyle 类型按 Class 引用比较（不再每次取类名比字符串）、手算 HSV→RGB（不再每帧每条弹幕分配数组）、去掉原子计数。
- 只在抖音主进程初始化，其它子进程不挂任何钩子。

## 1.12：双击只钩一个上游入口
- 根据 TextRec 的追踪（40.7.0）：一次双击里，`X.06y9.onTouch` 只调用 `BaseListFragmentPanel#LLLIILIL(MotionEvent)` 一次，红心（`DiggLayout.LIZJ`）和点赞（`observeVM$2.LIZ` → `X.1DAj` → `LJJLIIIJL`）都从它往下走。
- 现在直接跳过这一个方法并打开评论。删掉了：触摸层双击窗口（`dispatchTouchEvent` 钩子）、`GestureDetector` 监听、`observeVM$2.LIZ` 和 `DiggLayout.LIZJ` 两个下游拦截，以及所有时间窗逻辑。
- 如果双击后还是点赞了或出了红心，说明还有别的入口，把现象告诉我；1.11.1 是旧方案，可以回退。

## 抖音版本相关（换版本后可能要改）
- `TimeHook.DELEGATE` / `PROGRESS`：时长显示用到的播放器监听层类名和进度回调名。
- `CommentHook.DIGG_CLICK_METHOD`（`LJJJJJL`）：爱心按钮点击入口。
- `BaseListFragmentPanel#LLLIILIL(MotionEvent)`：双击的统一入口（红心和点赞都在它下面）。
- 评论按钮靠 contentDescription「评论N，按钮」查找。
- `PubHook.HOLDER` / `PubHook.AWEME`：发布日期地点（`pub.bind`）用到的 `VideoViewHolder#bind(Aweme…)` 和 `Aweme` 的 `aid` / `createTime` / `ipAttribution` / `city` 字段名；`PubHook.CITIES` 是行政区划代码表。
- `PubHook.DMT` / `PubHook.MENTION`：发布日期地点用到的文字控件类名（`DmtTextView#setText(CharSequence, BufferType)`、`MentionTextView`），以及文案结尾「4 个空格 + 日期 + 空格 + 地点」的格式。
