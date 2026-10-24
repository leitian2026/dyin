# 给 AI 的说明（改这个项目前先读完）

这是一个 LSPosed 模块，钩抖音里的方法。抖音更新后混淆名会变，所以**所有钩子都必须有自检**。

**新增钩子 / 新功能前，必须先读 [`HOOKS.md`](HOOKS.md)，并严格照它的「6 步」和「硬性规则」做。** 最低要求：

1. 钩抖音自己的类，一律走 `HookGuard`（`hook`，或 `find` + `ok` / `miss` / `waitFail`）；不要自己写 `Class.forName` + `hookMethod` + `xxxHooked` 变量。
2. 登记三处：`HookGuard` 的编号常量 + `IDS`（常量值 == 下标）；`DanmakuHook.init` **和** `DanmakuHook.hookResume` 里调用你的 `tryHook`；`MainActivity.HOOK_NAMES` 里加中文名。
3. 「类没找到」用 `waitFail`（有次数上限，不记放弃），「类在但方法没匹配」用 `miss`（立刻放弃并记住）。
4. 混淆过的名字不许放宽匹配；拿不准就不挂。放宽匹配只能用没混淆的类型做依据，有歧义就一个都不挂。
5. 不要加定时器 / 轮询（唯一例外是 `IdleExit` 那一条延时消息，它是「无操作自动退出」功能本身，不是自检）；钩子回调里不分配对象、不反射、不打日志；回调整体 `try/catch Throwable`。
6. 没挂上时功能要安静失效，不改变抖音原有行为。
7. 不知道的类名 / 方法名先用 TextRec 追踪取证，把证据写进注释，不许猜。
8. 改了已有钩子的匹配规则 → `HookGuard.RULES_REV` 加一；改完更新 `README.md`。

提交前按 `HOOKS.md` 第 6 节的清单逐条自测。
