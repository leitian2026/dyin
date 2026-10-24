# 钩子与自检规范（给改这个项目的人 / AI：新增钩子、新功能前必读）

这个模块靠 Xposed 钩住抖音里的方法。抖音每次更新，**混淆过的名字会变**，钩子可能失效。
所以**每一个钩子都必须接入 `HookGuard`**：它负责「挂不上时怎么停、怎么记住、怎么告诉用户」。
新增钩子如果不接入，就等于没有自检——会出现：类一直找不到时每次回到前台都重试（耗电）、抖音更新后悄悄失效且没人知道、设置页看不到状态。

> 一句话规则：**凡是钩抖音自己的类，都走 `HookGuard`，并且在 3 个地方登记（编号 / 重试入口 / 设置页显示名）。**

---

## 1. 它怎么工作

每个钩子有一个编号（`HookGuard` 里的常量）和一个字符串 id（`HookGuard.IDS`），状态只有四种：

| 状态 | 含义 | 停止条件 / 之后怎么办 |
|---|---|---|
| **OK** | 挂上了 | 不再处理 |
| **NEW** | 还没结论（类还没找到） | 每次回到前台（`Activity.onResume`）再试一次 |
| **MISSING** | 类连续 `MAX_WAIT`(=6) 次都没找到 | 这次进程不再试。**不写成放弃**：类可能只是还没加载，下次启动再给机会 |
| **GAVE_UP** | 类找到了，但里面没有匹配的方法（抖音改了这段代码） | 立刻放弃，按「APK 大小 + 修改时间 + `RULES_REV`」记进 `files/hooks.properties`，之后每次启动**直接跳过**。抖音更新（APK 变了）或 `RULES_REV` 加一才会重试 |

- 所有钩子都有结论（`HookGuard.allSettled()`）之后，`DanmakuHook.hookResume` 里直接 `return`：不再扫描类、不再挂钩。
- `hooks.properties` **只在状态有变化时才写**，稳态不写文件。
- 设置页「钩子状态」按钮用 root 读这个文件，显示 ✓ 可用 / ✗ 未适配（附原因）/ … 还没挂上。

`hooks.properties` 格式（Properties，ASCII，中文被转成 `\uXXXX`）：

```
sig=<apk大小>-<apk修改时间>-<RULES_REV>
ver=40.7.0 (400701)
paint=ok
paint.note=宽松匹配            # 可选，ok 时显示在括号里
dtap.cut=gaveup
dtap.cut.note=MonitorScrollFrameLayout 里没有 onInterceptTouchEvent(MotionEvent)（……）
time.stop=missing
```

LSPosed 日志里会出现两种关键行（只在状态变成「放弃」「不再重试」时各打一次）：
`[DanmakuColor] 钩子未适配 <id>: <原因>` 和 `[DanmakuColor] 钩子类没找到 <id>（这次进程不再试）`。

## 2. 文件分工

| 文件 | 职责 |
|---|---|
| `HookGuard.java` | 钩子编号 / id 清单、状态、停止条件、落盘、`find` / `hook` / `ok` / `miss` / `waitFail` |
| `DanmakuHook.java` | 入口 `init(lp)`、`hookResume()`（每次回到前台的重试入口）、弹幕 `paint` 钩子（分档匹配示例） |
| `TimeHook.java` | 时长进度：1 个钩子 `time.progress`（`OnUIPlayListenerDelegate#onPlayProgressChange`，单方法按名字匹配的示例），把 id / 位置 / 总时长转给 `PubHook.onProgress` |
| `CommentHook.java` | 双击改评论：3 个没混淆的钩子（`dtap.seen` 系统方法、`dtap.cut` 在拦截判断里改结果，两个都挂上才生效；`dtap.click` 跳过随后的单击暂停）+ `dtap.btn`（只找评论按钮的类 `FeedCommentImageView`，不挂钩，类没了设置页能看到）。评论按钮在被双击的这一页（`dtap.cut` 回调里的 `this`）的子树里按类名找，点它的**父级**；找不到就放行，双击照常点赞 |
| `MainActivity.java` | 设置页（折叠分组 `Section`）；`HOOK_NAMES` 是「钩子 id → 中文名」，`describeHooks` 解析 `hooks.properties` |
| `PubHook.java` | 发布日期地点、视频时长、文案样式。核心钩子 `pub.bind`（`VideoViewHolder#bind(Aweme, …)` after：按运行时类型取页面根 `VideoViewHolderRootView`，发布信息 / 时长两个浮层盖在页面根上（位置全屏可调），aid → 页面登记在 `PAGES` 里给进度回调用；3 个类（holder / Aweme / 页面根）按名字找，缺哪个都是「类没找到」）；文案样式和上顶另外 3 个：`pub.anchor`（只找类不挂钩：两个文案类、容器 `AwemeIntroInfoLayout`、页面根 `VideoViewHolderRootView`；上顶和文案下方位置不再依赖 `pub.bind` 装成功）、`pub.text`（`DmtTextView#setText` 通用控件，回调里先比较 `this` 的类；文案样式在这里套，**容器也从这里来**：文案第一次认出时沿父级找到 `AwemeIntroInfoLayout`，往上找 `VideoViewHolderRootView` 登记这一页，接手容器——整个容器 `translationY` 上移，下面留出空位给发布地和时长；1.30.0 起没有 `pub.intro` 和「从根往下找」，容器只有这一个入口；文案样式、上顶、文案下方位置有一个开着就挂）、`cap.draw`（`MentionTextView#onDraw`）、`name.draw`（`DmtTextView#onDraw`，作者名改色 / 灰色底，只认 `AwemeIntroInfoLayout` 里 id=title 的那个） |
| `TabHook.java` | 首页顶部标签隐藏：类名按没混淆的 `HomeTopTabItemViewNew` 找（走 HookGuard），挂框架方法 `View.setContentDescription` 并按这个类过滤 |
| `IdleExit.java` | 无操作自动退出：钩 `Activity.onUserInteraction`（框架方法，不走 HookGuard），记最后操作时刻；一条延时消息到点检查，够了结束进程 |
| `LiveHook.java` | 直播间开播时间：钩 `LivePlayFragmentV2#onViewCreated`（记页面）和 `Room#getCreateTime`（触发刷新），文字叠在页面根控件的 ViewOverlay 上，位置按百分比放；格式在 `LiveFormat.java` |
| `LivePosPanel.java` | 开播时间的位置浮窗（不是钩子）：设置页点「去抖音里调整」后配置里 `liveAdjust=true`，`LiveHook` 刷新时把屏幕上的直播页交给它，它在窗口最上层盖触摸层 + 小浮窗，拖动 / 微调 / 完成 / 取消；只在调节模式里存在，没有定时器 |
| `VidPosPanel.java` | 发布日期地点行 / 视频时长行的位置浮窗（不是钩子）：`vidAdjust=true` 时 `PubHook.onResume` 把当前 Activity 交给它，盖触摸层 + 小浮窗，拖动调浮层位置 `pubX/pubY/timeX/timeY`（dp）；只在调节模式里存在，没有定时器 |
| `ProfHook.java` | 个人主页默认「喜欢」：2 个钩子。`prof.pager`（`ViewPager#setCurrentItem` 两个重载，记 `ProfileViewPager` 的弱引用；冷启动计数期间把抖音自己恢复标签的 `(0)` 改成 99）、`prof.enter`（`MainPageFragment$2#onChanged`，参数 Pair 以 `(USER,` 开头 = 点底部「我」，触发时对记下的分页控件调 `setCurrentItem(99, false)`；进程里第一次触发还把计数设成 `COLD_RESTORES`（3），`(HOME,` 清零）。没有扫描、没有点击检测、没有定时器 |
| `RootShell.java` | `readFile(pkg, name)` 用 root 读抖音数据目录里的文件 |

## 3. 新增一个钩子：必须做的 6 步

### 第 0 步：先取证，不许猜
不知道的类名 / 方法名 / 参数，**先用 TextRec 追踪**（`类#方法`、`@stack`、按任务阶段看调用次数），把证据写进代码注释
（哪个版本、触发时机、参数样子、调用栈上下各一层），像 `TimeHook` / `CommentHook` 头部注释那样。
没有证据就不要写钩子；猜出来的钩子挂错地方比没挂更糟。

### 第 1 步：登记编号和 id（在 `HookGuard.java`）
```java
static final int ..., FOO = 7;                       // 编号 = 它在 IDS 里的下标，接着现有的往后加
static final String[] IDS = {..., "foo.something"};  // 第 8 项（下标 7）
```
- **常量的值必须等于它在 `IDS` 里的下标**，两处要一起改。
- 一个 id 对应「一个会独立失效的东西」。例如 `PubHook` 里发布信息 / 时长的 `pub.bind` 和文案样式的 `pub.text`、`pub.anchor`、`cap.draw` 是 4 个 id，因为它们各自可能失效、互不牵连；同一个方法的多个重载算 1 个 id。
- id 是写进 `hooks.properties` 的名字，改名等于新钩子；删掉 id 后文件里的旧键下次写入时会被丢弃。

### 第 2 步：写挂钩代码
**模板 A：按类名 + 方法名挂（名字没混淆，或者混淆但不允许猜）**
```java
final class FooHook {
    private FooHook() {}

    private static final String CLS = "com.ss.android.ugc.aweme.xxx.Yyy";   // 版本相关，注释里写明依据
    private static final XC_MethodHook HOOK = new Hook();

    /** 启动时和每次回到前台都会被调用；HookGuard 保证已有结论后立刻返回，重复调用没有开销。 */
    static void tryHook(ClassLoader cl) {
        if (cl == null) return;
        HookGuard.hook(HookGuard.FOO, CLS, HookGuard.named("onSomething"), HOOK,
                "Yyy 里没有 onSomething（说明抖音改了什么，需要重新追踪什么）", cl);
    }

    private static final class Hook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            try {
                if (!DanmakuHook.cfg.fooEnabled) return;   // 开关在回调里判断，不影响「钩子是否挂上」
                // ……
            } catch (Throwable ignored) {                  // 钩子回调里的异常绝不能抛回抖音
            }
        }
    }
}
```
需要只挂某些重载时，传自己的 `HookGuard.Filter`（见 `CommentHook.motion`）。

**模板 B：分档匹配（只有参数 / 返回类型都是没混淆的类型时才允许放宽）**
```java
static synchronized void tryInstall(ClassLoader cl) {
    if (cl == null || HookGuard.settled(HookGuard.FOO)) return;
    Class<?> c = HookGuard.find(HookGuard.FOO, CLS, cl);   // 类没找到：HookGuard 已记一次等待，直接返回
    if (c == null) return;
    int n;
    boolean relaxed = false;
    try {
        n = hookStrict(c);                                  // 第 1 档：追踪确认过的精确签名
        if (n == 0) { n = hookRelaxed(c); relaxed = n > 0; }// 第 2 档：放宽，位置按类型找
    } catch (Throwable t) {                                 // getDeclaredMethods 可能因为依赖的类没就绪而抛错
        HookGuard.waitFail(HookGuard.FOO);                  // 这是「等」，不是「放弃」
        return;
    }
    if (n > 0) HookGuard.ok(HookGuard.FOO, relaxed ? "宽松匹配" : "");
    else HookGuard.miss(HookGuard.FOO, "……没有……（说明什么变了）");
}
```
完整示例：`DanmakuHook.tryInstall` + `hookPaint`（颜色和 `DrawStyle` 的位置按类型找，不写死下标）。

### 第 3 步：接入重试入口（`DanmakuHook.java`，**两处都要加**）
```java
// DanmakuHook.init(lp) 里，HookGuard.init(lp) 之后：
FooHook.tryHook(lp.classLoader);

// DanmakuHook.hookResume() 里，和 CommentHook / TimeHook 并列（在 allSettled() 判断之后）：
FooHook.tryHook(cl);
```
漏了第二处：启动时没挂上就永远不会重试。`HookGuard.init(lp)` 必须在所有 `tryHook` 之前。

### 第 4 步：设置页显示名（`MainActivity.HOOK_NAMES`）
```java
{"foo.something", "功能名：哪一项"},
```
漏了：状态文件里有，但设置页看不到。数组顺序就是显示顺序。

### 第 5 步：失败时必须能降级
钩子没挂上时，功能要**安静地失效**：不崩溃、不改变抖音原本的行为（点赞照常、弹幕保持原色、文字不显示）。
拦截类的钩子（`setResult` 跳过原方法）尤其要想清楚：找不到辅助目标时要放行原方法（见 `CommentHook` 里找不到评论按钮时的处理）。

### 第 6 步：文档和 `RULES_REV`
- `README.md` 加一节更新说明：新钩子的依据、怎么匹配、失败时怎么降级。
- **改了某个已有钩子的匹配规则，`HookGuard.RULES_REV` 加一**，之前放弃过的钩子才会重新试。只加新钩子、不改旧规则时不用加。

## 4. 硬性规则（违反就是没做自检）

1. **钩抖音自己的类，必须走 `HookGuard`**（`hook`，或者 `find` + `ok` / `miss` / `waitFail`）。不要自己写 `Class.forName` + `hookMethod` + `xxxHooked` 布尔变量。
2. **不要自己维护重试状态**：禁止 `volatile boolean installed / givenUp / hooked`、自己的重试计数、自己的 `try again` 逻辑。
3. **「类没找到」和「类在但方法没匹配」必须分开**：前者 `waitFail`（有次数上限，不写放弃），后者 `miss`（立刻放弃并记住）。反过来会导致要么永远重试、要么类晚加载时被误判成不适配。
4. **混淆过的名字不许放宽匹配**。只按签名去猜混淆方法，猜错了会误伤（例如把每次触摸都当成双击）。拿不准就不挂，`miss` 掉。
5. **放宽匹配只能用没混淆的类型做依据**（`Canvas`、`DrawStyle`、`MotionEvent`、`long` 等）；结果有歧义（多个得分接近的候选）就**一个都不挂**。
6. **没有定时器、没有轮询、没有周期性自检。**（唯一例外：`IdleExit` 的那一条延时消息——它是「无操作自动退出」功能本身，不是自检；不要把它当先例，更不要拿它来做钩子自检。） 自检只发生在 `tryHook`（启动 + 每次回到前台），且有次数上限。不要为了「检测是否还有效」去加 `Handler.postDelayed` / `Timer`。
7. **热路径（钩子回调里）不分配对象、不反射、不打日志、不写文件。** 自检、日志、落盘都只发生在安装阶段。
8. **钩子回调整体 `try { … } catch (Throwable ignored) {}`**，异常不能抛回抖音。
9. **开关在回调里判断**，不要因为开关关着就不挂钩子。`HookGuard` 的状态含义是「钩子能不能装上」，不是「功能开没开」。
10. **只有 `HookGuard.flush` 可以写 `hooks.properties`**，别处不要写。
11. **未登记的钩子会被 `allSettled()` 误伤**：`allSettled()` 只看已登记的钩子，它们都有结论后 `hookResume` 就直接返回。如果你的钩子没登记、又靠 `hookResume` 重试，重试会被提前切断。所以不走 `HookGuard` 的钩子，要么登记，要么自己保证不依赖 `hookResume`。

## 5. 什么时候不用走 `HookGuard`

- 钩 **Android 框架 / Java 标准库**里一定存在的方法，例如 `DanmakuHook.hookResume` 里的 `Activity.onResume`、`IdleExit.install` 里的 `Activity.onUserInteraction`、`TabHook` 里的 `View.setContentDescription`（后者必须先用 `HookGuard.find` 确认抖音的目标类存在才挂，找不到类就不挂这个全局钩子）。
- 不是钩子的功能：设置页、配置读写、`PubFormat`（发布信息模板）、`RootShell`。
- 这两类之外，一律走 `HookGuard`。

## 6. 提交前自测清单（逐条打勾）

- [ ] `grep -rn "hookMethod\|hookAllMethods" app/src` 里，除了 `HookGuard.java`、`Activity.onResume`、`Activity.onUserInteraction`、`View.setContentDescription`（TabHook）、以及分档匹配里由 `tryInstall` 汇报结果的那一处，没有别的直接挂钩。
- [ ] 新常量的值 == 它在 `IDS` 里的下标；`IDS` 长度 == 常量个数。
- [ ] `tryHook` / `tryInstall` 在 `DanmakuHook.init` **和** `DanmakuHook.hookResume` 里都有调用。
- [ ] `MainActivity.HOOK_NAMES` 里有这个 id。
- [ ] 「类没找到」走 `waitFail`（或 `hook` / `find`），「方法没匹配」走 `miss`，并且 `miss` 的原因里写明**要重新追踪什么**。
- [ ] 没有新增 `Handler.postDelayed` / `Timer` / 轮询；没有新增自己的 `xxxHooked` 状态变量。
- [ ] 钩子回调里没有反射、对象分配、日志、文件写入；整体有 `try/catch Throwable`。
- [ ] 钩子没挂上时功能安静失效，抖音原有行为不变。
- [ ] 改了已有钩子的匹配规则 → `RULES_REV` 已加一。
- [ ] `README.md` 有更新说明。
- [ ] 模拟「名字变了」：把方法名常量故意改错 → 第一次运行后设置页应显示 ✗ 未适配 + 原因，之后再回到前台**不再**重复 `Class.forName`（`hooks.properties` 里有 `<id>=gaveup`）；改回正确名字并把 `RULES_REV` 加一 → 应恢复 ✓。

## 7. 常见错误

| 错误 | 后果 |
|---|---|
| 新钩子直接 `XposedBridge.hookMethod`，没登记 | 没有停止条件和状态记录；设置页看不到；类找不到会无限重试 |
| 常量值和 `IDS` 下标对不上 | 状态写到别的钩子名下，设置页显示错位 |
| 只在 `init` 里调用 `tryHook`，没加进 `hookResume` | 启动时没挂上就永远不重试 |
| 类没找到就 `miss` | 类晚加载时被永久判成不适配（直到抖音更新） |
| 方法没匹配只 `waitFail` | 永远重试到上限，且每次进程都重来 |
| 用开关控制要不要挂钩子 | 状态变成「功能没开」而不是「钩子能不能装」，且开关打开后不会补挂 |
| 为了「更及时发现失效」加定时器 | 违反省电原则；自检只能发生在 `tryHook` |
| 回调里 `new` 对象 / 反射 / 打日志 | 每帧每次都执行，拖慢抖音、耗电 |
| 给混淆方法名做「只按签名」的放宽匹配 | 猜错后误伤其他行为 |


## 按开关才挂钩子（1.28.7）

高频或全局的钩子（`time.progress`、`tab.hide`、`live.room`、`pub.text`、`cap.draw`）在对应功能没开时不挂：`tryHook` 里先看配置，没开就直接返回，**不调 `HookGuard`**（状态保持「还没有结论」，不算等待失败）。功能打开后回到前台，`DanmakuHook.hookResume` 会再走一遍 `tryHook` 把它挂上。新增高频钩子时也照这个写，并在回调第一行仍然先看开关。
