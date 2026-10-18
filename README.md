# dyin（DanmakuColor）— 抖音弹幕随机色 + 点赞改打开评论 + 视频时长常显（LSPosed 模块）

## 功能
1. **弹幕随机色**：每条弹幕随机颜色，保留黑色描边。
2. **拦截点赞**：右侧爱心按钮 / 双击视频不再点赞，也不出双击红心。
3. **打开评论**：上面两种操作改为打开当前视频的评论。
4. **视频时长常显**（1.15）：自己画一行 `00:12 / 03:45`，默认在屏幕正中，随播放走动；设置页的方向键可以上下左右调位置。

## 构建（手动）
仓库根目录放好所有文件 → Actions → **Build Release APK (manual)** → Run workflow → 下载 `DanmakuColor-release`。

## 使用
1. LSPosed 启用模块，作用域勾选抖音，强杀抖音重开。
2. 打开模块设置，点「保存并应用」（需要 root），抖音回到前台即生效。不推送则用默认：开启 + 鲜艳随机。

## 1.15.0：视频时长改成自己画（不再借用抖音的时间控件）
- 旧做法（1.13～1.14.3）：让抖音拖动进度条时才出现的那行时间一直显示。问题：文字只在拖动时更新，播放时是不走的死数字，而且依赖大量混淆名（`X.0qVI` / `X.0qVF` 等），换版本就坏。已全部删除。
- 新做法：往当前 Activity 最上层放一个独立 TextView（白字黑描边，20sp，不拦截触摸），默认在屏幕正中，用设置页的方向键偏移（单位 dp，右、下为正）。
- 数据来源（TextRec 追踪，40.7.0，全是没混淆的名字）：
  - `PMPlayListenerDispatcherV2#onPlayProgressChange`：播放时每次进度更新在主线程调用，作为刷新时机；
  - `TTVideoEngine#getCurrentPlaybackTime()` / `getDuration()`：毫秒。进度回调执行期间被调用的那个 `TTVideoEngine` 就是当前正在播的，记住它，回调结束后读位置和时长。
- 刷新：只在显示的秒数变化时才 `setText`；没有轮询。
- 显示 / 隐藏：`ProSeekBarView#setProgress` 的 `this` 作为"还在视频页"的标志——进度条还在屏幕上就一直显示（暂停也不消失），离开视频页后隐藏；每秒检查一次，隐藏后不再排队。没抓到进度条时，3 秒没有进度回调就隐藏。
- 去掉了诊断浮层开关（`timeDebug` 配置键保留但不再使用）。
- LSPosed 日志：第一次读到时间时写一行 `[DanmakuColor] time ok: pos=… dur=…`；类找不到时写 `time hook: classes not found yet`。
- 版本相关：`TimeHook.PM` / `PM_METHOD` / `ENGINE` / `SEEKBAR`。
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
- `TimeHook.PM` / `PM_METHOD` / `ENGINE` / `SEEKBAR`：时长显示用到的进度回调、播放器引擎、进度条类名。
- `CommentHook.DIGG_CLICK_METHOD`（`LJJJJJL`）：爱心按钮点击入口。
- `BaseListFragmentPanel#LLLIILIL(MotionEvent)`：双击的统一入口（红心和点赞都在它下面）。
- 评论按钮靠 contentDescription「评论N，按钮」查找。
