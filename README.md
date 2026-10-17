# DanmakuColor — 抖音弹幕随机颜色（LSPosed 模块）

让抖音弹幕每条使用随机颜色，保留黑色描边。

## 构建（手动，不会自动构建）
1. 解压本压缩包，所有文件上传到 GitHub 仓库根目录（`.github/workflows/build.yml` 路径要保留）。
2. 仓库页面 → Actions → **Build Release APK (manual)** → Run workflow。
3. 构建完成后在运行页面底部 Artifacts 下载 `DanmakuColor-release`，里面是已签名、已开启 R8 压缩优化的 release APK。
4. 签名用仓库里固定的 `randcolor.jks`，之后每次构建都能覆盖安装。

## 使用
1. 安装后 LSPosed 里启用「弹幕随机色」，作用域勾选抖音，强杀抖音重开。
2. 默认就是 开启 + 鲜艳随机，直接看有弹幕的视频即可。
3. 想换样式：打开模块，选好后点「保存并推送配置」（root），抖音回前台约 2 秒内生效。
4. 没效果：点「检查状态」，会告诉你是 没加载 / 没挂接 / 版本不兼容。

## 原理
抖音弹幕由 Jetpack Compose 绘制，每条弹幕调用两次 `MultiParagraph.paint(...)`：
先 `Stroke`（黑色描边），再 `Fill`（白色填充）。模块在「Stroke 之后紧跟的那次 Fill」里替换颜色参数，
颜色由弹幕对象（或文字）的哈希算出，同一条弹幕每帧颜色固定、不会闪。
方法按签名匹配（不写死 `paint-LG529CI` 这种带哈希的名字），Compose 小版本变化一般不受影响。
全程事件驱动：配置只在抖音事件触发时检查文件修改时间（≤2 秒一次）。

## 1.1：隐藏首页顶部标签
1. 强杀抖音重开，停在首页等顶部标签栏出现（模块会把标签文字记下来）。
2. 打开模块 → 「读取抖音当前的标签（root）」→ 列出标签，**勾选 = 隐藏** → 「保存并推送配置」。
3. 抖音回到前台即生效，取消勾选再推送就恢复。「检查状态」里会有一行“顶部标签 N 个，隐藏 M 个”。
- 只隐藏标签栏上的那一项，首页左右滑动仍会切到对应页面。
- 识别靠标签条目的类名 `HomeTopTabItemViewNew`（没混淆）+ 标签文字，不依赖混淆名；抖音改了这个类名就会失效，把「检查状态」发给我。

## 1.2：界面重做
- 卡片式设置页：怎么用（三步）、目标 App、弹幕颜色（分段按钮）、首页顶部标签（点选胶囊，蓝色 = 隐藏）。
- 底部固定操作栏：左「检查状态」，右「保存并应用」；结果以绿 / 红 / 灰色状态卡片显示，操作中按钮会变灰。
- 跟随系统深浅色；纯框架控件，不增加任何依赖。

## 1.3：点赞 → 打开评论（实验）
- 「点右侧爱心按钮时打开评论」：拦掉 FeedDiggPresenter#LJJJJJL（40.7.0，TextRec 记录 #316 的点击入口），改为点一下评论按钮。只在栈里有点击/触摸事件时才拦，切视频时不会误触。
- 「双击视频时打开评论（实验）」：通用办法，监听系统 GestureDetector 的 onDoubleTap；抖音没用它做双击就不生效，「检查状态」会列出识别到的双击监听类。
- 评论按钮靠 contentDescription“评论N，按钮”（TextRec 记录里的 LinearLayout id=e9l）找，找不到就不拦截，点赞照常。
- 换抖音版本后，LJJJJJL 这个名字要用 TextRec 重新记录，改 CommentHook.DIGG_CLICK_METHOD。

## 1.4：双击也拦 onDoubleTapEvent
- 1.3 只拦了 onDoubleTap，双击能开评论但点赞仍会发生（点赞写在后续的 onDoubleTapEvent 里）。现在两个回调都接管：双击开始时打开评论（只打开一次），第二次点按的移动 / 抬起也一并拦掉。
- 诊断：双击之后如果点赞刷新（FeedDiggPresenter#LJJLIIIJL）仍被调用，“检查状态”会记下调用栈，用来定位真正的点赞入口。

## 1.5：状态记录修复
- 沙箱子进程（sandboxed_process）不再初始化。以前它也往同一个状态文件里写，还会在文件超过 8K 时把整个文件删掉，主进程的行（包括双击监听记录）就被一起丢了，“检查状态”只剩子进程的几行。
- 状态文件超长时改为只保留最近约 8KB。
- 双击诊断日志每 2 分钟可再记一次；“检查状态”把识别到的双击回调逐个列出来。

## 1.6：定位双击点赞的真正入口（诊断）
- 1.5 的日志：双击回调是 X.1cy3#onDoubleTap / onDoubleTapEvent，已被拦下并打开了评论，但点赞仍发生（LJJLIIIJL 被 Handler 的 X.1DAj.run 和 EventBus 的 subscribeDiggUpdateEvent 调用）。
- 新增只记不改的诊断：谁发出了点赞相关的 EventBus 事件、X.1DAj 在哪里创建、双击监听类的 onSingleTapUp / onSingleTapConfirmed 是否被触发；调用栈改为完整类名并跳过钩子帧。

## 1.7：双击不再点赞
- 根据 1.6 日志：双击回调拦住后点赞仍发生，真正的发起点是 FeedDiggPresenter$observeVM$2.LIZ（它在 Handler 里创建 X.1DAj 执行点赞）。
- 新增：「双击→评论」开着时，双击后 1.5 秒内跳过 observeVM$2.LIZ，点赞不会发出；按钮点赞、切视频等其它情况原样放行。
- 找不到评论按钮时也不再放行点赞（以前会放行）。
- 日志里出现「已拦下双击点赞」就说明拦截生效；如果出现「observeVM$2 里没有叫 LIZ 的方法」，是抖音版本变了。

## 1.8：挡掉双击红心动画
- 「双击→评论」开着时，双击后 1.5 秒内：红心类 View（类名 / id 含 heart、digg、like、praise、love、lottie 等）不让加入界面、不让变成可见，Lottie 不让播放。
- 同时把窗口内新增的 View 类名记进状态（「双击后新增 View」），第一版没挡住时据此精确定位。

## 1.9：红心动画诊断
- 1.8 日志：双击后新增的 View 全是 LayoutInflater 预加载的 ViewStub / 布局，不是红心；点赞拦截已生效。
- addView 不再记布局预加载；新增记录「ViewStub 展开」「属性动画开始（含目标 View）」「帧动画」「View 动画」「Lottie 播放」，名字像红心的直接挡掉。

## 1.9.1：修正红心拦截的误判
- 1.9 把类名里带 lottie 的 View 一律当成红心：布局预加载时加进界面的 Lottie（LayoutInflater 里的 addView）也被挡了，状态里的“✓ 已挡掉”是误判，还可能让界面缺控件。现在布局预加载阶段的 addView 一律不拦、不记。
- 抖音用的 Lottie 是字节自己的分支 com.bytedance.lottie.LottieAnimationView（1.9 只挂了 com.airbnb 那个）。现在两个都挂：双击后 1.5 秒内的 playAnimation / resumeAnimation 直接跳过。
- Lottie 的可见性变化（setVisibility）在窗口内会被改成不可见。

## 1.9.2：日志别被噪音挤满
- 1.9.1 的日志里，双击后一堆“新增 View”其实是抖音 X2C / AndInflater 在代码里预加载布局（调用栈里没有 LayoutInflater 帧，过滤漏掉了）。现在 com.by.inflate_lib / nita.inflate 也算预加载，不记。
- 评论面板（comment / comment_split）打开 / 收起时的动画不再记。
- 调用栈只留 9 帧；状态文件上限从 16KB 提到 64KB（超出保留最后 32KB）。之前“已挂接”那几行被噪音挤掉，检查状态才会显示“还没挂接”。
