package com.danmaku.randcolor;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

/** 设置页：卡片 + 开关行 + 分段按钮 + 底部固定操作栏。纯框架控件，不依赖任何第三方库；跟随系统深浅色。 */
public class MainActivity extends Activity {
    static final String PREFS = "ui";
    static final String DEFAULT_PKG = "com.ss.android.ugc.aweme";
    // 配色（浅色 / 深色）
    private boolean dark;
    private int cBg, cCard, cStroke, cText, cSub, cPrimary, cPrimarySoft, cField;

    private EditText etPkg;
    private Switch swEnabled, swLikeBtn, swDtap, swTime;
    private LinearLayout segMode, statusCard;
    private TextView tvOffset;
    private int tX, tY;
    private TextView tvStatus, btnPush, btnAdjust, btnHooks;
    private ScrollView scroll;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("dyin");
        dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        initPalette();
        styleSystemBars();

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(cBg);

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(20), dp(16), dp(28));
        scroll.addView(root, new ViewGroup.LayoutParams(-1, -2));
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        page.addView(bottomBar(), new LinearLayout.LayoutParams(-1, -2));
        setContentView(page);

        // ---- 标题
        TextView title = tv("dyin", 30, cText, true);
        title.setPadding(dp(4), 0, 0, 0);
        root.addView(title);
        TextView sub = tv("弹幕随机颜色 · 点赞改为打开评论 · 视频时长常显", 14, cSub, false);
        sub.setPadding(dp(4), dp(2), 0, dp(14));
        root.addView(sub);

        // ---- 怎么用
        LinearLayout how = card();
        how.addView(tv("怎么用", 20, cText, true));
        TextView pre = tv("前提：已在 LSPosed 里启用本模块，并在作用域勾选抖音。", 14, cSub, false);
        pre.setPadding(0, dp(4), 0, dp(14));
        how.addView(pre);
        how.addView(step(1, "启用并重开抖音", "LSPosed 里启用模块、勾选抖音，然后强杀抖音再打开（只需做一次）"));
        how.addView(step(2, "调整下面的设置", "选好弹幕颜色，决定点赞按钮 / 双击是否改为打开评论"));
        how.addView(step(3, "点下方「保存并应用」", "把设置推送给抖音（需要 root）。抖音回到前台就生效，不用重启"));
        root.addView(how, cardLp());

        // ---- 目标 App
        root.addView(section("目标 App"));
        LinearLayout target = card();
        target.addView(tv("包名", 17, cText, true));
        TextView pd = tv("要作用的 App，多个包名用逗号分隔，默认是抖音。", 13, cSub, false);
        pd.setPadding(0, dp(2), 0, dp(10));
        target.addView(pd);
        etPkg = field();
        target.addView(etPkg);
        target.addView(divider());
        swEnabled = new Switch(this);
        target.addView(switchRow("启用弹幕随机色", "关闭后弹幕恢复原本的颜色", swEnabled));
        root.addView(target, cardLp());

        // ---- 弹幕颜色
        root.addView(section("弹幕颜色"));
        LinearLayout colors = card();
        colors.addView(tv("颜色风格", 17, cText, true));
        TextView d1 = tv("鲜艳：高饱和亮色，看得清；任意：含浅色、粉彩，更杂。", 13, cSub, false);
        d1.setPadding(0, dp(2), 0, dp(10));
        colors.addView(d1);
        segMode = segmented("鲜艳", "任意");
        colors.addView(segMode);
        root.addView(colors, cardLp());

        // ---- 点赞改为打开评论
        root.addView(section("点赞 → 打开评论"));
        LinearLayout like = card();
        swLikeBtn = new Switch(this);
        like.addView(switchRow("点右侧爱心按钮时打开评论", "不再点赞（也不会取消赞），改为点开这个视频的评论", swLikeBtn));
        like.addView(divider());
        swDtap = new Switch(this);
        like.addView(switchRow("双击视频时打开评论", "双击不再点赞，改为打开评论", swDtap));
        TextView ld = tv("右侧按钮：找不到评论按钮时不拦截，点赞照常；双击：始终不点赞，也不出红心。", 13, cSub, false);
        ld.setPadding(0, dp(12), 0, 0);
        like.addView(ld);
        root.addView(like, cardLp());

        // ---- 视频时长常显
        root.addView(section("视频时长常显"));
        LinearLayout tm = card();
        swTime = new Switch(this);
        tm.addView(switchRow("视频时长一直显示", "在视频上自己画一行 00:12 / 03:45，抖音任何界面都显示，播放时随进度走动；刷到直播或离开视频页自动隐藏", swTime));
        tm.addView(divider());
        tm.addView(tv("位置微调", 17, cText, true));
        TextView td = tv("点下面的按钮，会在抖音里弹出一个浮窗：两条滑动条（左边减、右边加），两端的 − / + 每点一下移动 2 像素，一边调一边看时长怎么动。点浮窗里的「完成」自动保存，回到本页会同步数值。需要 root。", 13, cSub, false);
        td.setPadding(0, dp(2), 0, dp(12));
        tm.addView(td);
        tvOffset = tv("", 14, cSub, false);
        tvOffset.setTypeface(Typeface.MONOSPACE);
        tvOffset.setPadding(0, 0, 0, dp(12));
        tm.addView(tvOffset);
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btnAdjust = pillButton("打开浮窗调整位置", true);
        btnAdjust.setTextSize(16);
        btnAdjust.setOnClickListener(v -> openAdjust());
        btns.addView(btnAdjust, new LinearLayout.LayoutParams(0, dp(48), 1f));
        TextView reset = pillButton("归零", false);
        reset.setTextSize(16);
        reset.setOnClickListener(v -> {
            tX = 0;
            tY = 0;
            showOffset();
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("adjusting", false).apply();
        });
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(dp(88), dp(48));
        rl.leftMargin = dp(10);
        btns.addView(reset, rl);
        tm.addView(btns);
        root.addView(tm, cardLp());

        // ---- 钩子状态
        root.addView(section("钩子状态"));
        LinearLayout hk = card();
        TextView hd = tv("抖音更新后，模块会自己按特征重新找钩子，找不到的会放弃并记住（同一个版本不再反复尝试）。点下面的按钮用 root 读取每个钩子是否可用。", 13, cSub, false);
        hd.setPadding(0, 0, 0, dp(12));
        hk.addView(hd);
        btnHooks = pillButton("检查钩子状态", false);
        btnHooks.setTextSize(16);
        btnHooks.setOnClickListener(v -> checkHooks());
        hk.addView(btnHooks, new LinearLayout.LayoutParams(-1, dp(48)));
        root.addView(hk, cardLp());

        // ---- 状态（有内容才显示）
        statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusCard.setPadding(dp(18), dp(14), dp(18), dp(14));
        tvStatus = tv("", 13, cText, false);
        tvStatus.setTypeface(Typeface.MONOSPACE);
        tvStatus.setTextIsSelectable(true);
        statusCard.addView(tvStatus);
        statusCard.setVisibility(View.GONE);
        root.addView(statusCard, cardLp());

        load();
    }

    // ------------------------------------------------------------ 读写配置

    private void load() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        etPkg.setText(sp.getString("pkg", DEFAULT_PKG));
        DanmakuConfig c = DanmakuConfig.fromText(sp.getString("cfg", ""));
        swEnabled.setChecked(c.enabled);
        swLikeBtn.setChecked(c.likeBtnToComment);
        swDtap.setChecked(c.dtapToComment);
        swTime.setChecked(c.timeAlways);
        tX = c.timeX;
        tY = c.timeY;
        showOffset();
        setSeg(segMode, c.mode == 1 ? 1 : 0);
    }

    private DanmakuConfig read() {
        DanmakuConfig c = new DanmakuConfig();
        c.enabled = swEnabled.isChecked();
        c.mode = segIndex(segMode) == 1 ? 1 : 0;
        c.likeBtnToComment = swLikeBtn.isChecked();
        c.dtapToComment = swDtap.isChecked();
        c.timeAlways = swTime.isChecked();
        c.timeX = tX;
        c.timeY = tY;
        return c;
    }

    private void save() {
        final String cfgText = read().toText();
        final String pkgs = etPkg.getText().toString().trim();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("cfg", cfgText).putString("pkg", pkgs).apply();
        final List<String> list = DanmakuConfig.split(pkgs);
        if (list.isEmpty()) {
            showStatus("请先填目标包名", 2);
            return;
        }
        setBusy(btnPush, true, "正在推送…");
        showStatus("正在推送…（首次会弹出 root 授权，请允许）", 0);
        new Thread(() -> {
            final StringBuilder sb = new StringBuilder();
            for (String pkg : list) {
                if (!RootShell.validPkg(pkg)) {
                    sb.append("✗ ").append(pkg).append("：包名格式不对\n");
                    continue;
                }
                RootShell.Result r = RootShell.pushConfig(pkg, cfgText);
                if (r.out.contains("__OK__")) sb.append("✓ ").append(pkg).append("：已推送\n");
                else if (r.out.contains("__NOPKG__")) sb.append("✗ ").append(pkg).append("：").append(RootShell.nopkgMsg(r.out)).append('\n');
                else sb.append("✗ ").append(pkg).append("：失败(").append(r.code).append(") ").append(r.err.trim()).append('\n');
            }
            sb.append("\n抖音回到前台就生效，不用重启。抖音清除数据/重装后需要重新推送（不推送则用默认：开启+鲜艳）。");
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                setBusy(btnPush, false, "保存并应用");
                showStatus(sb.toString(), sb.indexOf("✗") >= 0 ? 2 : 1);
            });
        }).start();
    }

    // ------------------------------------------------------------ 位置浮窗

    /** 把「弹出位置浮窗」的请求（timeAdjust=true）连同当前全部设置推给抖音，然后打开抖音。 */
    private void openAdjust() {
        swTime.setChecked(true);
        DanmakuConfig c = read();
        final String pkgs = etPkg.getText().toString().trim();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("cfg", c.toText()).putString("pkg", pkgs).apply();
        final List<String> list = DanmakuConfig.split(pkgs);
        if (list.isEmpty()) {
            showStatus("请先填目标包名", 2);
            return;
        }
        c.timeAdjust = true;
        final String pushText = c.toText();
        setBusy(btnAdjust, true, "正在打开…");
        showStatus("正在推送…（首次会弹出 root 授权，请允许）", 0);
        new Thread(() -> {
            final StringBuilder sb = new StringBuilder();
            String first = null;
            for (String pkg : list) {
                if (!RootShell.validPkg(pkg)) {
                    sb.append("✗ ").append(pkg).append("：包名格式不对\n");
                    continue;
                }
                RootShell.Result r = RootShell.pushConfig(pkg, pushText);
                if (r.out.contains("__OK__")) {
                    if (first == null) first = pkg;
                } else if (r.out.contains("__NOPKG__")) {
                    sb.append("✗ ").append(pkg).append("：").append(RootShell.nopkgMsg(r.out)).append('\n');
                } else {
                    sb.append("✗ ").append(pkg).append("：失败(").append(r.code).append(") ").append(r.err.trim()).append('\n');
                }
            }
            final String ok = first;
            final String err = sb.toString();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                setBusy(btnAdjust, false, "打开浮窗调整位置");
                if (ok == null) {
                    showStatus(err.isEmpty() ? "推送失败" : err, 2);
                    return;
                }
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("adjusting", true).putString("adjustPkg", ok).apply();
                Intent i = getPackageManager().getLaunchIntentForPackage(ok);
                if (i != null) {
                    statusCard.setVisibility(View.GONE);
                    startActivity(i);
                } else {
                    showStatus("已推送，但没找到 " + ok + " 的启动入口，请手动切到抖音，浮窗会自动弹出。", 1);
                }
            });
        }).start();
    }

    /** 从抖音回到本页：把浮窗里调好的位置读回来，免得下次「保存并应用」把旧值推回去。 */
    @Override
    protected void onResume() {
        super.onResume();
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        final String pkg = sp.getString("adjustPkg", "");
        if (!sp.getBoolean("adjusting", false) || pkg.isEmpty()) return;
        new Thread(() -> {
            String text = RootShell.readConfig(pkg);
            if (text == null) return;
            final DanmakuConfig c = DanmakuConfig.fromText(text);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                tX = c.timeX;
                tY = c.timeY;
                showOffset();
                SharedPreferences sp2 = getSharedPreferences(PREFS, MODE_PRIVATE);
                sp2.edit().putString("cfg", read().toText()).putBoolean("adjusting", false).apply();
            });
        }).start();
    }

    // ------------------------------------------------------------ 钩子状态

    private static final String[][] HOOK_NAMES = {
            {"paint", "弹幕随机色"},
            {"comment.digg", "爱心按钮改评论"},
            {"comment.dtap", "双击改评论"},
            {"time.progress", "视频时长：进度"},
            {"time.stop", "视频时长：停止"},
            {"time.release", "视频时长：释放"},
            {"time.playing", "视频时长：开始播放"},
    };

    /** 用 root 读抖音数据目录里的 hooks.properties（抖音进程自己写的），列出每个钩子的状态。 */
    private void checkHooks() {
        final List<String> list = DanmakuConfig.split(etPkg.getText().toString().trim());
        if (list.isEmpty()) {
            showStatus("请先填目标包名", 2);
            return;
        }
        final String pkg = list.get(0);
        setBusy(btnHooks, true, "正在读取…");
        new Thread(() -> {
            final String text = RootShell.readFile(pkg, "hooks.properties");
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                setBusy(btnHooks, false, "检查钩子状态");
                if (text == null) {
                    showStatus("没读到钩子状态：抖音还没运行过 / 模块没在 LSPosed 里勾选它 / 没 root。先打开抖音用一会儿，再来检查。", 2);
                    return;
                }
                boolean[] bad = new boolean[1];
                String msg = describeHooks(text, bad);
                showStatus(msg == null ? "钩子状态文件读不懂" : msg, msg == null || bad[0] ? 2 : 1);
            });
        }).start();
    }

    private static String describeHooks(String text, boolean[] bad) {
        Properties p = new Properties();
        try {
            p.load(new ByteArrayInputStream(text.getBytes(StandardCharsets.ISO_8859_1)));
        } catch (Exception e) {
            return null;
        }
        String ver = p.getProperty("ver");
        StringBuilder b = new StringBuilder("抖音 ").append(ver != null ? ver : "（版本未知）").append('\n');
        for (String[] h : HOOK_NAMES) {
            String st = p.getProperty(h[0]), note = p.getProperty(h[0] + ".note");
            boolean hasNote = note != null && !note.isEmpty();
            if ("ok".equals(st)) {
                b.append("✓ ").append(h[1]).append(hasNote ? "（" + note + "）" : "");
            } else if ("gaveup".equals(st)) {
                bad[0] = true;
                b.append("✗ ").append(h[1]).append("：未适配").append(hasNote ? " — " + note : "");
            } else if ("missing".equals(st)) {
                bad[0] = true;
                b.append("✗ ").append(h[1]).append("：相关的类一直没找到");
            } else {
                b.append("… ").append(h[1]).append("：还没挂上（抖音还没走到相关界面）");
            }
            b.append('\n');
        }
        return b.toString().trim();
    }

    private void showOffset() {
        if (tvOffset != null) tvOffset.setText("当前位置  水平 " + signed(tX) + " px　垂直 " + signed(tY) + " px");
    }

    private static String signed(int v) {
        return v > 0 ? "+" + v : String.valueOf(v);
    }

    // ------------------------------------------------------------ 状态卡片 / 忙碌态

    /** tone: 0 普通  1 成功  2 失败 */
    private void showStatus(String msg, int tone) {
        int bg, fg;
        if (tone == 1) {
            bg = dark ? 0xFF16301F : 0xFFE8F6EC;
            fg = dark ? 0xFF8EE6A9 : 0xFF1E6B35;
        } else if (tone == 2) {
            bg = dark ? 0xFF3A1B1B : 0xFFFDECEC;
            fg = dark ? 0xFFFF9C94 : 0xFFB3261E;
        } else {
            bg = cField;
            fg = cText;
        }
        statusCard.setBackground(round(bg, 0, 16));
        tvStatus.setTextColor(fg);
        tvStatus.setText(msg);
        statusCard.setVisibility(View.VISIBLE);
        scroll.post(() -> scroll.smoothScrollTo(0, Math.max(0, statusCard.getTop() - dp(12))));
    }

    private void setBusy(TextView b, boolean busy, String label) {
        b.setEnabled(!busy);
        b.setAlpha(busy ? 0.55f : 1f);
        b.setText(label);
    }

    // ------------------------------------------------------------ 控件工厂

    private void initPalette() {
        cBg = dark ? 0xFF101218 : 0xFFF5F6FA;
        cCard = dark ? 0xFF1A1D26 : 0xFFFFFFFF;
        cStroke = dark ? 0xFF2B303C : 0xFFE3E6EF;
        cText = dark ? 0xFFECEEF4 : 0xFF14161C;
        cSub = dark ? 0xFF9AA1B2 : 0xFF6B7280;
        cPrimary = dark ? 0xFF6C85F5 : 0xFF3B5BDB;
        cPrimarySoft = dark ? 0xFF262E52 : 0xFFE7EBFF;
        cField = dark ? 0xFF242835 : 0xFFEEF0F6;
    }

    @SuppressWarnings("deprecation")
    private void styleSystemBars() {
        getWindow().setStatusBarColor(cBg);
        getWindow().setNavigationBarColor(cCard);
        if (!dark) {
            int f = getWindow().getDecorView().getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) f |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            getWindow().getDecorView().setSystemUiVisibility(f);
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView tv(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    private GradientDrawable round(int fill, int stroke, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private Drawable ripple(Drawable content) {
        return new RippleDrawable(ColorStateList.valueOf(dark ? 0x33FFFFFF : 0x1F000000), content, null);
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(20), dp(18), dp(20), dp(18));
        c.setBackground(round(cCard, cStroke, 22));
        return c;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(4);
        return lp;
    }

    private TextView section(String s) {
        TextView t = tv(s, 14, cSub, true);
        t.setPadding(dp(6), dp(20), 0, dp(8));
        return t;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(cStroke);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(1));
        lp.topMargin = dp(16);
        lp.bottomMargin = dp(16);
        v.setLayoutParams(lp);
        return v;
    }

    private EditText field() {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setTextSize(16);
        e.setTextColor(cText);
        e.setHintTextColor(cSub);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setPadding(dp(16), dp(14), dp(16), dp(14));
        e.setBackground(round(cField, 0, 14));
        return e;
    }

    private LinearLayout switchRow(String title, String desc, Switch sw) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.addView(tv(title, 17, cText, false));
        TextView d = tv(desc, 13, cSub, false);
        d.setPadding(0, dp(2), 0, 0);
        txt.addView(d);
        row.addView(txt, new LinearLayout.LayoutParams(0, -2, 1f));
        int[][] st = {{android.R.attr.state_checked}, {}};
        sw.setThumbTintList(new ColorStateList(st, new int[]{cPrimary, dark ? 0xFF8A90A0 : 0xFFFFFFFF}));
        sw.setTrackTintList(new ColorStateList(st, new int[]{dark ? 0xFF35417A : 0xFFC5CFFA, dark ? 0xFF3A3F4D : 0xFFD5D8E2}));
        row.addView(sw);
        row.setOnClickListener(v -> sw.toggle());
        return row;
    }

    private LinearLayout step(int n, String title, String desc) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 0, 0, dp(14));
        TextView num = tv(String.valueOf(n), 15, cPrimary, true);
        num.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(cPrimarySoft);
        num.setBackground(circle);
        row.addView(num, new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.addView(tv(title, 17, cText, true));
        TextView d = tv(desc, 13, cSub, false);
        d.setPadding(0, dp(2), 0, 0);
        txt.addView(d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.leftMargin = dp(14);
        row.addView(txt, lp);
        return row;
    }

    /** 分段按钮：点哪个选哪个，用 segIndex 读当前选中的序号。 */
    private LinearLayout segmented(String... opts) {
        final LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setPadding(dp(4), dp(4), dp(4), dp(4));
        seg.setBackground(round(cField, 0, 16));
        for (int i = 0; i < opts.length; i++) {
            final int idx = i;
            TextView t = new TextView(this);
            t.setText(opts[i]);
            t.setTextSize(15);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(8), dp(11), dp(8), dp(11));
            t.setOnClickListener(v -> setSeg(seg, idx));
            seg.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        setSeg(seg, 0);
        return seg;
    }

    private void setSeg(LinearLayout seg, int idx) {
        seg.setTag(idx);
        for (int i = 0; i < seg.getChildCount(); i++) {
            TextView t = (TextView) seg.getChildAt(i);
            boolean on = i == idx;
            t.setBackground(on ? round(cPrimary, 0, 12) : null);
            t.setTextColor(on ? 0xFFFFFFFF : cSub);
            t.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    private int segIndex(LinearLayout seg) {
        Object t = seg.getTag();
        return t instanceof Integer ? (Integer) t : 0;
    }

    private TextView pillButton(String s, boolean filled) {
        TextView t = tv(s, 17, filled ? 0xFFFFFFFF : cText, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(ripple(filled ? round(cPrimary, 0, 26) : round(cCard, cStroke, 26)));
        return t;
    }

    /** 底部固定操作栏：只有一个主操作（保存并应用）。 */
    private View bottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(cCard);
        bar.setElevation(dp(8));
        View line = new View(this);
        line.setBackgroundColor(cStroke);
        bar.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        btnPush = pillButton("保存并应用", true);
        btnPush.setOnClickListener(v -> save());
        row.addView(btnPush, new LinearLayout.LayoutParams(0, dp(52), 1f));
        bar.addView(row);
        return bar;
    }
}
