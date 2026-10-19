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
