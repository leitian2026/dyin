package com.danmaku.randcolor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** 配置：模块界面写出，目标 App 里的 Hook 读取。两边共用同一个类。默认值 = 开启 + 鲜艳随机 + 按弹幕对象固定颜色。 */
public final class DanmakuConfig {
    public boolean enabled = true;
    /** 0 = 鲜艳（高饱和高亮度）  1 = 任意（含浅色/粉彩） */
    public int mode = 0;
    /** 点右侧爱心按钮时，不点赞，改为打开评论。 */
    public boolean likeBtnToComment = false;
    /** 双击视频时，不点赞、不出红心，改为打开评论。 */
    public boolean dtapToComment = false;
    /** 视频时长一直显示（自己画一行 00:12 / 03:45）。 */
    public boolean timeAlways = false;
    /** 时长文字相对屏幕正中的偏移，单位 px。右、下为正。（1.16 起由 dp 改为 px，键名也换了，旧值不再使用） */
    public int timeX = 0, timeY = 0;
    /** 发布日期和地点一直显示（自己画一行「3天前 北京」）。 */
    public boolean pubAlways = false;
    /** 发布信息文字相对屏幕正中的偏移，单位 px。右、下为正。默认在时长文字下面一点。 */
    public int pubX = 0, pubY = 0;
    /** 时长文字 / 发布信息文字的字号，单位 sp（位置浮窗里的 − / + 每点一下增减 1）。默认 = 以前写死的 20 / 16。 */
    public int timeSize = 20, pubSize = 16;
    /** 视频文案加黑色描边（用阴影层画一圈黑边）。关掉后要重启抖音才恢复。 */
    public boolean capOutline = false;
    /** 视频文案的颜色（0xAARRGGBB，不透明）；0 = 不改，用抖音原来的颜色。话题、@ 这类抖音自己上色的文字不受影响。 */
    public int capColor = 0;
    public static final int SIZE_MIN = 8, SIZE_MAX = 40;
    /** 首页顶部标签行里要隐藏的标签（"热点"、"同城"……），取消勾选后要重启抖音才恢复显示。 */
    public String[] hiddenTabs = new String[0];
    /** 无操作多少分钟后结束抖音进程；0 = 关闭。回到前台、触摸、按键都算一次操作。 */
    public int idleExitMin = 0;
    /** 一次性请求：抖音回到前台时弹出「位置浮窗」，弹出后立即被抖音清掉。 */
    public boolean timeAdjust = false;
    /** 一次性请求：抖音回到前台时弹出「发布信息位置浮窗」，弹出后立即被抖音清掉。 */
    public boolean pubAdjust = false;

    public String toText() {
        try {
            Properties p = new Properties();
            p.setProperty("enabled", String.valueOf(enabled));
            p.setProperty("mode", String.valueOf(mode));
            p.setProperty("likeBtnToComment", String.valueOf(likeBtnToComment));
            p.setProperty("dtapToComment", String.valueOf(dtapToComment));
            p.setProperty("timeAlways", String.valueOf(timeAlways));
            p.setProperty("timeX", String.valueOf(timeX));
            p.setProperty("timeY", String.valueOf(timeY));
            p.setProperty("timeAdjust", String.valueOf(timeAdjust));
            p.setProperty("pubAlways", String.valueOf(pubAlways));
            p.setProperty("pubX", String.valueOf(pubX));
            p.setProperty("pubY", String.valueOf(pubY));
            p.setProperty("pubAdjust", String.valueOf(pubAdjust));
            p.setProperty("timeSize", String.valueOf(timeSize));
            p.setProperty("pubSize", String.valueOf(pubSize));
            p.setProperty("capOutline", String.valueOf(capOutline));
            p.setProperty("capColor", String.valueOf(capColor));
            p.setProperty("idleExitMin", String.valueOf(idleExitMin));
            StringBuilder sb = new StringBuilder();
            for (String s : hiddenTabs) sb.append(sb.length() == 0 ? "" : ",").append(s);
            p.setProperty("hiddenTabs", sb.toString());
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            p.store(bo, null);
            return new String(bo.toByteArray(), StandardCharsets.ISO_8859_1);
        } catch (Throwable t) {
            return "";
        }
    }

    public static DanmakuConfig fromText(String text) {
        DanmakuConfig c = new DanmakuConfig();
        if (text == null || text.isEmpty()) return c;
        try {
            Properties p = new Properties();
            p.load(new StringReader(text));
            fill(c, p);
        } catch (Throwable ignored) {
        }
        return c;
    }

    public static DanmakuConfig read(File f) {
        DanmakuConfig c = new DanmakuConfig();
        try (FileInputStream in = new FileInputStream(f)) {
            Properties p = new Properties();
            p.load(in);
            fill(c, p);
        } catch (Throwable ignored) {
        }
        return c;
    }

    private static void fill(DanmakuConfig c, Properties p) {
        c.enabled = Boolean.parseBoolean(p.getProperty("enabled", "true").trim());
        c.likeBtnToComment = Boolean.parseBoolean(p.getProperty("likeBtnToComment", "false").trim());
        c.dtapToComment = Boolean.parseBoolean(p.getProperty("dtapToComment", "false").trim());
        c.timeAlways = Boolean.parseBoolean(p.getProperty("timeAlways", "false").trim());
        c.timeX = parseOffset(p.getProperty("timeX", "0"));
        c.timeY = parseOffset(p.getProperty("timeY", "0"));
        c.timeAdjust = Boolean.parseBoolean(p.getProperty("timeAdjust", "false").trim());
        c.pubAlways = Boolean.parseBoolean(p.getProperty("pubAlways", "false").trim());
        c.pubX = parseOffset(p.getProperty("pubX", "0"));
        c.pubY = parseOffset(p.getProperty("pubY", "0"));
        c.pubAdjust = Boolean.parseBoolean(p.getProperty("pubAdjust", "false").trim());
        c.timeSize = parseSize(p.getProperty("timeSize", "20"), 20);
        c.pubSize = parseSize(p.getProperty("pubSize", "16"), 16);
        c.capOutline = Boolean.parseBoolean(p.getProperty("capOutline", "false").trim());
        try {
            int col = (int) Long.parseLong(p.getProperty("capColor", "0").trim());
            c.capColor = col == 0 ? 0 : (col | 0xFF000000);
        } catch (Throwable t) {
            c.capColor = 0;
        }
        List<String> tabs = split(p.getProperty("hiddenTabs", ""));
        c.hiddenTabs = tabs.toArray(new String[0]);
        try {
            c.idleExitMin = Math.max(0, Math.min(1440, Integer.parseInt(p.getProperty("idleExitMin", "0").trim())));
        } catch (Throwable t) {
            c.idleExitMin = 0;
        }
        try {
            c.mode = Integer.parseInt(p.getProperty("mode", "0").trim()) == 1 ? 1 : 0;
        } catch (Throwable t) {
            c.mode = 0;
        }
    }

    private static int parseSize(String v, int def) {
        try {
            return Math.max(SIZE_MIN, Math.min(SIZE_MAX, Integer.parseInt(v.trim())));
        } catch (Throwable t) {
            return def;
        }
    }

    private static int parseOffset(String v) {
        try {
            return Math.max(-4000, Math.min(4000, Integer.parseInt(v.trim())));
        } catch (Throwable t) {
            return 0;
        }
    }

    static List<String> split(String s) {
        ArrayList<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String p : s.split("[,\\n\\r]+")) {
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }
}
