package com.danmaku.randcolor;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.List;

/** 设置页：卡片 + 开关行 + 分段按钮 + 底部固定操作栏。纯框架控件，不依赖任何第三方库；跟随系统深浅色。 */
public class MainActivity extends Activity {
    static final String PREFS = "ui";
    static final String DEFAULT_PKG = "com.ss.android.ugc.aweme";
    // 配色（浅色 / 深色）
    private boolean dark;
    private int cBg, cCard, cStroke, cText, cSub, cPrimary, cPrimarySoft, cField;

    private EditText etPkg;
    private Switch swEnabled, swLikeBtn, swDtap, swTime;
    private LinearLayout segMode, segKey, segStep, statusCard;
    private TextView tvOffset;
    private int tDx, tDy;
    private static final int[] STEPS = {2, 10, 40};
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView tvStatus, btnPush;
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
        TextView ld = tv("右侧按钮：找不到评论按钮时不拦截，点赞照常；双击：始终不点赞，也不出红心。", 13, cSub, false);
        ld.setPadding(0, dp(12), 0, 0);
        like.addView(ld);
        root.addView(like, cardLp());

        // ---- 视频时长常显
        root.addView(section("视频时长常显"));
        LinearLayout tm = card();
        swTime = new Switch(this);
        tm.addView(switchRow("视频时长一直显示", "在视频上自己画一行 00:12 / 03:45，播放时随进度走动，不用碰进度条；离开视频页自动隐藏", swTime));
        tm.addView(divider());
        tm.addView(tv("位置微调", 17, cText, true));
        TextView td = tv("默认在屏幕正中，以此为起点移动（右、下为正，单位 dp）。点箭头走一格，长按连续移动；改完点下方「保存并应用」。", 13, cSub, false);
        td.setPadding(0, dp(2), 0, dp(10));
        tm.addView(td);
        segStep = segmented("细（2）", "中（10）", "粗（40）");
        setSeg(segStep, 1);
        tm.addView(segStep);
        tm.addView(dpad());
        root.addView(tm, cardLp());

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
        tDx = c.timeDx;
        tDy = c.timeDy;
        showOffset();
        setSeg(segMode, c.mode == 1 ? 1 : 0);
        setSeg(segKey, c.keyByText ? 1 : 0);
    }

    private DanmakuConfig read() {
        DanmakuConfig c = new DanmakuConfig();
        c.enabled = swEnabled.isChecked();
        c.mode = segIndex(segMode) == 1 ? 1 : 0;
        c.keyByText = segIndex(segKey) == 1;
        c.likeBtnToComment = swLikeBtn.isChecked();
        c.dtapToComment = swDtap.isChecked();
        c.timeAlways = swTime.isChecked();
        c.timeDx = tDx;
        c.timeDy = tDy;
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

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ------------------------------------------------------------ 方向键

    private View dpad() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(0, dp(14), 0, 0);
        box.addView(padRow(null, arrow("▲", 0, -1), null));
        tvOffset = tv("", 13, cSub, false);
        tvOffset.setGravity(Gravity.CENTER);
        tvOffset.setTypeface(Typeface.MONOSPACE);
        box.addView(padRow(arrow("◀", -1, 0), tvOffset, arrow("▶", 1, 0)));
        box.addView(padRow(null, arrow("▼", 0, 1), null));
        TextView reset = pillButton("归零", false);
        reset.setTextSize(15);
        reset.setOnClickListener(v -> {
            tDx = 0;
            tDy = 0;
            showOffset();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(120), dp(44));
        lp.topMargin = dp(12);
        box.addView(reset, lp);
        return box;
    }

    private LinearLayout padRow(View l, View m, View r) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        View[] vs = {l, m, r};
        for (View v : vs) {
            if (v == null) v = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(92), dp(56));
            lp.setMargins(dp(3), dp(3), dp(3), dp(3));
            row.addView(v, lp);
        }
        return row;
    }

    /** 方向键：按下走一格，按住 350ms 后每 70ms 再走一格，松手 / 取消就停。 */
    private TextView arrow(String label, final int sx, final int sy) {
        TextView t = tv(label, 22, cText, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(ripple(round(cField, 0, 16)));
        final Runnable[] rep = new Runnable[1];
        rep[0] = () -> {
            nudge(sx, sy);
            ui.postDelayed(rep[0], 70);
        };
        t.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                    v.setPressed(true);
                    nudge(sx, sy);
                    ui.postDelayed(rep[0], 350);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    ui.removeCallbacks(rep[0]);
                    return true;
                default:
                    return true;
            }
        });
        return t;
    }

    private void nudge(int sx, int sy) {
        int step = STEPS[Math.max(0, Math.min(STEPS.length - 1, segIndex(segStep)))];
        tDx = clampOffset(tDx + sx * step);
        tDy = clampOffset(tDy + sy * step);
        showOffset();
    }

    private static int clampOffset(int v) {
        return Math.max(-2000, Math.min(2000, v));
    }

    private void showOffset() {
        if (tvOffset != null) tvOffset.setText("水平 " + signed(tDx) + "\n垂直 " + signed(tDy));
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
