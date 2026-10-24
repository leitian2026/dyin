package com.danmaku.randcolor;

import android.app.Activity;
import android.content.pm.PackageInfo;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.Properties;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 钩子的统一管理：每个钩子只有四种结果，并且都有明确的停止条件。
 *
 *  OK       挂上了。
 *  NEW      还没有结论，回到前台时再试（类还没找到）。
 *  MISSING  类连续 MAX_WAIT 次回到前台都没找到：这次进程不再试。不记成「放弃」——类可能只是还没加载，下次启动再给它机会。
 *  GAVE_UP  类找到了，但里面没有匹配的方法（抖音改了这段代码）：立刻放弃，按「APK 签名 + 规则版本」记在
 *           files/hooks.properties 里，之后每次启动直接跳过，什么都不做。抖音更新（APK 变了）或匹配规则改了（RULES_REV 加一）才会清掉重试。
 *
 * 所有钩子都有结论之后，回到前台不再扫描类、不再挂钩（见 allSettled）。
 *
 * 新增钩子 / 新功能必须接入这里，步骤和硬性规则见项目根目录的 HOOKS.md（给 AI 的入口是 AGENTS.md）。
 * hooks.properties 只在状态有变化时才写（稳态不写文件），模块设置页用 root 读它显示「哪个钩子可用」。
 */
final class HookGuard {
    private HookGuard() {}

    static final int PAINT = 0, TIME_PROGRESS = 1, TAB = 2, PUB = 3, PUB_BIND = 4, PUB_ANCHOR = 5, DTAP_SEEN = 6, DTAP_CUT = 7, CAP_DRAW = 8, DTAP_CLICK = 9, LIVE_PAGE = 10, LIVE_ROOM = 11, PROF_PAGER = 12, DTAP_BTN = 13, NAME_DRAW = 14, PROF_ENTER = 15;
    static final String[] IDS = {"paint", "time.progress", "tab.hide", "pub.text", "pub.bind", "pub.anchor", "dtap.seen", "dtap.cut", "cap.draw", "dtap.click", "live.page", "live.room", "prof.pager", "dtap.btn", "name.draw", "prof.enter"};

    /** 匹配规则改了就加一：已经放弃的钩子会重新试一次。 */
    private static final int RULES_REV = 5;
    /** 类一直找不到时，最多试几次（每次回到前台一次）。 */
    private static final int MAX_WAIT = 6;

    private static final int NEW = 0, OK = 1, GAVE_UP = 2, MISSING = 3;
    private static final int[] state = new int[IDS.length];
    private static final int[] waits = new int[IDS.length];
    private static final String[] note = new String[IDS.length];

    private static File file;
    private static String sig, ver;
    private static boolean sameSig, verDone;
    private static Properties saved = new Properties();

    /** 挑选要挂的方法。 */
    interface Filter {
        boolean ok(Method m);
    }

    static Filter named(final String name) {
        return new Filter() {
            @Override
            public boolean ok(Method m) {
                return m.getName().equals(name);
            }
        };
    }

    // ------------------------------------------------------------ 初始化 / 状态

    static synchronized void init(XC_LoadPackage.LoadPackageParam lp) {
        try {
            file = new File(new File(lp.appInfo.dataDir, "files"), "hooks.properties");
            File apk = new File(lp.appInfo.sourceDir);
            sig = apk.length() + "-" + apk.lastModified() + "-" + RULES_REV;
            Properties p = new Properties();
            if (file.exists()) {
                try (FileInputStream in = new FileInputStream(file)) {
                    p.load(in);
                } catch (Throwable ignored) {
                }
            }
            saved = p;
            sameSig = sig.equals(p.getProperty("sig"));
            if (!sameSig) return; // 抖音更新了 / 规则改了：一切重来
            ver = p.getProperty("ver");
            for (int i = 0; i < IDS.length; i++) {
                if ("gaveup".equals(p.getProperty(IDS[i]))) {
                    state[i] = GAVE_UP;
                    note[i] = p.getProperty(IDS[i] + ".note");
                }
            }
        } catch (Throwable ignored) {
        }
    }

    static synchronized boolean settled(int id) {
        return state[id] != NEW;
    }

    static synchronized boolean allSettled() {
        for (int s : state) if (s == NEW) return false;
        return true;
    }

    static synchronized void ok(int id, String how) {
        if (state[id] == OK) return;
        state[id] = OK;
        note[id] = how;
        flush();
    }

    static synchronized void miss(int id, String why) {
        state[id] = GAVE_UP;
        note[id] = why;
        XposedBridge.log("[DanmakuColor] 钩子未适配 " + IDS[id] + ": " + why);
        flush();
    }

    static synchronized void waitFail(int id) {
        if (state[id] != NEW || ++waits[id] < MAX_WAIT) return;
        state[id] = MISSING;
        note[id] = "类一直没找到";
        XposedBridge.log("[DanmakuColor] 钩子类没找到 " + IDS[id] + "（这次进程不再试）");
        flush();
    }

    /** 回到前台时调一次：记下抖音的版本号，设置页显示用。 */
    static synchronized void noteVersion(Activity a) {
        if (verDone) return;
        verDone = true;
        try {
            PackageInfo pi = a.getPackageManager().getPackageInfo(a.getPackageName(), 0);
            ver = pi.versionName + " (" + pi.versionCode + ")";
            flush();
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ 找类 / 挂钩

    /**
     * 按类名找类（依次试每个类加载器）。找不到返回 null：
     * 都是「没有这个类」→ 记一次等待；类在但加载不了 → 记一次等待，不放弃（可能只是依赖的类还没就绪）。
     */
    static Class<?> find(int id, String cls, ClassLoader... loaders) {
        if (settled(id)) return null;
        for (ClassLoader cl : loaders) {
            if (cl == null) continue;
            try {
                return Class.forName(cls, false, cl);
            } catch (Throwable ignored) {
            }
        }
        waitFail(id);
        return null;
    }

    /**
     * 在类里挂所有 filter 通过的方法。返回 true 表示这个钩子已经有结论（挂上 / 放弃 / 不再重试），false 表示下次再试。
     * 类在、但一个方法都没匹配上 = 抖音改了这段代码，立刻放弃并记住，不再重试。
     */
    static boolean hook(int id, String cls, Filter f, XC_MethodHook h, String missNote, ClassLoader... loaders) {
        if (settled(id)) return true;
        Class<?> c = find(id, cls, loaders);
        if (c == null) return settled(id);
        int n = 0;
        try {
            for (Method m : c.getDeclaredMethods()) {
                if (!f.ok(m)) continue;
                try {
                    XposedBridge.hookMethod(m, h);
                    n++;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) { // 方法签名里引用的类还没就绪
            waitFail(id);
            return settled(id);
        }
        if (n > 0) ok(id, "");
        else miss(id, missNote);
        return true;
    }

    // ------------------------------------------------------------ 落盘（有变化才写）

    private static void flush() {
        try {
            if (file == null || sig == null) return;
            Properties np = new Properties();
            np.setProperty("sig", sig);
            String v = ver != null ? ver : (sameSig ? saved.getProperty("ver") : null);
            if (v != null) np.setProperty("ver", v);
            for (int i = 0; i < IDS.length; i++) {
                String s, n = note[i];
                switch (state[i]) {
                    case OK: s = "ok"; break;
                    case GAVE_UP: s = "gaveup"; break;
                    case MISSING: s = "missing"; break;
                    default: // 这次还没有结论：沿用上次的，免得每次启动都因为「少了几项」而重写
                        s = sameSig ? saved.getProperty(IDS[i]) : null;
                        n = sameSig ? saved.getProperty(IDS[i] + ".note") : null;
                        break;
                }
                if (s == null) continue;
                np.setProperty(IDS[i], s);
                if (n != null && !n.isEmpty()) np.setProperty(IDS[i] + ".note", n);
            }
            if (np.equals(saved)) return;
            File dir = file.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            try (FileOutputStream o = new FileOutputStream(file, false)) {
                np.store(o, null);
            }
            saved = np;
            sameSig = true;
        } catch (Throwable ignored) {
        }
    }
}
