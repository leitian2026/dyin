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
    /** 一次性请求：抖音回到前台时弹出「位置浮窗」，弹出后立即被抖音清掉。 */
    public boolean timeAdjust = false;

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
        try {
            c.mode = Integer.parseInt(p.getProperty("mode", "0").trim()) == 1 ? 1 : 0;
        } catch (Throwable t) {
            c.mode = 0;
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
