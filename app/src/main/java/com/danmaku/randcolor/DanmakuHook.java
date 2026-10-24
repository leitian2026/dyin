package com.danmaku.randcolor;

import android.app.Activity;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
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
 * 省电要点：没有任何轮询 / 日志 / 文件写入；配置只在 Activity.onResume 时读一次（文件没变就不重读）；
 * paint 热路径里不分配对象、不做字符串比较（Class 对象缓存后按引用比较）。
 */
final class DanmakuHook {
    private DanmakuHook() {}

    private static final String MP = "androidx.compose.ui.text.MultiParagraph";

    static volatile DanmakuConfig cfg = new DanmakuConfig();
    private static volatile long cfgMtime = -1;
    private static File cfgFile, posFile;
    private static long posMtime = -1;

    /**
     * 上一次 paint 是不是 Stroke（说明下一次 Fill 就是弹幕的填充）。
     * 不用 ThreadLocal：Compose 的文字绘制都在主线程，普通静态变量每次少一次查表。
     */
    private static boolean pendingFill;

    /**
     * 颜色缓存（直接映射，256 项）：键 = 弹幕对象的 identityHashCode，值 = 算好的颜色。
     * 一个 long：高 32 位是键，低 32 位是 0x01000000 | (mode << 25) | (最低亮度档 << 26) | RGB（0 表示空；亮度档 = textLift / 5，0～20，改了设置缓存自然失效）。
     * 弹幕滚动时每帧重绘同一条，命中缓存就不用再做哈希混合和 HSV→RGB。
     */
    private static final long[] CACHE = new long[256];
    private static volatile Class<?> strokeCls, fillCls;
    /** 每个缓存槽里最近一次交给 Compose 的装箱结果（Long）和它对应的 ARGB：参数一样就直接复用，不再每次绘制 new 一个 Long。 */
    private static final Object[] BOX = new Object[256];
    private static final int[] BOXA = new int[256];
    private static int lastSlot;

    static void init(XC_LoadPackage.LoadPackageParam lp) {
        cfgFile = new File(new File(lp.appInfo.dataDir, "files"), "danmaku_color.properties");
        posFile = new File(cfgFile.getParentFile(), "dyin_pos.properties");
        HookGuard.init(lp);
        reload();
        tryInstall(lp.classLoader);
        CommentHook.tryHook(lp.classLoader);
        TimeHook.tryHook(lp.classLoader);
        TabHook.tryHook(lp.classLoader);
        ProfHook.tryHook(lp.classLoader);
        PubHook.tryHook(lp.classLoader);
        LiveHook.tryHook(lp.classLoader);
        IdleExit.install();
        hookResume();
    }

    // ------------------------------------------------------------ 配置（回到前台时读一次，文件没变就不重读）

    private static void reload() {
        try {
            long m = cfgFile.lastModified(); // 文件不存在返回 0
            boolean changed = m != cfgMtime;
            if (changed) {
                cfgMtime = m;
                cfg = (m == 0) ? new DanmakuConfig() : DanmakuConfig.read(cfgFile);
            }
            long pm = posFile == null ? 0 : posFile.lastModified();
            if (changed || pm != posMtime) { // 配置重读过（对象换了）或位置文件变了：位置文件的值盖在上面
                posMtime = pm;
                if (pm != 0) DanmakuConfig.applyPos(cfg, posFile);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 把当前配置写回自己的数据目录（位置浮窗调整后用；文件属主本来就是抖音自己，能直接写）。 */
    static void saveCfg() {
        try {
            File f = cfgFile;
            if (f == null) return;
            File dir = f.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            try (FileOutputStream o = new FileOutputStream(f, false)) {
                o.write(cfg.toText().getBytes(StandardCharsets.ISO_8859_1));
            }
            cfgMtime = f.lastModified(); // 自己写的，不用再重读
            File pf = posFile; // 位置单独写一个文件，设置页的推送不会冲掉它
            if (pf != null) {
                try (FileOutputStream o = new FileOutputStream(pf, false)) {
                    o.write(cfg.posText().getBytes(StandardCharsets.ISO_8859_1));
                }
                posMtime = pf.lastModified();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 每次回到前台：读一次配置（文件没变就跳过）；钩子还没挂全时再试一次。 */
    private static void hookResume() {
        try {
            Method m = Activity.class.getDeclaredMethod("onResume");
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        reload();
                        if (!(p.thisObject instanceof Activity)) return;
                        Activity a = (Activity) p.thisObject;
                        PubHook.onResume(a);
                        LiveHook.onResume();
                        IdleExit.onResume();
                        HookGuard.noteVersion(a);
                        // 每个钩子都有结论（挂上 / 放弃 / 不再重试）之后，回到前台不再扫描类、不再挂钩
                        if (HookGuard.allSettled()) return;
                        ClassLoader cl = a.getClass().getClassLoader();
                        CommentHook.tryHook(cl);
                        TimeHook.tryHook(cl);
                        TabHook.tryHook(cl);
                        ProfHook.tryHook(cl);
                        PubHook.tryHook(cl);
                        LiveHook.tryHook(cl);
                        tryInstall(cl); // Compose 类可能晚于进程启动才可用
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("[DanmakuColor] hook Activity.onResume failed: " + t);
        }
    }

    // ------------------------------------------------------------ 安装 Hook

    /**
     * 先严格匹配（TextRec 追踪确认的签名），没有再放宽；都没有就放弃并记住（见 HookGuard）。
     *  严格：paint(Canvas, long 颜色, Shadow, TextDecoration, DrawStyle, int)
     *  放宽：第一个参数是 Canvas，恰好一个 long（颜色），有一个 DrawStyle 参数——位置按类型找，不依赖参数个数和顺序。
     */
    static synchronized void tryInstall(ClassLoader cl) {
        if (cl == null || HookGuard.settled(HookGuard.PAINT)) return;
        Class<?> mp = HookGuard.find(HookGuard.PAINT, MP, cl);
        if (mp == null) return;
        int n;
        boolean relaxed = false;
        try {
            n = hookPaint(mp, true);
            if (n == 0) {
                n = hookPaint(mp, false);
                relaxed = n > 0;
            }
        } catch (Throwable t) { // 方法签名里引用的类还没就绪
            HookGuard.waitFail(HookGuard.PAINT);
            return;
        }
        if (n > 0) HookGuard.ok(HookGuard.PAINT, relaxed ? "宽松匹配" : "");
        else HookGuard.miss(HookGuard.PAINT, "MultiParagraph 里没有 paint(Canvas, 颜色, …, DrawStyle, …) 这样的方法（Compose 接口变了）");
    }

    private static int hookPaint(Class<?> mp, boolean strict) {
        int n = 0;
        for (Method m : mp.getDeclaredMethods()) {
            String name = m.getName();
            if (!name.startsWith("paint") || name.contains("$")) continue;
            Class<?>[] pt = m.getParameterTypes();
            int colorIdx = -1, styleIdx = -1, longs = 0;
            for (int i = 0; i < pt.length; i++) {
                if (pt[i] == long.class) {
                    longs++;
                    colorIdx = i;
                }
                if (pt[i].getName().endsWith("DrawStyle")) styleIdx = i;
            }
            if (longs != 1 || styleIdx < 0) continue; // 带 Brush 的那个重载没有 long 颜色，在这里被排除
            if (strict) {
                if (!(pt.length == 6 && colorIdx == 1 && styleIdx == 4 && pt[2].getName().endsWith("Shadow"))) continue;
            } else if (pt.length == 0 || !pt[0].getName().endsWith("Canvas")) {
                continue;
            }
            try {
                XposedBridge.hookMethod(m, new PaintHook(colorIdx, styleIdx));
                n++;
            } catch (Throwable ignored) {
            }
        }
        return n;
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
        private final int colorIdx, styleIdx; // 颜色（long）和 DrawStyle 在参数里的位置：按类型找到的

        PaintHook(int colorIdx, int styleIdx) {
            this.colorIdx = colorIdx;
            this.styleIdx = styleIdx;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            try {
                DanmakuConfig c = cfg;
                int bg = c.lineBg;
                if (!c.enabled && bg <= 0) return;
                Object[] a = p.args;
                int kind = kindOf(a[styleIdx]);
                if (kind == 1) { // 弹幕先画描边
                    if (bg > 0) drawBg(p.thisObject, a[0], bg); // 文字灰色底：画在描边和字之前
                    if (c.enabled) pendingFill = true;
                    return;
                }
                if (!c.enabled) return;
                boolean pend = pendingFill;
                pendingFill = false; // 任何一次非 Stroke 调用都会清掉标记，不会误伤别的文字
                if (!pend || kind != 2) return;

                long v = (Long) a[colorIdx];
                if ((v & 0x3FL) != 0L) return; // 只处理 sRGB；Unspecified/其他色彩空间不碰

                int rgb = colorOf(System.identityHashCode(p.thisObject), p.thisObject, c.mode, c.textLift / 5);
                int alpha = (int) ((v >>> 56) & 0xFF);           // 保留原透明度
                int argb = (alpha << 24) | rgb;
                int slot = lastSlot;
                Object box = BOX[slot];
                if (box == null || BOXA[slot] != argb) { // Compose 的 sRGB Color：ARGB 放在高 32 位
                    box = Long.valueOf((((long) argb) & 0xFFFFFFFFL) << 32);
                    BOX[slot] = box;
                    BOXA[slot] = argb;
                }
                a[colorIdx] = box;
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------ 文字灰色底（1.29.0）

    /**
     * 弹幕的灰色底：在「Stroke 那次 paint」之前画（弹幕就是靠 Stroke → Fill 两次认出来的），位置按这条弹幕的文字框。
     * paint 的 canvas 已经平移到这条弹幕的原点，所以文字框就是 (0, 第一行顶) ～ (最宽一行的右, 最后一行底)。
     *
     * 没有 TextRec 证据的部分（只按 Compose 公开接口写的，没在 40.7.0 上验证）：
     *  - MultiParagraph 有 getLineCount() / getLineLeft(int) / getLineRight(int) / getLineTop(int) / getLineBottom(int)，没被混淆；
     *  - paint 的第一个参数（Compose Canvas 的实现类）里有一个类型是 android.graphics.Canvas 的字段（按类型找，不依赖名字）。
     * 任何一项对不上都安静地不画底，不影响弹幕本身。
     * 每条弹幕的文字框按对象身份缓存（256 项直接映射，和颜色缓存同一个思路），热路径里不反射、不分配。
     */
    private static volatile Class<?> ncCls;
    private static volatile Field ncF;
    private static volatile Class<?> geoCls;
    private static volatile Method gCount, gLeft, gRight, gTop, gBottom;
    private static final int[] GKEY = new int[256];
    private static final float[] GBOX = new float[256 * 4];

    private static void drawBg(Object mp, Object composeCanvas, int pct) {
        if (mp == null || composeCanvas == null) return;
        android.graphics.Canvas nc = nativeCanvas(composeCanvas);
        if (nc == null) return;
        int key = System.identityHashCode(mp);
        int slot = (key ^ (key >>> 8)) & 255;
        int o = slot * 4;
        if (GKEY[slot] != key) {
            measure(mp, o);
            GKEY[slot] = key;
        }
        float sd = android.content.res.Resources.getSystem().getDisplayMetrics().scaledDensity;
        LineBg.drawBox(nc, GBOX[o], GBOX[o + 1], GBOX[o + 2], GBOX[o + 3], sd, pct);
    }

    /** Compose Canvas 实现类里类型是 android.graphics.Canvas 的字段的当前值（按类型找，类变了才重找）。 */
    private static android.graphics.Canvas nativeCanvas(Object cc) {
        try {
            Class<?> k = cc.getClass();
            if (ncCls != k) {
                Field found = null;
                for (Class<?> c = k; c != null && c != Object.class && found == null; c = c.getSuperclass()) {
                    for (Field f : c.getDeclaredFields()) {
                        if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                        if (!android.graphics.Canvas.class.isAssignableFrom(f.getType())) continue;
                        f.setAccessible(true);
                        found = f;
                        break;
                    }
                }
                ncF = found;
                ncCls = k;
            }
            Field f = ncF;
            if (f == null) return null;
            Object v = f.get(cc);
            return v instanceof android.graphics.Canvas ? (android.graphics.Canvas) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 读这条弹幕的文字框写进 GBOX[o..o+3]（左、上、右、下）；读不到就写成空框（右 <= 左，LineBg.drawBox 不画）。 */
    private static void measure(Object mp, int o) {
        GBOX[o] = GBOX[o + 1] = GBOX[o + 2] = GBOX[o + 3] = 0f;
        try {
            Class<?> k = mp.getClass();
            if (geoCls != k) {
                geoCls = k; // 找不到也记住（gCount 留空），不会每条新弹幕都抛一次异常
                gCount = null;
                gCount = k.getMethod("getLineCount");
                gLeft = k.getMethod("getLineLeft", int.class);
                gRight = k.getMethod("getLineRight", int.class);
                gTop = k.getMethod("getLineTop", int.class);
                gBottom = k.getMethod("getLineBottom", int.class);
            }
            if (gCount == null || gLeft == null || gRight == null || gTop == null || gBottom == null) return;
            int n = ((Number) gCount.invoke(mp)).intValue();
            if (n <= 0) return;
            float l = Float.MAX_VALUE, r = 0f;
            for (int i = 0; i < n; i++) {
                float li = ((Number) gLeft.invoke(mp, i)).floatValue(), ri = ((Number) gRight.invoke(mp, i)).floatValue();
                if (ri <= li) continue; // 空行不算
                if (li < l) l = li;
                if (ri > r) r = ri;
            }
            if (r <= l) return;
            GBOX[o] = l;
            GBOX[o + 1] = ((Number) gTop.invoke(mp, 0)).floatValue();
            GBOX[o + 2] = r;
            GBOX[o + 3] = ((Number) gBottom.invoke(mp, n - 1)).floatValue();
        } catch (Throwable t) {
            GBOX[o] = GBOX[o + 1] = GBOX[o + 2] = GBOX[o + 3] = 0f;
        }
    }

    /** key = 对象身份，只用来当缓存键；真正决定颜色的是文字（读不到才用身份）。
     *  以前按对象身份定色，抖音重新测量会换新对象，颜色就跟着变，弹幕会闪几下。 */
    private static int colorOf(int key, Object mp, int mode, int lift) {
        if (lift < 0) lift = 0;
        else if (lift > 20) lift = 20;
        int slot = (key ^ (key >>> 8)) & 255;
        lastSlot = slot;
        long e = CACHE[slot];
        int tag = 0x01000000 | (mode << 25) | (lift << 26);
        if ((int) (e >>> 32) == key && ((int) e & 0xFF000000) == tag) return (int) e & 0xFFFFFF;
        int rgb = pick(textKey(mp, key), mode, lift);
        CACHE[slot] = ((long) key << 32) | (tag | rgb) & 0xFFFFFFFFL;
        return rgb;
    }

    // ------------------------------------------------------------ 读弹幕文字（只在缓存没命中时调用）

    private static volatile Field intrinsicsF;
    private static volatile Class<?> intrCls;
    private static volatile Field[] intrFields;

    private static Field findIntrinsics(Class<?> k) {
        try {
            Field f = k.getDeclaredField("intrinsics");
            f.setAccessible(true);
            return f;
        } catch (Throwable ignored) {
        }
        // 字段名万一被改了：找类型名带 MultiParagraphIntrinsics 的字段
        for (Field f : k.getDeclaredFields()) {
            if (f.getType().getName().endsWith("MultiParagraphIntrinsics")) {
                f.setAccessible(true);
                return f;
            }
        }
        return null;
    }

    /** 文字 → 颜色 key。读不到就退回 fallback（对象身份）。 */
    private static int textKey(Object mp, int fallback) {
        try {
            if (mp == null) return fallback;
            Field fi = intrinsicsF;
            if (fi == null) {
                fi = findIntrinsics(mp.getClass());
                if (fi == null) return fallback;
                intrinsicsF = fi;
            }
            Object in = fi.get(mp);
            if (in == null) return fallback;
            Class<?> ic = in.getClass();
            Field[] fs = intrFields;
            if (fs == null || intrCls != ic) {
                fs = ic.getDeclaredFields();
                for (Field f : fs) f.setAccessible(true);
                intrFields = fs;
                intrCls = ic;
            }
            for (Field f : fs) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (!CharSequence.class.isAssignableFrom(f.getType())) continue;
                Object v = f.get(in);
                if (v instanceof CharSequence && ((CharSequence) v).length() > 0) {
                    return v.toString().hashCode();
                }
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    private static int mix(int x) {
        x ^= x >>> 16;
        x *= 0x7feb352d;
        x ^= x >>> 15;
        x *= 0x846ca68b;
        x ^= x >>> 16;
        return x;
    }

    /** 同一个 key（文字哈希）永远得到同一个颜色，所以弹幕滚动、被重新测量都不会闪。HSV→RGB 手算，不分配对象（每帧每条弹幕都会走到这里）。 */
    private static int pick(int key, int mode, int lift) {
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
        return LineBg.legible((Math.round(r * 255f) << 16) | (Math.round(g * 255f) << 8) | Math.round(b * 255f), lift * 0.05f); // 比用户设的最低亮度暗的色（纯蓝等）提亮
    }
}
