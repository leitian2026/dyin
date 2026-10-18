package com.danmaku.randcolor;

import android.app.Activity;

import java.io.File;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 抖音弹幕是用 Jetpack Compose 画的：每条弹幕调用两次
 *   MultiParagraph.paint(canvas, color, shadow, decoration, drawStyle, blendMode)
 * 第 1 次 drawStyle=Stroke（黑色描边），第 2 次 drawStyle=Fill（白色填充）。
 * 这里在"Stroke 之后紧跟的那次 Fill"里把 color 参数换成随机色。描边保持不变。
 *
 * 省电要点：没有任何轮询 / 日志 / 文件写入；配置只在 Activity.onResume 时读一次；
 * paint 热路径里不分配对象、不做字符串比较（Class 对象缓存后按引用比较）。
 */
final class DanmakuHook {
    private DanmakuHook() {}

    private static final String MP = "androidx.compose.ui.text.MultiParagraph";

    static volatile DanmakuConfig cfg = new DanmakuConfig();
    private static volatile long cfgMtime = -1;
    private static volatile boolean installed, givenUp;
    private static File cfgFile;
    private static boolean textInit;
    private static Method mIntrinsics, mAnnotated, mText;

    /** 同一线程上：上一次 paint 是不是 Stroke（说明下一次 Fill 就是弹幕的填充）。 */
    private static final ThreadLocal<boolean[]> PENDING = new ThreadLocal<boolean[]>() {
        @Override
        protected boolean[] initialValue() {
            return new boolean[1];
        }
    };
    private static volatile Class<?> strokeCls, fillCls;

    static void init(XC_LoadPackage.LoadPackageParam lp) {
        cfgFile = new File(new File(lp.appInfo.dataDir, "files"), "danmaku_color.properties");
        reload();
        tryInstall(lp.classLoader);
        CommentHook.tryHook(lp.classLoader);
        TimeHook.tryHook(lp.classLoader);
        hookResume();
    }

    // ------------------------------------------------------------ 配置（回到前台时读一次，文件没变就不重读）

    private static void reload() {
        try {
            long m = cfgFile.lastModified(); // 文件不存在返回 0
            if (m == cfgMtime) return;
            cfgMtime = m;
            cfg = (m == 0) ? new DanmakuConfig() : DanmakuConfig.read(cfgFile);
        } catch (Throwable ignored) {
        }
    }

    private static void hookResume() {
        try {
            Method m = Activity.class.getDeclaredMethod("onResume");
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        reload();
                        if (p.thisObject instanceof Activity) {
                            Activity a = (Activity) p.thisObject;
                            CommentHook.top = new java.lang.ref.WeakReference<>(a);
                            ClassLoader cl = a.getClass().getClassLoader();
                            CommentHook.tryHook(cl);
                            TimeHook.tryHook(cl);
                            TimeHook.onResume(a);
                            if (!installed) tryInstall(cl); // Compose 类可能晚于进程启动才可用
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("[DanmakuColor] hook Activity.onResume failed: " + t);
        }
    }

    // ------------------------------------------------------------ 安装 Hook

    static synchronized void tryInstall(ClassLoader cl) {
        if (installed || givenUp || cl == null) return;
        try {
            Class<?> mp;
            try {
                mp = Class.forName(MP, false, cl);
            } catch (Throwable t) {
                return; // 还没加载：下次回到前台再试
            }
            int n = 0;
            for (Method m : mp.getDeclaredMethods()) {
                String name = m.getName();
                if (!name.startsWith("paint") || name.contains("$")) continue;
                Class<?>[] pt = m.getParameterTypes();
                // 按签名匹配而不是写死方法名后缀（Compose 版本变了后缀会变）
                if (pt.length == 6 && pt[1] == long.class
                        && pt[2].getName().endsWith("Shadow") && pt[4].getName().endsWith("DrawStyle")) {
                    try {
                        XposedBridge.hookMethod(m, new PaintHook());
                        n++;
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (n > 0) installed = true;
            else givenUp = true; // Compose 接口变了：不再反复扫描
        } catch (Throwable ignored) {
            givenUp = true;
        }
    }

    // ------------------------------------------------------------ 核心

    /** 1 = Stroke，2 = Fill，0 = 其他。第一次见到才比类名，之后按 Class 引用比较。 */
    private static int kindOf(Object style) {
        if (style == null) return 0;
        Class<?> k = style.getClass();
        if (k == strokeCls) return 1;
        if (k == fillCls) return 2;
        String n = k.getName();
        if (n.endsWith(".Stroke")) {
            strokeCls = k;
            return 1;
        }
        if (n.endsWith(".Fill")) {
            fillCls = k;
            return 2;
        }
        return 0;
    }

    private static final class PaintHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            try {
                DanmakuConfig c = cfg;
                if (!c.enabled) return;
                Object[] a = p.args;
                int kind = kindOf(a[4]);
                boolean[] st = PENDING.get();
                if (kind == 1) { // 弹幕先画描边
                    st[0] = true;
                    return;
                }
                boolean pend = st[0];
                st[0] = false; // 任何一次非 Stroke 调用都会清掉标记，不会误伤别的文字
                if (!pend || kind != 2) return;

                long v = (Long) a[1];
                if ((v & 0x3FL) != 0L) return; // 只处理 sRGB；Unspecified/其他色彩空间不碰

                int rgb = pick(keyOf(p.thisObject, c), c.mode);
                int alpha = (int) ((v >>> 56) & 0xFF);           // 保留原透明度
                int argb = (alpha << 24) | rgb;
                a[1] = (((long) argb) & 0xFFFFFFFFL) << 32;      // Compose 的 sRGB Color：ARGB 放在高 32 位
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

    /** 同一个 key 永远得到同一个颜色，所以弹幕滚动时每帧重绘不会闪。HSV→RGB 手算，不分配对象（每帧每条弹幕都会走到这里）。 */
    private static int pick(int key, int mode) {
        int m = mix(key);
        float h = ((m & 0xFFFF) % 360) / 60f;
        float s, v;
        if (mode == 0) {
            s = 0.75f + ((m >>> 16) & 0xFF) / 255f * 0.25f;
            v = 1f;
        } else {
            s = 0.35f + ((m >>> 16) & 0xFF) / 255f * 0.65f;
            v = 0.80f + ((m >>> 24) & 0xFF) / 255f * 0.20f;
        }
        int i = (int) h;
        float f = h - i;
        float pp = v * (1f - s), q = v * (1f - s * f), t = v * (1f - s * (1f - f));
        float r, g, b;
        switch (i) {
            case 0: r = v; g = t; b = pp; break;
            case 1: r = q; g = v; b = pp; break;
            case 2: r = pp; g = v; b = t; break;
            case 3: r = pp; g = q; b = v; break;
            case 4: r = t; g = pp; b = v; break;
            default: r = v; g = pp; b = q; break;
        }
        return (Math.round(r * 255f) << 16) | (Math.round(g * 255f) << 8) | Math.round(b * 255f);
    }
}
