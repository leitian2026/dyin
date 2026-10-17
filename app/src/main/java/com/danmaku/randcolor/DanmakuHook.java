package com.danmaku.randcolor;

import android.app.Activity;
import android.graphics.Color;
import android.os.Process;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 抖音弹幕是用 Jetpack Compose 画的：每条弹幕调用两次
 *   MultiParagraph.paint(canvas, color, shadow, decoration, drawStyle, blendMode)
 * 第 1 次 drawStyle=Stroke（黑色描边），第 2 次 drawStyle=Fill（白色填充）。
 * 这里在"Stroke 之后紧跟的那次 Fill"里把 color 参数换成随机色。描边保持不变。
 * 全程事件驱动，没有轮询。
 */
final class DanmakuHook {
    private DanmakuHook() {}

    private static final String MP = "androidx.compose.ui.text.MultiParagraph";

    static volatile DanmakuConfig cfg = new DanmakuConfig();
    private static volatile long cfgMtime = -1;
    private static volatile long lastCheck;
    private static volatile boolean installed;
    private static File cfgFile, statusFile;
    private static String proc = "?";
    private static boolean mismatchLogged, waitLogged, eventsHooked, textInit;
    private static Method mIntrinsics, mAnnotated, mText;

    /** 同一线程上：上一次 paint 是不是 Stroke（说明下一次 Fill 就是弹幕的填充） */
    private static final ThreadLocal<Boolean> PENDING = new ThreadLocal<>();
    private static final AtomicLong COUNT = new AtomicLong();

    static void init(XC_LoadPackage.LoadPackageParam lp) {
        File files = new File(lp.appInfo.dataDir, "files");
        files.mkdirs();
        cfgFile = new File(files, "danmaku_color.properties");
        statusFile = new File(files, "danmaku_color.status");
        TabHook.tabsFile = new File(files, "danmaku_color.tabs");
        proc = lp.processName == null ? "?" : lp.processName;
        reload(true);
        status("模块已加载 " + cfg.summary());
        tryInstall(lp.classLoader);
        TabHook.install();
        CommentHook.install();
        CommentHook.tryHookDigg(lp.classLoader);
        hookResume();
    }

    // ------------------------------------------------------------ 配置（文件变了才重读）

    static void maybeReload() {
        reload(false);
    }

    private static void reload(boolean force) {
        long now = SystemClock.elapsedRealtime();
        if (!force && now - lastCheck < 2000) return;
        synchronized (DanmakuHook.class) {
            lastCheck = now;
            long m = cfgFile.lastModified(); // 文件不存在返回 0
            if (m == cfgMtime) return;
            cfgMtime = m;
            cfg = (m == 0) ? new DanmakuConfig() : DanmakuConfig.read(cfgFile);
            if (!force) status("配置已更新 " + cfg.summary());
        }
    }

    // ------------------------------------------------------------ 安装 Hook

    private static void hookResume() {
        if (eventsHooked) return;
        eventsHooked = true;
        try {
            Method m = Activity.class.getDeclaredMethod("onResume");
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        long before = cfgMtime;
                        reload(true); // 回到前台必查一次配置（不受 2 秒节流），改了隐藏列表马上生效
                        if (cfgMtime != before) status("配置已更新 " + cfg.summary());
                        if (p.thisObject instanceof Activity) {
                            TabHook.scanLater((Activity) p.thisObject);
                            CommentHook.top = new java.lang.ref.WeakReference<>((Activity) p.thisObject);
                            CommentHook.tryHookDigg(p.thisObject.getClass().getClassLoader());
                        }
                        // Compose 类可能晚于进程启动才可用（插件/动态加载），回到前台时再试
                        if (!installed && p.thisObject != null) tryInstall(p.thisObject.getClass().getClassLoader());
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("[DanmakuColor] hook Activity.onResume failed: " + t);
        }
    }

    static synchronized void tryInstall(ClassLoader cl) {
        if (installed || cl == null) return;
        try {
            Class<?> mp;
            try {
                mp = Class.forName(MP, false, cl);
            } catch (Throwable t) {
                if (!waitLogged) {
                    waitLogged = true;
                    status("暂未找到 Compose 文字类（本进程可能不是界面进程，或稍后才加载）");
                }
                return;
            }
            int n = 0;
            StringBuilder seen = new StringBuilder();
            for (Method m : mp.getDeclaredMethods()) {
                String name = m.getName();
                if (!name.startsWith("paint") || name.contains("$")) continue;
                Class<?>[] pt = m.getParameterTypes();
                seen.append(name).append('/').append(pt.length).append(' ');
                // 按签名匹配而不是写死方法名后缀（Compose 版本变了后缀会变）
                if (pt.length == 6 && pt[1] == long.class
                        && pt[2].getName().endsWith("Shadow") && pt[4].getName().endsWith("DrawStyle")) {
                    try {
                        XposedBridge.hookMethod(m, new PaintHook());
                        n++;
                    } catch (Throwable t) {
                        status("挂接 " + name + " 失败：" + t);
                    }
                }
            }
            if (n > 0) {
                installed = true;
                status("已挂接 " + n + " 个 paint 方法");
            } else if (!mismatchLogged) {
                mismatchLogged = true;
                status("没找到匹配的 paint 方法（Compose 版本可能变了），现有：" + seen);
            }
        } catch (Throwable t) {
            status("安装 Hook 出错：" + t);
        }
    }

    // ------------------------------------------------------------ 核心

    private static final class PaintHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            try {
                maybeReload();
                DanmakuConfig c = cfg;
                if (!c.enabled) {
                    PENDING.set(Boolean.FALSE);
                    return;
                }
                Object[] a = p.args;
                Object style = a[4];
                String sn = style == null ? "" : style.getClass().getName();
                if (sn.endsWith(".Stroke")) { // 弹幕先画描边
                    PENDING.set(Boolean.TRUE);
                    return;
                }
                boolean pend = Boolean.TRUE.equals(PENDING.get());
                PENDING.set(Boolean.FALSE);   // 任何一次非 Stroke 调用都会清掉标记，不会误伤别的文字
                if (!pend || !sn.endsWith(".Fill")) return;

                long v = (Long) a[1];
                if ((v & 0x3FL) != 0L) return; // 只处理 sRGB；Unspecified/其他色彩空间不碰

                int rgb = pick(keyOf(p.thisObject, c), c.mode);
                int alpha = (int) ((v >>> 56) & 0xFF);           // 保留原透明度
                int argb = (alpha << 24) | (rgb & 0xFFFFFF);
                a[1] = (((long) argb) & 0xFFFFFFFFL) << 32;      // Compose 的 sRGB Color：ARGB 放在高 32 位

                long n = COUNT.incrementAndGet();
                if (n == 1 || n == 200 || n == 5000) {
                    status("已改色 " + n + " 次，最近一次 0x" + Integer.toHexString(argb));
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static int keyOf(Object paragraph, DanmakuConfig c) {
        if (c.keyByText) {
            String t = textOf(paragraph);
            if (t != null) return t.hashCode();
        }
        return System.identityHashCode(paragraph);
    }

    /** 读取弹幕文字（用于"相同文字同色"）。字段被混淆读不到时返回 null，退回按对象。 */
    private static String textOf(Object paragraph) {
        try {
            if (!textInit) {
                textInit = true;
                try {
                    mIntrinsics = paragraph.getClass().getMethod("getIntrinsics");
                    mAnnotated = mIntrinsics.getReturnType().getMethod("getAnnotatedString");
                    mText = mAnnotated.getReturnType().getMethod("getText");
                } catch (Throwable t) {
                    mIntrinsics = null;
                    status("读不到弹幕文字（被混淆），「按文字」模式退回按对象");
                }
            }
            if (mIntrinsics == null) return null;
            Object intrinsics = mIntrinsics.invoke(paragraph);
            Object annotated = mAnnotated.invoke(intrinsics);
            return (String) mText.invoke(annotated);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int mix(int x) {
        x ^= x >>> 16;
        x *= 0x7feb352d;
        x ^= x >>> 15;
        x *= 0x846ca68b;
        x ^= x >>> 16;
        return x;
    }

    /** 同一个 key 永远得到同一个颜色，所以弹幕滚动时每帧重绘不会闪。 */
    private static int pick(int key, int mode) {
        int m = mix(key);
        float h = (m & 0xFFFF) % 360;
        float s, v;
        if (mode == 0) {
            s = 0.75f + ((m >>> 16) & 0xFF) / 255f * 0.25f;
            v = 1f;
        } else {
            s = 0.35f + ((m >>> 16) & 0xFF) / 255f * 0.65f;
            v = 0.80f + ((m >>> 24) & 0xFF) / 255f * 0.20f;
        }
        return Color.HSVToColor(new float[]{h, s, v}) & 0xFFFFFF;
    }

    // ------------------------------------------------------------ 状态文件（模块界面「检查状态」读它）

    static void status(String msg) {
        try {
            File f = statusFile;
            if (f == null) return;
            if (f.length() > 8192) f.delete();
            String line = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date())
                    + " [" + proc + " p" + Process.myPid() + "] " + msg + "\n";
            try (FileOutputStream os = new FileOutputStream(f, true)) {
                os.write(line.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }
}
