# dyin（DanmakuColor）— 抖音弹幕随机色 + 点赞改打开评论 + 视频时长常显（LSPosed 模块）

## 功能
1. **弹幕随机色**：每条弹幕随机颜色，保留黑色描边。
2. **拦截点赞**：右侧爱心按钮 / 双击视频不再点赞，也不出双击红心。
3. **打开评论**：上面两种操作改为打开当前视频的评论。
4. **视频时长常显**（1.15）：自己画一行 `00:12 / 03:45`，默认在屏幕正中，随播放走动；1.16 起抖音任何界面都显示，位置用抖音里的浮窗（两条滑动条）调整。

## 构建（手动）
仓库根目录放好所有文件 → Actions → **Build Release APK (manual)** → Run workflow → 下载 `DanmakuColor-release`。

## 使用
1. LSPosed 启用模块，作用域勾选抖音，强杀抖音重开。
2. 打开模块设置，点「保存并应用」（需要 root），抖音回到前台即生效。不推送则用默认：开启 + 鲜艳随机。

## 1.16.3：时长改钩播放分发器，状态方法用 TextRec 追到的真名
TextRec 追踪（40.7.0）发现：`DIPlayListenerDispatcher`（`com.ss.android.ugc.aweme.o.oplayer.util`，主线程）是播放事件的共用入口，进度和状态回调都经过它，**每个事件只到一次**；1.16.2 挂的 `OnUIPlayListenerDelegate` 在它下面，每个事件会被 5～6 个包装监听器重复收到（进度一次 8～9 遍）。
- 进度：`onPlayProgressChange(String id, long 位置ms, long 总时长ms)`（同上）。
- 状态（真实名字，不再按关键字猜）：`onPlayStop` / `onPlayRelease` 隐藏；`onPausePlay` / `onPlayPause` 暂停；`onPlaying` / `onRenderFirstFrame` 新视频开始，立刻显示，`onPlaying` 的 `PlayerEvent` 里读 `duration` 作为总时长。`onPreparePlay` 不用（预加载的视频也会走）。
- 一次翻页的事件顺序（追踪实测，同一秒内）：旧视频 `onPlayStop` → `onPlayRelease`，新视频 `onPreparePlay` → `onPlaying`，约 1 秒内 `onRenderFirstFrame`。
- 没有在真机上验证过。如果翻页过程中（手指还在滑）文字不消失，是因为这些事件都在翻页落定时才到，需要再追页面滑动的信号。

## 1.16.2：时长改听播放器自己的状态回调
1.16.1 靠「回调停了」「进度条在不在」「手指有没有滑」去推断，本质还是模块在猜。现在改成直接钩播放器自己报的状态变化：
- 在 `OnUIPlayListenerDelegate`（和进度回调同一个类）里，启动时扫描除 `onPlayProgressChange` 之外的所有 `on*` 方法，按方法名关键字归类（`TimeHook.kindOf`）：
  - 名字含 stop / release / fail / error → **立刻隐藏**（带视频 id 且不是当前视频的事件忽略，免得旧视频迟到的 stop 把新视频的文字藏掉）；
  - 含 pause → 150ms 后看一次进度条：还在屏幕上是用户手动暂停，保持显示；已经走了是翻页离开，隐藏（翻页动画没走完再看两次）；
  - 含 firstFrame / renderStart → **新视频首帧出来就立刻画出来**（先显示 `00:00 / --:--`，第一个进度回调到了换成真实时长）；
  - 带 buffer / load / seek / cache / resolution / speed 的不算。
- **这些回调的名字没有追踪过，是按关键字猜的。** 所以启动时把扫到的方法名写进 LSPosed 日志（`[DanmakuColor] time events hooked: …`），并把每个事件前两次触发的参数也写进去（`[DanmakuColor] ev 方法名 kind=… args=(…)`）。如果某类事件没生效，把日志发出来，按真实名字改 `kindOf`。
- 保留：1 秒看门狗兜底（只有一条延时消息，没有轮询）、换视频后 1 秒内忽略旧视频进度回调。
- 删除：1.16.1 的触摸钩子和翻页检测。

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
- `TimeHook.DELEGATE`（播放分发器）/ `PROGRESS` / `SEEKBAR`，以及 `TimeHook.kindOf` 里的状态方法名：时长显示用到的类名和方法名。
- `CommentHook.DIGG_CLICK_METHOD`（`LJJJJJL`）：爱心按钮点击入口。
- `BaseListFragmentPanel#LLLIILIL(MotionEvent)`：双击的统一入口（红心和点赞都在它下面）。
- 评论按钮靠 contentDescription「评论N，按钮」查找。
