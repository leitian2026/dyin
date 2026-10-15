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
