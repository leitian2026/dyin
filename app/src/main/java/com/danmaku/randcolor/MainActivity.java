package com.danmaku.randcolor;

import android.app.Activity;
import android.content.Context;
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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 设置页：卡片 + 开关行 + 分段按钮 + 标签胶囊 + 底部固定操作栏。纯框架控件，不依赖任何第三方库；跟随系统深浅色。 */
public class MainActivity extends Activity {
    static final String PREFS = "ui";
    static final String DEFAULT_PKG = "com.ss.android.ugc.aweme";
    /** 抖音首页顶部标签的默认列表（来自实测，从左到右）；点「读取抖音当前的标签」后以抖音里实际的为准。 */
    private static final String DEFAULT_TABS = "热点\n经验\n游戏\n少儿\n同城\n精选\n团购\n免费看剧\n商城\n短剧\n直播\n关注\n推荐";

    // 配色（浅色 / 深色）
    private boolean dark;
    private int cBg, cCard, cStroke, cText, cSub, cPrimary, cPrimarySoft, cField;

    private EditText etPkg;
    private Switch swEnabled, swLikeBtn, swDtap;
    private LinearLayout segMode, segKey, statusCard;
    private Flow tabFlow;
    private final ArrayList<TextView> chips = new ArrayList<>();
    private TextView tvStatus, tvTabCount, btnPush, btnCheck, btnReadTabs;
    private ScrollView scroll;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("弹幕随机色");
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
        TextView title = tv("弹幕随机色", 30, cText, true);
        title.setPadding(dp(4), 0, 0, 0);
        root.addView(title);
        TextView sub = tv("抖音弹幕随机颜色 · 隐藏首页顶部标签", 14, cSub, false);
        sub.setPadding(dp(4), dp(2), 0, dp(14));
        root.addView(sub);

        // ---- 怎么用
        LinearLayout how = card();
        how.addView(tv("怎么用", 20, cText, true));
        TextView pre = tv("前提：已在 LSPosed 里启用本模块，并在作用域勾选抖音。", 14, cSub, false);
        pre.setPadding(0, dp(4), 0, dp(14));
        how.addView(pre);
        how.addView(step(1, "启用并重开抖音", "LSPosed 里启用模块、勾选抖音，然后强杀抖音再打开（只需做一次）"));
        how.addView(step(2, "调整下面的设置", "选好弹幕颜色，点选要隐藏的首页标签"));
        how.addView(step(3, "点下方「保存并应用」", "把设置推送给抖音（需要 root）。抖音回到前台约 2 秒内生效，不用重启"));
        TextView hint = tv("没效果？点下方「检查状态」，会告诉你卡在哪一步。", 13, cSub, false);
        hint.setPadding(0, dp(2), 0, 0);
        how.addView(hint);
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
        colors.addView(divider());
        colors.addView(tv("同一条弹幕怎么固定颜色", 17, cText, true));
        TextView d2 = tv("弹幕滚动时每帧都在重绘，必须固定，否则会闪。推荐按弹幕对象；如果会闪，换成按文字内容。", 13, cSub, false);
        d2.setPadding(0, dp(2), 0, dp(10));
        colors.addView(d2);
        segKey = segmented("按弹幕对象（推荐）", "按文字内容");
        colors.addView(segKey);
        root.addView(colors, cardLp());

        // ---- 点赞改为打开评论
        root.addView(section("点赞 → 打开评论"));
        LinearLayout like = card();
        swLikeBtn = new Switch(this);
        like.addView(switchRow("点右侧爱心按钮时打开评论", "不再点赞（也不会取消赞），改为点开这个视频的评论", swLikeBtn));
        like.addView(divider());
        swDtap = new Switch(this);
        like.addView(switchRow("双击视频时打开评论", "双击不再点赞，改为打开评论", swDtap));
        TextView ld = tv("找不到当前视频的评论按钮时不会拦截，点赞照常。", 13, cSub, false);
        ld.setPadding(0, dp(12), 0, 0);
        like.addView(ld);
        root.addView(like, cardLp());

        // ---- 首页顶部标签
        root.addView(section("首页顶部标签"));
        LinearLayout tabs = card();
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(tv("要隐藏的标签", 17, cText, true), new LinearLayout.LayoutParams(0, -2, 1f));
        tvTabCount = tv("", 14, cPrimary, true);
        head.addView(tvTabCount);
        tabs.addView(head);
        TextView td = tv("点一下切换，蓝色 = 隐藏。只隐藏标签栏上的这一项，首页左右滑动仍会切到对应页面。", 13, cSub, false);
        td.setPadding(0, dp(2), 0, dp(12));
        tabs.addView(td);
        tabFlow = new Flow(this, dp(8), dp(8));
        tabs.addView(tabFlow, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout acts = new LinearLayout(this);
        acts.setOrientation(LinearLayout.HORIZONTAL);
        acts.setPadding(0, dp(10), 0, 0);
        btnReadTabs = textButton("读取抖音当前的标签", v -> readTabs());
        acts.addView(btnReadTabs);
        TextView clear = textButton("全部取消", v -> {
            for (TextView c : chips) styleChip(c, false);
            updateTabCount();
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.leftMargin = dp(8);
        acts.addView(clear, clp);
        tabs.addView(acts);
        root.addView(tabs, cardLp());

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
        setSeg(segMode, c.mode == 1 ? 1 : 0);
        setSeg(segKey, c.keyByText ? 1 : 0);
        buildTabs(DanmakuConfig.split(sp.getString("tabs", DEFAULT_TABS)), c.hideTabs);
    }

    private DanmakuConfig read() {
        DanmakuConfig c = new DanmakuConfig();
        c.enabled = swEnabled.isChecked();
        c.mode = segIndex(segMode) == 1 ? 1 : 0;
        c.keyByText = segIndex(segKey) == 1;
        c.likeBtnToComment = swLikeBtn.isChecked();
        c.dtapToComment = swDtap.isChecked();
        for (TextView t : chips) if (t.isSelected()) c.hideTabs.add(t.getText().toString());
        return c;
    }

    private void save() {
        final String cfgText = read().toText();
        final String pkgs = etPkg.getText().toString().trim();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("cfg", cfgText).putString("pkg", pkgs)
                .putString("tabs", String.join("\n", tabLabels())).apply();
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
            sb.append("\n抖音回到前台后约 2 秒内生效，不用重启。抖音清除数据/重装后需要重新推送（不推送则用默认：开启+鲜艳）。");
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                setBusy(btnPush, false, "保存并应用");
                showStatus(sb.toString(), sb.indexOf("✗") >= 0 ? 2 : 1);
            });
        }).start();
    }

    private void check() {
        final List<String> list = DanmakuConfig.split(etPkg.getText().toString());
        if (list.isEmpty()) {
            showStatus("请先填目标包名", 2);
            return;
        }
        setBusy(btnCheck, true, "读取中…");
        showStatus("正在读取状态…", 0);
        new Thread(() -> {
            final StringBuilder sb = new StringBuilder();
            for (String pkg : list) {
                if (!RootShell.validPkg(pkg)) {
                    sb.append(pkg).append("：包名格式不对\n");
                    continue;
                }
                RootShell.Result r = RootShell.readStatus(pkg);
                sb.append("== ").append(pkg).append(" ==\n");
                if (r.out.contains("__NOPKG__")) {
                    sb.append(RootShell.nopkgMsg(r.out)).append('\n');
                } else if (r.code != 0 && r.out.isEmpty()) {
                    sb.append("读取失败(").append(r.code).append(") ").append(r.err.trim()).append('\n');
                } else {
                    sb.append(diagnose(r.out));
                }
            }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                setBusy(btnCheck, false, "检查状态");
                String s = sb.toString();
                showStatus(s, s.contains("✓") ? 1 : (s.contains("✗") || s.contains("失败") ? 2 : 0));
            });
        }).start();
    }

    /** 把 Hook 写的状态文件翻译成结论 + 最近几行原文。 */
    private static String diagnose(String out) {
        StringBuilder sb = new StringBuilder();
        if (out.trim().isEmpty()) {
            sb.append("没有状态文件：确认 LSPosed 已启用本模块、作用域勾了抖音，并且强杀抖音后重新打开过。\n");
            return sb.toString();
        }
        if (out.contains("已改色")) sb.append("✓ 已经在改弹幕颜色了。\n");
        else if (out.contains("已挂接")) sb.append("已挂接，但还没改到色：去看有弹幕的视频（或发一条弹幕）后再点检查。\n");
        else if (out.contains("没找到匹配的 paint")) sb.append("✗ 抖音这个版本的 Compose 接口变了，需要更新模块（把下面的内容发给我）。\n");
        else if (out.contains("暂未找到 Compose")) sb.append("还没找到 Compose 文字类：打开抖音进入视频页后再检查。\n");
        else sb.append("模块已加载，但还没挂接：打开抖音进入视频页后再检查。\n");
        if (out.contains("点赞入口被触发")) sb.append("点赞按钮入口：已触发过（下面“最近状态”里有调用栈）。\n");
        java.util.LinkedHashSet<String> taps = new java.util.LinkedHashSet<>();
        for (String ln : out.split("\n")) {
            int i = ln.indexOf("双击监听被触发：");
            if (i >= 0) taps.add(ln.substring(i + 8).trim());
        }
        if (taps.isEmpty()) {
            sb.append("双击：状态里还没有双击记录——在视频上双击一下再点检查（日志只保留最近一段，隔太久可能已被挤掉）。\n");
        } else {
            sb.append("双击：识别到 ").append(taps.size()).append(" 个双击回调：\n");
            for (String t : taps) sb.append("  · ").append(t).append('\n');
        }
        if (out.contains("已挡掉双击红心") || out.contains("（已挡掉）") || out.contains("（已隐藏）")) sb.append("✓ 双击红心动画已被挡掉。\n");
        if (((out.contains("双击后新增 View") || out.contains("双击后由触摸新增 View")) || out.contains("双击后动画开始") || out.contains("双击后 ViewStub") || out.contains("双击后 Lottie") || out.contains("双击后 View 动画")) && !out.contains("（已挡掉）") && !out.contains("（已隐藏）") && !out.contains("已挡掉双击红心")) sb.append("△ 红心动画还没挡到：把“最近状态”里带「双击后」的几行发给我。\n");
        if (out.contains("已拦下双击点赞")) sb.append("✓ 双击点赞已被拦下。\n");
        if (out.contains("双击后点赞仍被触发")) sb.append("✗ 双击后点赞仍然被触发了：调用栈在下面“最近状态”里，把它发给我就能找到真正的点赞入口。\n");
        if (out.contains("里没有叫")) sb.append("✗ 点赞入口方法名对不上：抖音版本变了，需要重新记录。\n");
        String[] lines = out.trim().split("\n");
        int from = Math.max(0, lines.length - 30);
        sb.append("\n最近状态：\n");
        for (int i = from; i < lines.length; i++) sb.append(lines[i]).append('\n');
        return sb.toString();
    }

    // ------------------------------------------------------------ 顶部标签

    /** labels = 全部标签（屏幕顺序），hide = 当前要隐藏的。 */
    private void buildTabs(List<String> labels, Set<String> hide) {
        tabFlow.removeAllViews();
        chips.clear();
        for (String s : labels) {
            final TextView c = new TextView(this);
            c.setText(s);
            c.setTextSize(15);
            c.setGravity(Gravity.CENTER);
            c.setPadding(dp(16), dp(9), dp(16), dp(9));
            styleChip(c, hide.contains(s));
            c.setOnClickListener(v -> {
                styleChip(c, !c.isSelected());
                updateTabCount();
            });
            tabFlow.addView(c, new ViewGroup.LayoutParams(-2, -2));
            chips.add(c);
        }
        updateTabCount();
    }

    private List<String> tabLabels() {
        ArrayList<String> l = new ArrayList<>();
        for (TextView t : chips) l.add(t.getText().toString());
        return l;
    }

    private void updateTabCount() {
        int n = 0;
        for (TextView t : chips) if (t.isSelected()) n++;
        tvTabCount.setText(n == 0 ? "未隐藏" : "已隐藏 " + n + " 个");
    }

    /** 选中（隐藏）= 蓝底白字；未选中 = 描边。 */
    private void styleChip(TextView c, boolean selected) {
        c.setSelected(selected);
        c.setTextColor(selected ? 0xFFFFFFFF : cText);
        c.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
        c.setBackground(ripple(selected ? round(cPrimary, 0, 20) : round(cCard, cStroke, 20)));
    }

    private void readTabs() {
        final List<String> list = DanmakuConfig.split(etPkg.getText().toString());
        if (list.isEmpty() || !RootShell.validPkg(list.get(0))) {
            showStatus("请先填目标包名", 2);
            return;
        }
        final String pkg = list.get(0);
        setBusy(btnReadTabs, true, "读取中…");
        new Thread(() -> {
            final RootShell.Result r = RootShell.readTabs(pkg);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                setBusy(btnReadTabs, false, "读取抖音当前的标签");
                if (r.out.contains("__NOPKG__")) {
                    showStatus(RootShell.nopkgMsg(r.out), 2);
                    return;
                }
                List<String> found = DanmakuConfig.split(r.out);
                if (found.isEmpty()) {
                    showStatus("还没读到标签：确认模块已启用，强杀抖音重开，停在首页等顶部标签栏出现后再点。", 2);
                    return;
                }
                // 以抖音里的实际顺序为准；已勾选但这次没看到的（比如已经被隐藏）也保留，免得没法取消
                Set<String> checked = new HashSet<>();
                for (TextView t : chips) if (t.isSelected()) checked.add(t.getText().toString());
                ArrayList<String> all = new ArrayList<>(found);
                for (String s : tabLabels()) if (checked.contains(s) && !all.contains(s)) all.add(s);
                buildTabs(all, checked);
                showStatus("已读到 " + found.size() + " 个标签。点选要隐藏的，再点下方「保存并应用」。", 1);
            });
        }).start();
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

    private TextView textButton(String s, View.OnClickListener l) {
        TextView t = tv(s, 15, cPrimary, true);
        t.setPadding(dp(4), dp(8), dp(8), dp(8));
        t.setOnClickListener(l);
        return t;
    }

    private TextView pillButton(String s, boolean filled) {
        TextView t = tv(s, 17, filled ? 0xFFFFFFFF : cText, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(ripple(filled ? round(cPrimary, 0, 26) : round(cCard, cStroke, 26)));
        return t;
    }

    /** 底部固定操作栏：左边次要操作（检查状态），右边主操作（保存并应用）。 */
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
        btnCheck = pillButton("检查状态", false);
        btnCheck.setOnClickListener(v -> check());
        btnPush = pillButton("保存并应用", true);
        btnPush.setOnClickListener(v -> save());
        row.addView(btnCheck, new LinearLayout.LayoutParams(0, dp(52), 1f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(52), 1.5f);
        lp.leftMargin = dp(12);
        row.addView(btnPush, lp);
        bar.addView(row);
        return bar;
    }

    /** 自动换行的流式布局（标签胶囊用）。 */
    private static final class Flow extends ViewGroup {
        private final int hGap, vGap;

        Flow(Context c, int hGap, int vGap) {
            super(c);
            this.hGap = hGap;
            this.vGap = vGap;
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int maxW = MeasureSpec.getSize(wSpec) - getPaddingLeft() - getPaddingRight();
            int x = 0, y = 0, rowH = 0, usedW = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                if (c.getVisibility() == GONE) continue;
                c.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                int w = c.getMeasuredWidth(), h = c.getMeasuredHeight();
                if (x > 0 && x + w > maxW) {
                    x = 0;
                    y += rowH + vGap;
                    rowH = 0;
                }
                x += w + hGap;
                rowH = Math.max(rowH, h);
                usedW = Math.max(usedW, x - hGap);
            }
            setMeasuredDimension(resolveSize(usedW + getPaddingLeft() + getPaddingRight(), wSpec),
                    resolveSize(y + rowH + getPaddingTop() + getPaddingBottom(), hSpec));
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int maxW = r - l - getPaddingLeft() - getPaddingRight();
            int x = 0, y = 0, rowH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                if (c.getVisibility() == GONE) continue;
                int w = c.getMeasuredWidth(), h = c.getMeasuredHeight();
                if (x > 0 && x + w > maxW) {
                    x = 0;
                    y += rowH + vGap;
                    rowH = 0;
                }
                c.layout(getPaddingLeft() + x, getPaddingTop() + y, getPaddingLeft() + x + w, getPaddingTop() + y + h);
                x += w + hGap;
                rowH = Math.max(rowH, h);
            }
        }
    }
}
