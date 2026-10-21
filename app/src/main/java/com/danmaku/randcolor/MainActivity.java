package com.danmaku.randcolor;

import android.app.Activity;
import android.app.AlertDialog;
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
import android.widget.CheckBox;
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

    private EditText etPkg, etIdle;
    /** 首页顶部标签行里的 13 个标签（来自 TextRec 追踪，40.7.0）；勾选 = 隐藏。 */
    private static final String[] TAB_LABELS = {"推荐", "关注", "热点", "同城", "商城", "经验", "游戏", "少儿", "精选", "团购", "免费看剧", "短剧", "直播"};
    private final CheckBox[] cbTabs = new CheckBox[TAB_LABELS.length];
    private Switch swEnabled, swLikeBtn, swDtap, swTime, swPub;
    private LinearLayout segMode, statusCard;
    private TextView tvOffset, tvPubOffset;
    private int tX, tY, pX, pY;
    /** 时长 / 发布信息文字的字号（sp），和视频文案的颜色（0 = 不改）；字号在抖音的位置浮窗里调，回到本页同步。 */
    private int tSize = 20, pSize = 16, capColor;
    private Switch swCapOutline;
    private View capSwatch;
    private TextView tvCapColor;
    private TextView tvStatus, btnPush, btnAdjust, btnAdjustPub, btnHooks;
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
        TextView sub = tv("弹幕随机颜色 · 点赞改为打开评论 · 视频时长常显 · 发布日期地点常显", 14, cSub, false);
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
        TextView td = tv("点下面的按钮，会在抖音里弹出一个浮窗：三条滑动条：水平、垂直、字号（左边减、右边加），两端的 − / + 每点一下移动 2 像素（字号是增减 1 号），一边调一边看时长怎么动。点浮窗里的「完成」自动保存，回到本页会同步数值。需要 root。", 13, cSub, false);
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
        btnAdjust.setOnClickListener(v -> openAdjust(false));
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

        // ---- 发布日期和地点常显
        root.addView(section("发布日期和地点常显"));
        LinearLayout pb = card();
        swPub = new Switch(this);
        pb.addView(switchRow("发布日期和地点一直显示", "在视频文案下面自己画一行「2026-10-19 11:50:00 广西 南宁」，取自视频本身的发布时间（精确到秒）和 IP 属地（省，有城市就加市），不用点「展开」；划到下一个视频就换；没有地点的视频只显示时间", swPub));
        pb.addView(divider());
        pb.addView(tv("位置微调", 17, cText, true));
        TextView pdsc = tv("和时长位置一样：点下面的按钮，会在抖音里弹出同样的浮窗，调的是这一行的位置和字号（位置是相对视频文案左下角的偏移）。点浮窗里的「完成」自动保存，回到本页会同步数值。需要 root。", 13, cSub, false);
        pdsc.setPadding(0, dp(2), 0, dp(12));
        pb.addView(pdsc);
        tvPubOffset = tv("", 14, cSub, false);
        tvPubOffset.setTypeface(Typeface.MONOSPACE);
        tvPubOffset.setPadding(0, 0, 0, dp(12));
        pb.addView(tvPubOffset);
        LinearLayout pbtns = new LinearLayout(this);
        pbtns.setOrientation(LinearLayout.HORIZONTAL);
        btnAdjustPub = pillButton("打开浮窗调整位置", true);
        btnAdjustPub.setTextSize(16);
        btnAdjustPub.setOnClickListener(v -> openAdjust(true));
        pbtns.addView(btnAdjustPub, new LinearLayout.LayoutParams(0, dp(48), 1f));
        TextView preset = pillButton("归零", false);
        preset.setTextSize(16);
        preset.setOnClickListener(v -> {
            pX = 0;
            pY = 0;
            showPubOffset();
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("adjusting", false).apply();
        });
        LinearLayout.LayoutParams prl = new LinearLayout.LayoutParams(dp(88), dp(48));
        prl.leftMargin = dp(10);
        pbtns.addView(preset, prl);
        pb.addView(pbtns);
        root.addView(pb, cardLp());

        // ---- 视频文案样式
        root.addView(section("视频文案样式"));
        LinearLayout cp = card();
        swCapOutline = new Switch(this);
        cp.addView(switchRow("视频文案加黑色描边", "给视频下方的文案（标题、话题）加一圈黑边，亮背景上也看得清。关掉后要重启抖音才恢复", swCapOutline));
        cp.addView(divider());
        cp.addView(tv("文案颜色", 17, cText, true));
        TextView cpd = tv("点「选色」从色表里挑一个颜色；话题、@ 这类抖音自己上色的文字不变。选「不改」就用抖音原来的颜色（选过颜色再改回「不改」，要重启抖音才恢复）。", 13, cSub, false);
        cpd.setPadding(0, dp(2), 0, dp(12));
        cp.addView(cpd);
        LinearLayout crow = new LinearLayout(this);
        crow.setOrientation(LinearLayout.HORIZONTAL);
        crow.setGravity(Gravity.CENTER_VERTICAL);
        capSwatch = new View(this);
        crow.addView(capSwatch, new LinearLayout.LayoutParams(dp(28), dp(28)));
        tvCapColor = tv("", 14, cSub, false);
        tvCapColor.setPadding(dp(12), 0, 0, 0);
        crow.addView(tvCapColor, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView pick = pillButton("选色", true);
        pick.setTextSize(16);
        pick.setOnClickListener(v -> showPalette());
        crow.addView(pick, new LinearLayout.LayoutParams(dp(88), dp(48)));
        cp.addView(crow);
        root.addView(cp, cardLp());

        // ---- 首页顶部标签
        root.addView(section("首页顶部标签"));
        LinearLayout tabs = card();
        tabs.addView(tv("勾选的标签会被隐藏", 17, cText, true));
        TextView tbd = tv("追踪（抖音 40.7.0）里，这台手机当前显示的是：推荐、关注、热点、同城、商城；其余 8 个在标签行里存在但抖音没有启用，哪天启用了才会出现。取消勾选后要重启抖音才恢复；只隐藏标签，左右划动仍会翻到它的页面。", 13, cSub, false);
        tbd.setPadding(0, dp(2), 0, dp(10));
        tabs.addView(tbd);
        for (int r = 0; r < TAB_LABELS.length; r += 3) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = r; i < r + 3; i++) {
                if (i < TAB_LABELS.length) {
                    CheckBox cb = new CheckBox(this);
                    cb.setText(TAB_LABELS[i]);
                    cb.setTextSize(15);
                    cb.setTextColor(cText);
                    cbTabs[i] = cb;
                    row.addView(cb, new LinearLayout.LayoutParams(0, -2, 1f));
                } else {
                    row.addView(new View(this), new LinearLayout.LayoutParams(0, 0, 1f));
                }
            }
            tabs.addView(row);
        }
        root.addView(tabs, cardLp());

        // ---- 无操作自动退出
        root.addView(section("无操作自动退出"));
        LinearLayout idle = card();
        idle.addView(tv("无操作多少分钟后结束抖音", 17, cText, true));
        TextView idd = tv("连续这么多分钟没有触摸、按键，也没有回到前台，就结束抖音进程（后台也计时）。回到前台算一次操作，重新开始。填 0 = 关闭。", 13, cSub, false);
        idd.setPadding(0, dp(2), 0, dp(10));
        idle.addView(idd);
        etIdle = field();
        etIdle.setInputType(InputType.TYPE_CLASS_NUMBER);
        etIdle.setHint("分钟，0 = 关闭");
        idle.addView(etIdle);
        root.addView(idle, cardLp());

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
        etIdle.setText(String.valueOf(c.idleExitMin));
        for (int i = 0; i < TAB_LABELS.length; i++) {
            boolean hide = false;
            for (String h : c.hiddenTabs) if (TAB_LABELS[i].equals(h)) hide = true;
            cbTabs[i].setChecked(hide);
        }
        tX = c.timeX;
        tY = c.timeY;
        showOffset();
        swPub.setChecked(c.pubAlways);
        pX = c.pubX;
        pY = c.pubY;
        tSize = c.timeSize;
        pSize = c.pubSize;
        showOffset();
        showPubOffset();
        swCapOutline.setChecked(c.capOutline);
        capColor = c.capColor;
        showCapColor();
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
        c.pubAlways = swPub.isChecked();
        c.pubX = pX;
        c.pubY = pY;
        c.timeSize = tSize;
        c.pubSize = pSize;
        c.capOutline = swCapOutline.isChecked();
        c.capColor = capColor;
        java.util.ArrayList<String> hid = new java.util.ArrayList<>();
        for (int i = 0; i < TAB_LABELS.length; i++) if (cbTabs[i].isChecked()) hid.add(TAB_LABELS[i]);
        c.hiddenTabs = hid.toArray(new String[0]);
        try {
            c.idleExitMin = Math.max(0, Math.min(1440, Integer.parseInt(etIdle.getText().toString().trim())));
        } catch (Throwable t) {
            c.idleExitMin = 0;
        }
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
    private void openAdjust(final boolean pub) {
        final TextView btn = pub ? btnAdjustPub : btnAdjust;
        if (pub) swPub.setChecked(true);
        else swTime.setChecked(true);
        DanmakuConfig c = read();
        final String pkgs = etPkg.getText().toString().trim();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("cfg", c.toText()).putString("pkg", pkgs).apply();
        final List<String> list = DanmakuConfig.split(pkgs);
        if (list.isEmpty()) {
            showStatus("请先填目标包名", 2);
            return;
        }
        if (pub) c.pubAdjust = true;
        else c.timeAdjust = true;
        final String pushText = c.toText();
        setBusy(btn, true, "正在打开…");
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
                setBusy(btn, false, "打开浮窗调整位置");
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
                pX = c.pubX;
                pY = c.pubY;
                tSize = c.timeSize;
                pSize = c.pubSize;
                showOffset();
                showPubOffset();
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
            {"dtap.seen", "双击改评论：识别双击（系统方法）"},
            {"dtap.cut", "双击改评论：拦在点赞之前"},
            {"dtap.click", "双击改评论：不让单击暂停视频"},
            {"time.progress", "视频时长：进度"},
            {"time.stop", "视频时长：停止"},
            {"time.release", "视频时长：释放"},
            {"time.playing", "视频时长：开始播放"},
            {"tab.hide", "首页顶部标签隐藏"},
            {"pub.text", "发布日期地点：展开文案"},
            {"pub.bind", "发布日期地点：视频数据"},
            {"pub.anchor", "视频文案：位置 / 描边 / 改色"},
            {"cap.draw", "视频文案：绘制时改色"},
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

    // ------------------------------------------------------------ 文案颜色色表

    /** 色表：6 列 × 4 行，名字 + 不透明 RGB。 */
    private static final String[] PAL_NAMES = {
            "白", "浅灰", "灰", "黑", "米黄", "柠檬黄",
            "金", "橙", "深橙", "红", "玫红", "粉",
            "淡紫", "紫", "靛蓝", "蓝", "天蓝", "青",
            "薄荷", "绿", "嫩绿", "草绿", "棕", "肉粉"};
    private static final int[] PAL = {
            0xFFFFFF, 0xD9D9D9, 0x9E9E9E, 0x000000, 0xFFF3C4, 0xFFEB3B,
            0xFFC107, 0xFF9800, 0xFF5722, 0xF44336, 0xE91E63, 0xFF80AB,
            0xCE93D8, 0x9C27B0, 0x3F51B5, 0x2196F3, 0x4FC3F7, 0x00BCD4,
            0x1DE9B6, 0x4CAF50, 0xA5D6A7, 0x8BC34A, 0x8D6E63, 0xFFCCBC};

    private void showCapColor() {
        if (capSwatch == null || tvCapColor == null) return;
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setStroke(dp(1), cStroke);
        if (capColor == 0) {
            g.setColor(0x00000000);
            tvCapColor.setText("不改（抖音原来的颜色）");
        } else {
            g.setColor(capColor);
            String name = "";
            for (int i = 0; i < PAL.length; i++) if ((PAL[i] | 0xFF000000) == capColor) name = PAL_NAMES[i] + "  ";
            tvCapColor.setText("当前：" + name + String.format("#%06X", capColor & 0xFFFFFF));
        }
        capSwatch.setBackground(g);
    }

    /** 弹出色表：点一个颜色就选中并关掉；「不改」恢复成抖音原色。 */
    private void showPalette() {
        final AlertDialog[] dlg = new AlertDialog[1];
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(8), dp(16), dp(4));
        TextView none = pillButton(capColor == 0 ? "✓ 不改（抖音原色）" : "不改（抖音原色）", false);
        none.setTextSize(15);
        none.setOnClickListener(v -> {
            capColor = 0;
            showCapColor();
            dlg[0].dismiss();
        });
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-1, dp(44));
        nl.bottomMargin = dp(10);
        col.addView(none, nl);
        for (int r = 0; r < PAL.length; r += 6) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = r; i < r + 6; i++) {
                final int rgb = PAL[i] | 0xFF000000;
                LinearLayout cell = new LinearLayout(this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                TextView sw = new TextView(this);
                sw.setGravity(Gravity.CENTER);
                sw.setTextSize(18);
                sw.setTypeface(Typeface.DEFAULT_BOLD);
                GradientDrawable g = new GradientDrawable();
                g.setShape(GradientDrawable.OVAL);
                g.setColor(rgb);
                g.setStroke(dp(1), 0x66808080);
                sw.setBackground(g);
                if (rgb == capColor) {
                    sw.setText("✓");
                    sw.setTextColor(luminance(rgb) > 140 ? 0xFF000000 : 0xFFFFFFFF);
                }
                sw.setOnClickListener(v -> {
                    capColor = rgb;
                    showCapColor();
                    dlg[0].dismiss();
                });
                cell.addView(sw, new LinearLayout.LayoutParams(dp(40), dp(40)));
                TextView nm = tv(PAL_NAMES[i], 11, cSub, false);
                nm.setGravity(Gravity.CENTER);
                cell.addView(nm, new LinearLayout.LayoutParams(-2, -2));
                LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1f);
                cl.bottomMargin = dp(8);
                row.addView(cell, cl);
            }
            col.addView(row);
        }
        dlg[0] = new AlertDialog.Builder(this).setTitle("选择文案颜色").setView(col).setNegativeButton("取消", null).create();
        dlg[0].show();
    }

    private static int luminance(int rgb) {
        return (((rgb >> 16) & 0xFF) * 299 + ((rgb >> 8) & 0xFF) * 587 + (rgb & 0xFF) * 114) / 1000;
    }

    private void showOffset() {
        if (tvOffset != null) tvOffset.setText("当前位置  水平 " + signed(tX) + " px　垂直 " + signed(tY) + " px　字号 " + tSize + " sp");
    }

    private void showPubOffset() {
        if (tvPubOffset != null) tvPubOffset.setText("当前位置  水平 " + signed(pX) + " px　垂直 " + signed(pY) + " px　字号 " + pSize + " sp");
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
