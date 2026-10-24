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
    /** 双击视频时，不点赞、不出红心，改为打开评论。 */
    public boolean dtapToComment = false;
    /** 点底部「我」进个人主页时默认停在「喜欢」标签（最后一页）。见 ProfHook。 */
    public boolean profFav = false;
    /** 视频时长一直显示（放在发布信息那一行的下面，没显示发布信息就放在文案下面）。 */
    public boolean timeAlways = false;
    /** 发布日期和地点一直显示（在文案下面单独一行）。 */
    public boolean pubAlways = false;
    /** 发布信息那一行的模板，见 PubFormat：{日期} {时间} {地点} {省} {市} 可以随意排顺序、夹文字，不限长度。 */
    public String pubFormat = PubFormat.DEFAULT;
    /** 时长文字 / 发布信息那一行的字号，单位 sp（在设置页调）。默认 = 以前写死的 20 / 16。 */
    public int timeSize = 20, pubSize = 16;
    /** 时长文字 / 发布信息那一行的颜色（0xAARRGGBB，不透明）；0 = 默认（时长白色，发布信息半透明白）。和视频文案的颜色互不影响。 */
    public int timeColor = 0, pubColor = 0;
    /** 视频文案的颜色（0xAARRGGBB，不透明）；0 = 不改，用抖音原来的颜色。话题、@ 这类抖音自己上色的文字不受影响。 */
    public int capColor = 0;
    /** 视频作者名的颜色（0xAARRGGBB，不透明）；0 = 不改，用抖音原来的颜色。作者名的灰色底跟「文字灰色底」的不透明度 lineBg 走（0 = 不画）。见 PubHook.NameHook。 */
    public int nameColor = 0;
    /** 直播间里显示开播时间（只在直播间页面里画，别处不显示）。 */
    public boolean liveTime = false;
    /** 取哪个时间：0 = 开播时间（没有就用创建时间）  1 = 房间创建时间。 */
    public int liveSrc = 0;
    /** 开播时间文字的字号（sp）、颜色（0xAARRGGBB 不透明；0 = 默认白色）。 */
    public int liveSize = 14, liveColor = 0;
    /** 位置：文字左上角在「直播页剩余空间」里的百分比，0 = 最左 / 最上，100 = 最右 / 最下（文字始终完整显示在页面里）。 */
    public int liveX = 3, liveY = 10;
    /** 位置调节模式：设置页点「去抖音里调整」时置 true；进直播间后抖音里出现位置浮窗，点「完成 / 取消」时由抖音里的浮窗改回 false 并写回文件。设置页自己存的配置里永远是 false。 */
    public boolean liveAdjust = false;
    /** 视频文案 / 视频时长 / 发布日期地点每一行字后面的灰色底的不透明度（0～100，%）；0 = 不画。见 LineBg。 */
    public int lineBg = 70;
    /** 灰色底的颜色（0xAARRGGBB，不透明）；0 = 默认（近黑 #101010）。不透明度仍由 lineBg 管。见 LineBg。 */
    public int lineBgColor = 0;
    /** 模块自己决定颜色的字（弹幕随机色）的最低亮度（0～100，%，每档 5）：比这暗的颜色往白色提亮；0 = 不提亮。见 LineBg.legible。 */
    public int textLift = 30;
    /** 空白行默认高度 = 这么多 sp 的字高（blankH 为 -1 时用）。 */
    public static final int BLANK_DEFAULT_SP = 37;
    /** 文案下面的空白行高度：每 1 = 2 像素（-1 = 默认，高度等于 BLANK_DEFAULT_SP 的字高；0 = 不放；最大 200）。 */
    public int blankH = -1;
    /** 文案下方固定位置的开关；选哪个（1 = 发布地，2 = 时长，3 = 两个都选）：选中的文字放在文案下方（上顶留出的空位的顶上），不再用自己的自由位置；没有色条。 */
    public boolean barOn = true;
    public int barSel = 1;
    /** 发布日期地点、视频时长两个浮层在页面里的位置（左 / 上，dp，0 ～ 4000）；-1 = 默认。在抖音里用位置浮窗拖，见 VidPosPanel。 */
    public int pubX = -1, pubY = -1, timeX = -1, timeY = -1;
    /** 位置调节模式（发布地 / 时长）：设置页点「去抖音里调整」时置 true，抖音里浮窗点完成 / 取消时改回 false。设置页自己存的配置里永远是 false。 */
    public boolean vidAdjust = false;
    /** 开播时间那一行的模板，见 LiveFormat。 */
    public String liveFormat = LiveFormat.DEFAULT;
    public static final int SIZE_MIN = 8, SIZE_MAX = 40;
    /** 首页顶部标签行里要隐藏的标签（"热点"、"同城"……），取消勾选后要重启抖音才恢复显示。 */
    public String[] hiddenTabs = new String[0];
    /** 无操作多少分钟后结束抖音进程；0 = 关闭。回到前台、触摸、按键都算一次操作。 */
    public int idleExitMin = 0;

    public String toText() {
        try {
            Properties p = new Properties();
            p.setProperty("enabled", String.valueOf(enabled));
            p.setProperty("mode", String.valueOf(mode));
            p.setProperty("dtapToComment", String.valueOf(dtapToComment));
            p.setProperty("profFav", String.valueOf(profFav));
            p.setProperty("timeAlways", String.valueOf(timeAlways));
            p.setProperty("pubAlways", String.valueOf(pubAlways));
            p.setProperty("timeSize", String.valueOf(timeSize));
            p.setProperty("pubSize", String.valueOf(pubSize));
            p.setProperty("pubFormat", pubFormat == null ? PubFormat.DEFAULT : pubFormat);
            p.setProperty("timeColor", String.valueOf(timeColor));
            p.setProperty("pubColor", String.valueOf(pubColor));
            p.setProperty("capColor", String.valueOf(capColor));
            p.setProperty("nameColor", String.valueOf(nameColor));
            p.setProperty("idleExitMin", String.valueOf(idleExitMin));
            p.setProperty("liveTime", String.valueOf(liveTime));
            p.setProperty("liveSrc", String.valueOf(liveSrc));
            p.setProperty("liveSize", String.valueOf(liveSize));
            p.setProperty("liveColor", String.valueOf(liveColor));
            p.setProperty("liveX", String.valueOf(liveX));
            p.setProperty("liveY", String.valueOf(liveY));
            p.setProperty("liveAdjust", String.valueOf(liveAdjust));
            p.setProperty("lineBg", String.valueOf(lineBg));
            p.setProperty("lineBgColor", String.valueOf(lineBgColor));
            p.setProperty("textLift", String.valueOf(textLift));
            p.setProperty("blankH", String.valueOf(blankH));
            p.setProperty("barOn", String.valueOf(barOn));
            p.setProperty("barSel", String.valueOf(barSel));
            p.setProperty("pubX", String.valueOf(pubX));
            p.setProperty("pubY", String.valueOf(pubY));
            p.setProperty("timeX", String.valueOf(timeX));
            p.setProperty("timeY", String.valueOf(timeY));
            p.setProperty("vidAdjust", String.valueOf(vidAdjust));
            p.setProperty("liveFormat", liveFormat == null ? LiveFormat.DEFAULT : liveFormat);
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

    /**
     * 位置（直播间开播时间的百分比、发布地 / 时长的偏移）单独存在抖音自己数据目录里的 dyin_pos.properties：
     * 位置浮窗只写它，设置页的「保存并应用」不碰它（以前位置跟着配置文件走，设置页里的旧值会把抖音里调好的位置冲掉）。
     * 有这个文件时，它的值盖过配置文件里的同名项。
     */
    String posText() {
        try {
            Properties p = new Properties();
            p.setProperty("liveX", String.valueOf(liveX));
            p.setProperty("liveY", String.valueOf(liveY));
            p.setProperty("pubX", String.valueOf(pubX));
            p.setProperty("pubY", String.valueOf(pubY));
            p.setProperty("timeX", String.valueOf(timeX));
            p.setProperty("timeY", String.valueOf(timeY));
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            p.store(bo, null);
            return new String(bo.toByteArray(), StandardCharsets.ISO_8859_1);
        } catch (Throwable t) {
            return "";
        }
    }

    static void applyPos(DanmakuConfig c, Properties p) {
        c.liveX = parsePct(p.getProperty("liveX", String.valueOf(c.liveX)), c.liveX);
        c.liveY = parsePct(p.getProperty("liveY", String.valueOf(c.liveY)), c.liveY);
        c.pubX = parsePos(p.getProperty("pubX", String.valueOf(c.pubX)));
        c.pubY = parsePos(p.getProperty("pubY", String.valueOf(c.pubY)));
        c.timeX = parsePos(p.getProperty("timeX", String.valueOf(c.timeX)));
        c.timeY = parsePos(p.getProperty("timeY", String.valueOf(c.timeY)));
    }

    static void applyPos(DanmakuConfig c, File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            Properties p = new Properties();
            p.load(in);
            applyPos(c, p);
        } catch (Throwable ignored) {
        }
    }

    /** 设置页用：把 root 读回的 dyin_pos.properties 文本盖到 c 上。 */
    static void applyPosText(DanmakuConfig c, String text) {
        try {
            Properties p = new Properties();
            p.load(new StringReader(text));
            applyPos(c, p);
        } catch (Throwable ignored) {
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
        c.dtapToComment = Boolean.parseBoolean(p.getProperty("dtapToComment", "false").trim());
        c.profFav = Boolean.parseBoolean(p.getProperty("profFav", "false").trim());
        c.timeAlways = Boolean.parseBoolean(p.getProperty("timeAlways", "false").trim());
        c.pubAlways = Boolean.parseBoolean(p.getProperty("pubAlways", "false").trim());
        c.timeSize = parseSize(p.getProperty("timeSize", "20"), 20);
        c.pubSize = parseSize(p.getProperty("pubSize", "16"), 16);
        c.capColor = parseColor(p.getProperty("capColor", "0"));
        c.nameColor = parseColor(p.getProperty("nameColor", "0"));
        c.timeColor = parseColor(p.getProperty("timeColor", "0"));
        c.pubColor = parseColor(p.getProperty("pubColor", "0"));
        c.barOn = Boolean.parseBoolean(p.getProperty("barOn", "true").trim());
        c.barSel = Math.max(1, Math.min(3, parseIntOr(p.getProperty("barSel", "1"), 1)));
        String fmt = p.getProperty("pubFormat", PubFormat.DEFAULT);
        c.pubFormat = fmt == null || fmt.trim().isEmpty() ? PubFormat.DEFAULT : fmt;
        c.liveTime = Boolean.parseBoolean(p.getProperty("liveTime", "false").trim());
        c.liveSrc = parsePct(p.getProperty("liveSrc", "0"), 0) == 1 ? 1 : 0;
        c.liveSize = parseSize(p.getProperty("liveSize", "14"), 14);
        c.liveColor = parseColor(p.getProperty("liveColor", "0"));
        c.liveX = parsePct(p.getProperty("liveX", "3"), 3);
        c.liveY = parsePct(p.getProperty("liveY", "10"), 10);
        c.lineBg = parsePct(p.getProperty("lineBg", "70"), 70);
        c.lineBgColor = parseColor(p.getProperty("lineBgColor", "0"));
        c.textLift = parsePct(p.getProperty("textLift", "30"), 30);
        c.blankH = Math.max(-1, Math.min(200, parseIntOr(p.getProperty("blankH", "-1"), -1)));
        c.pubX = parsePos(p.getProperty("pubX", "-1"));
        c.pubY = parsePos(p.getProperty("pubY", "-1"));
        c.timeX = parsePos(p.getProperty("timeX", "-1"));
        c.timeY = parsePos(p.getProperty("timeY", "-1"));
        c.vidAdjust = Boolean.parseBoolean(p.getProperty("vidAdjust", "false").trim());
        c.liveAdjust = Boolean.parseBoolean(p.getProperty("liveAdjust", "false").trim());
        String lf = p.getProperty("liveFormat", LiveFormat.DEFAULT);
        c.liveFormat = lf == null || lf.trim().isEmpty() ? LiveFormat.DEFAULT : lf;
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

    /** 位置偏移（dp）：-400～400；读不懂当 0。 */
    /** 浮层位置（dp）：-1 = 默认；读不懂当默认。 */
    private static int parsePos(String v) {
        try {
            return Math.max(-1, Math.min(4000, Integer.parseInt(v.trim())));
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int parseIntOr(String v, int def) {
        try {
            return Integer.parseInt(v.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    /** 0～100 的整数（位置百分比）；读不懂用默认值。 */
    private static int parsePct(String v, int def) {
        try {
            return Math.max(0, Math.min(100, Integer.parseInt(v.trim())));
        } catch (Throwable t) {
            return def;
        }
    }

    /** 颜色：0 = 不改 / 默认；其他一律补成不透明。读不懂当 0。 */
    private static int parseColor(String v) {
        try {
            int col = (int) Long.parseLong(v.trim());
            return col == 0 ? 0 : (col | 0xFF000000);
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
