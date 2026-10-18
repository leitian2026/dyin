# DanmakuColor — 抖音弹幕随机色 + 点赞改打开评论 + 视频时长常显（LSPosed 模块）

## 功能
1. **弹幕随机色**：每条弹幕随机颜色，保留黑色描边。
2. **拦截点赞**：右侧爱心按钮 / 双击视频不再点赞，也不出双击红心。
3. **打开评论**：上面两种操作改为打开当前视频的评论。
4. **视频时长常显**（1.13）：沿用抖音拖动进度条时显示的 `00:12 / 03:45`，播放时一直显示；设置页的方向键可以上下左右调位置。

## 构建（手动）
仓库根目录放好所有文件 → Actions → **Build Release APK (manual)** → Run workflow → 下载 `DanmakuColor-release`。

## 使用
1. LSPosed 启用模块，作用域勾选抖音，强杀抖音重开。
2. 打开模块设置，点「保存并应用」（需要 root），抖音回到前台即生效。不推送则用默认：开启 + 鲜艳随机。

## 1.13：视频时长常显 + 位置可调
- 根据 TextRec 的追踪（40.7.0）：`ProTimeDescViewComponent$onCreate$1#onChanged` 播放时每帧更新时间文字；`$onCreate$2#onChanged(Boolean)` 只在拖动开始(true) / 松手(false) 时调用，是显示开关。
- `$onCreate$2`：参数一律改成 `true`，松手后时长不再收起。
- `$onCreate$1`：借每帧回调找到组件持有的时间 View（沿观察者 → 外层组件 → 字段，最多 3 层，挑出显示“分:秒”的 TextView），强制可见并做 translation 偏移。找到之后每 16 次回调才复查一次，没有任何轮询 / 定时器。
- 位置：设置页方向键，相对抖音原位置，单位 dp，右 / 下为正；步长 2 / 10 / 40，长按连续移动，可归零。改完点「保存并应用」，抖音回到前台生效。
- 关掉开关：下一次回调里偏移归零、可见性还原。
- 找不到时间 View 时只在 LSPosed 日志里写一行 `[DanmakuColor] time view not found ...`（每个组件一次），找到时写一行 `time view ok ... text="..."`（每个进程一次）。
- 版本相关：`TimeHook.COMP`（组件类名）。

## 1.13.1：诊断浮层
- 设置页「显示诊断信息」：抖音左上角出一小块黑底绿字（独立 TextView，不依赖抖音的时间 View 显不显示），内容：两个钩子有没有挂上、状态、屏幕尺寸、时间 View 的屏幕坐标 / 宽高 / 可见性 / 透明度 / 是否在屏内、偏移，以及往上第一个被隐藏的祖先。
- 同时给时间 View 涂半透明红底，文字为空或透明时也能看到这块区域。调好位置后关掉。
- 也可以边调位置边看它的坐标。

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
- `CommentHook.DIGG_CLICK_METHOD`（`LJJJJJL`）：爱心按钮点击入口。
- `BaseListFragmentPanel#LLLIILIL(MotionEvent)`：双击的统一入口（红心和点赞都在它下面）。
- 评论按钮靠 contentDescription「评论N，按钮」查找。
