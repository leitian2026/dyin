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
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Properties;
import java.util.function.Supplier;

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
    /** 时长 / 发布信息的字号（sp）、三处颜色（0 = 默认 / 不改）、发布信息的积木列表。 */
    private SizeField timeSizeF, pubSizeF;
    private ColorField timeColorF, pubColorF, capColorF;
    private final java.util.ArrayList<Block> blocks = new java.util.ArrayList<>();
    private LinearLayout blockList;
    private Switch swCapOutline;
    /** 直播间开播时间：开关、取哪个时间、模板、字号、颜色。位置（百分比）不在设置页调，在抖音里用浮窗拖，这里只存 / 显示。 */
    private Switch swLive;
    private LinearLayout segLiveSrc;
    private EditText etLiveFmt;
    private SizeField liveSizeF;
    private ColorField liveColorF;
    private int liveX = 3, liveY = 10;
    private PctField bgF;
    private TextView tvLivePos, btnAdjust;
    private TextView tvStatus, btnPush, btnHooks;
    private ScrollView scroll;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
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
        title.setPadding(dp(4), 0, 0, dp(2));
        root.addView(title);

        // ================= 弹幕与点赞
        root.addView(groupLabel("弹幕与点赞"));

        swEnabled = new Switch(this);
        Section sDanmaku = new Section("弹幕随机色", false, swEnabled,
                () -> swEnabled.isChecked() ? "已开启 · " + (segIndex(segMode) == 1 ? "任意" : "鲜艳") : "已关闭");
        sDanmaku.body.addView(note("鲜艳：高饱和亮色，看得清；任意：含浅色、粉彩，更杂。关掉右上角的开关，弹幕恢复原本的颜色。"));
        segMode = segmented("鲜艳", "任意");
        sDanmaku.body.addView(segMode);
        root.addView(sDanmaku.box, cardLp());

        swLikeBtn = new Switch(this);
        swDtap = new Switch(this);
        Section sLike = new Section("点赞改为打开评论", false, null, () -> {
            boolean a = swLikeBtn.isChecked(), b = swDtap.isChecked();
            return a && b ? "爱心按钮 · 双击视频" : a ? "爱心按钮" : b ? "双击视频" : "未开启";
        });
        sLike.body.addView(switchRow("点右侧爱心按钮时打开评论", "不再点赞（也不会取消赞）；找不到评论按钮时点赞照常", swLikeBtn));
        sLike.body.addView(divider());
        sLike.body.addView(switchRow("双击视频时打开评论", "双击不再点赞，也不出红心", swDtap));
        root.addView(sLike.box, cardLp());

        // ================= 视频页
        root.addView(groupLabel("视频页"));

        swTime = new Switch(this);
        timeSizeF = new SizeField(20);
        timeColorF = new ColorField("默认（白色）", "选择时长颜色");
        Section sTime = new Section("视频时长常显", false, swTime,
                () -> swTime.isChecked() ? "已开启 · " + timeSizeF.v + " sp" : "已关闭");
        sTime.body.addView(note("在视频文案下面显示一行 00:12 / 03:45，播放时随进度走动；刷到直播或离开视频页自动隐藏。位置由抖音的布局决定，不用调。"));
        sTime.body.addView(sub("字号（8～40 sp）"));
        sTime.body.addView(timeSizeF.build());
        sTime.body.addView(sub("颜色"));
        sTime.body.addView(timeColorF.build());
        root.addView(sTime.box, cardLp());

        swPub = new Switch(this);
        pubSizeF = new SizeField(16);
        pubColorF = new ColorField("默认（半透明白）", "选择发布信息颜色");
        Section sPub = new Section("发布日期和地点常显", false, swPub,
                () -> swPub.isChecked() ? "已开启 · " + pubSizeF.v + " sp" : "已关闭");
        sPub.body.addView(note("在视频文案下面单独显示一行（有作者声明时在声明上方），取自视频本身的发布时间和 IP 属地，不用点「展开」。字号和颜色改完，划到下一个视频生效。"));
        Section sBlocks = new Section("显示内容和顺序", true, null, () -> {
            String s = PubFormat.render(toTemplate(), PREVIEW_SEC, "广西", "南宁");
            return s == null ? "预览：（空，不显示）" : "预览：" + oneLine(s);
        });
        sBlocks.body.addView(note("点 ↑ ↓ 调顺序，✕ 删除；「+ 文字」加自己的字（空格原样保留），「+ 换行」另起一行。太长会自动换行，不截断。"));
        blockList = new LinearLayout(this);
        blockList.setOrientation(LinearLayout.VERTICAL);
        sBlocks.body.addView(blockList);
        LinearLayout add1 = new LinearLayout(this);
        add1.setOrientation(LinearLayout.HORIZONTAL);
        String[] toks = {"日期", "时间", "地点", "省", "市"};
        for (int i = 0; i < toks.length; i++) {
            final String tok = toks[i];
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, dp(44), 1f);
            if (i > 0) cl.leftMargin = dp(6);
            add1.addView(addButton("+ " + tok, () -> blocks.add(new Block(tok, null))), cl);
        }
        LinearLayout.LayoutParams a1l = new LinearLayout.LayoutParams(-1, -2);
        a1l.topMargin = dp(4);
        sBlocks.body.addView(add1, a1l);
        LinearLayout add2 = new LinearLayout(this);
        add2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams c1 = new LinearLayout.LayoutParams(0, dp(44), 1f);
        add2.addView(addButton("+ 文字", () -> blocks.add(new Block(null, ""))), c1);
        LinearLayout.LayoutParams c2 = new LinearLayout.LayoutParams(0, dp(44), 1f);
        c2.leftMargin = dp(6);
        add2.addView(addButton("+ 换行", () -> blocks.add(new Block("换行", null))), c2);
        LinearLayout.LayoutParams c3 = new LinearLayout.LayoutParams(0, dp(44), 1f);
        c3.leftMargin = dp(6);
        add2.addView(addButton("恢复默认", () -> parseTemplate(PubFormat.DEFAULT)), c3);
        LinearLayout.LayoutParams a2l = new LinearLayout.LayoutParams(-1, -2);
        a2l.topMargin = dp(8);
        sBlocks.body.addView(add2, a2l);
        sPub.body.addView(sBlocks.box, nestLp());
        sPub.body.addView(sub("字号（8～40 sp）"));
        sPub.body.addView(pubSizeF.build());
        sPub.body.addView(sub("颜色"));
        sPub.body.addView(pubColorF.build());
        root.addView(sPub.box, cardLp());

        bgF = new PctField(40);
        Section sBg = new Section("文字灰色底", false, null, () -> bgF.v == 0 ? "已关闭" : "不透明度 " + bgF.v + "%");
        sBg.body.addView(note("给视频文案、视频时长、发布日期地点的每一行字加一条灰色底，底比字大一个字号（四周各多 1 sp）。0% = 不加。文案的底在文案控件边界处可能被裁掉一点。"));
        sBg.body.addView(sub("不透明度"));
        sBg.body.addView(bgF.build());
        root.addView(sBg.box, cardLp());

        swCapOutline = new Switch(this);
        capColorF = new ColorField("不改（抖音原来的颜色）", "选择文案颜色");
        Section sCap = new Section("视频文案样式", false, null,
                () -> "描边 " + (swCapOutline.isChecked() ? "开" : "关") + " · 颜色 " + (capColorF.color == 0 ? "不改" : "自定义"));
        sCap.body.addView(switchRow("给视频文案加黑色描边", "亮背景上也看得清；关掉后要重启抖音才恢复", swCapOutline));
        sCap.body.addView(divider());
        sCap.body.addView(sub("文案颜色"));
        sCap.body.addView(note("话题、@ 这类抖音自己上色的文字不变；选过颜色再改回「不改」，要重启抖音才恢复。"));
        sCap.body.addView(capColorF.build());
        root.addView(sCap.box, cardLp());

        // ================= 直播间
        root.addView(groupLabel("直播间"));

        swLive = new Switch(this);
        liveSizeF = new SizeField(14);
        liveColorF = new ColorField("默认（白色）", "选择开播时间颜色");
        tvLivePos = tv("", 15, cText, false);
        Section sLive = new Section("直播间开播时间", false, swLive,
                () -> swLive.isChecked() ? "已开启 · 位置 " + liveX + "% / " + liveY + "%" : "已关闭");
        sLive.body.addView(note("进直播间时在画面上叠一行开播时间，只在直播间里显示，推荐、关注、同城这些界面都不显示。重新进直播间生效。"));
        Section sLiveFmt = new Section("来源与格式", true, null, () -> {
            String s = LiveFormat.render(etLiveFmt.getText().toString(), PREVIEW_SEC);
            return s == null ? "（没有内容）" : "预览：" + oneLine(s);
        });
        sLiveFmt.body.addView(sub("显示哪个时间"));
        sLiveFmt.body.addView(note("「开播时间」是房间数据里的 startTime（没有就用创建时间）；「房间创建时间」是 createTime，通常比开播早几秒。哪个更准要在你的直播间里对一下。"));
        segLiveSrc = segmented("开播时间", "房间创建时间");
        sLiveFmt.body.addView(segLiveSrc);
        sLiveFmt.body.addView(sub("显示格式"));
        sLiveFmt.body.addView(note("可用：{日期} 如 2026-10-22，{时间} 如 07:22:36，{月日} 如 10-22；其他文字原样显示，两个字符 \\n 表示换行。"));
        etLiveFmt = field();
        etLiveFmt.setHint(LiveFormat.DEFAULT);
        watch(etLiveFmt);
        sLiveFmt.body.addView(etLiveFmt);
        sLive.body.addView(sLiveFmt.box, nestLp());
        sLive.body.addView(sub("字号（8～40 sp）"));
        sLive.body.addView(liveSizeF.build());
        sLive.body.addView(sub("颜色（带一圈黑边，亮背景上也看得清）"));
        sLive.body.addView(liveColorF.build());
        sLive.body.addView(sub("位置"));
        LinearLayout posRow = new LinearLayout(this);
        posRow.setOrientation(LinearLayout.HORIZONTAL);
        posRow.setGravity(Gravity.CENTER_VERTICAL);
        posRow.addView(tvLivePos, new LinearLayout.LayoutParams(0, -2, 1f));
        btnAdjust = pillButton("去抖音里调整", true);
        btnAdjust.setTextSize(15);
        btnAdjust.setOnClickListener(v -> adjustLivePos());
        posRow.addView(btnAdjust, new LinearLayout.LayoutParams(dp(136), dp(48)));
        sLive.body.addView(posRow);
        TextView adjNote = note("点按钮会先保存并应用当前设置，再打开抖音。进入直播间后，在屏幕上拖动文字，点浮窗里的「完成」。");
        adjNote.setPadding(0, dp(8), 0, 0);
        sLive.body.addView(adjNote);
        root.addView(sLive.box, cardLp());

        // ================= 界面与系统
        root.addView(groupLabel("界面与系统"));

        Section sTabs = new Section("首页顶部标签", false, null, () -> {
            int n = 0;
            for (CheckBox cb : cbTabs) if (cb != null && cb.isChecked()) n++;
            return n == 0 ? "全部显示" : "隐藏 " + n + " 个";
        });
        sTabs.body.addView(note("勾选的标签会被隐藏；取消勾选后要重启抖音才恢复。只隐藏标签，左右划动仍会翻到它的页面。追踪（抖音 40.7.0）里这台手机当前显示的是：推荐、关注、热点、同城、商城，其余 8 个抖音没有启用，哪天启用了才会出现。"));
        for (int r = 0; r < TAB_LABELS.length; r += 3) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = r; i < r + 3; i++) {
                if (i < TAB_LABELS.length) {
                    CheckBox cb = new CheckBox(this);
                    cb.setText(TAB_LABELS[i]);
                    cb.setTextSize(15);
                    cb.setTextColor(cText);
                    cb.setOnCheckedChangeListener((b, on) -> refreshSummaries());
                    cbTabs[i] = cb;
                    row.addView(cb, new LinearLayout.LayoutParams(0, -2, 1f));
                } else {
                    row.addView(new View(this), new LinearLayout.LayoutParams(0, 0, 1f));
                }
            }
            sTabs.body.addView(row);
        }
        root.addView(sTabs.box, cardLp());

        etIdle = field();
        etIdle.setInputType(InputType.TYPE_CLASS_NUMBER);
        etIdle.setHint("分钟，0 = 关闭");
        watch(etIdle);
        Section sIdle = new Section("无操作自动退出", false, null, () -> {
            String t = etIdle.getText().toString().trim();
            return t.isEmpty() || t.equals("0") ? "已关闭" : t + " 分钟无操作后退出";
        });
        sIdle.body.addView(note("连续这么多分钟没有触摸、按键，也没有回到前台，就结束抖音进程（后台也计时）。回到前台算一次操作，重新开始。填 0 = 关闭。"));
        sIdle.body.addView(etIdle);
        root.addView(sIdle.box, cardLp());

        // ================= 高级
        root.addView(groupLabel("高级"));

        etPkg = field();
        watch(etPkg);
        Section sAdv = new Section("目标 App 与钩子状态", false, null, () -> "包名 " + etPkg.getText().toString().trim());
        sAdv.body.addView(sub("包名"));
        sAdv.body.addView(note("要作用的 App，多个包名用逗号分隔，默认是抖音。"));
        sAdv.body.addView(etPkg);
        sAdv.body.addView(divider());
        sAdv.body.addView(sub("钩子状态"));
        sAdv.body.addView(note("抖音更新后，模块会自己按特征重新找钩子，找不到的会放弃并记住（同一个版本不再反复尝试）。点下面的按钮用 root 读取每个钩子是否可用。"));
        btnHooks = pillButton("检查钩子状态", false);
        btnHooks.setTextSize(16);
        btnHooks.setOnClickListener(v -> checkHooks());
        sAdv.body.addView(btnHooks, new LinearLayout.LayoutParams(-1, dp(48)));
        root.addView(sAdv.box, cardLp());

        // ---- 状态（有内容才显示）
        statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusCard.setPadding(dp(18), dp(14), dp(18), dp(14));
        tvStatus = tv("", 13, cText, false);
        tvStatus.setTypeface(Typeface.MONOSPACE);
        tvStatus.setTextIsSelectable(true);
        statusCard.addView(tvStatus);
        statusCard.setVisibility(View.GONE);
        LinearLayout.LayoutParams slp = cardLp();
        slp.topMargin = dp(8);
        root.addView(statusCard, slp);

        load();
        ready = true;
        refreshSummaries();
    }

    // ------------------------------------------------------------ 折叠分组

    /** 顶层分组：展开一个，其他自动收起（手风琴）。 */
    private final ArrayList<Section> sections = new ArrayList<>();
    /** 所有分组（含子分组）：刷新摘要用。 */
    private final ArrayList<Section> allSections = new ArrayList<>();
    /** 控件都建好、load() 做完之后才刷新摘要。 */
    private boolean ready;

    /**
     * 折叠分组：标题 + 一行摘要（开没开、关键数值）+ 可选的总开关 + 箭头。点标题行展开 / 收起；总开关在标题行里，不用展开就能开关。
     * nested = true 是分组里面的子分组（灰底、不参与手风琴）。内容往 body 里加。
     */
    private final class Section {
        final LinearLayout box, body;
        final TextView tvSum, chev;
        final boolean nested;
        final Supplier<String> sum;
        boolean open;

        Section(String title, boolean nested, Switch master, Supplier<String> sum) {
            this.nested = nested;
            this.sum = sum;
            box = new LinearLayout(MainActivity.this);
            box.setOrientation(LinearLayout.VERTICAL);
            if (nested) {
                box.setPadding(dp(14), dp(10), dp(14), dp(10));
                box.setBackground(round(cField, 0, 14));
            } else {
                box.setPadding(dp(20), dp(12), dp(16), dp(12));
                box.setBackground(round(cCard, cStroke, 22));
            }
            LinearLayout head = new LinearLayout(MainActivity.this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.setMinimumHeight(dp(nested ? 40 : 52));
            LinearLayout txt = new LinearLayout(MainActivity.this);
            txt.setOrientation(LinearLayout.VERTICAL);
            txt.addView(tv(title, nested ? 15 : 17, cText, true));
            tvSum = tv("", nested ? 12 : 13, cSub, false);
            tvSum.setSingleLine(true);
            tvSum.setEllipsize(TextUtils.TruncateAt.END);
            tvSum.setPadding(0, dp(2), 0, 0);
            txt.addView(tvSum);
            head.addView(txt, new LinearLayout.LayoutParams(0, -2, 1f));
            if (master != null) {
                styleSwitch(master);
                LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-2, -2);
                ml.leftMargin = dp(8);
                head.addView(master, ml);
            }
            chev = tv("▾", 18, cSub, true);
            chev.setGravity(Gravity.CENTER);
            head.addView(chev, new LinearLayout.LayoutParams(dp(32), dp(32)));
            head.setOnClickListener(v -> setOpen(!open));
            box.addView(head, new LinearLayout.LayoutParams(-1, -2));
            body = new LinearLayout(MainActivity.this);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setPadding(0, dp(10), nested ? 0 : dp(4), dp(4));
            body.setVisibility(View.GONE);
            box.addView(body, new LinearLayout.LayoutParams(-1, -2));
            allSections.add(this);
            if (!nested) sections.add(this);
        }

        void setOpen(boolean o) {
            if (o == open) return;
            int before = box.getTop() - scroll.getScrollY(); // 标题行现在在屏幕上的位置
            open = o;
            body.setVisibility(o ? View.VISIBLE : View.GONE);
            chev.setText(o ? "▴" : "▾");
            if (o && !nested) {
                boolean closed = false;
                for (Section s : sections) {
                    if (s != this && s.open) {
                        s.setOpen(false);
                        closed = true;
                    }
                }
                if (closed) keepInPlace(box, before); // 上面的分组收起会把内容顶上去，滚回来让标题行原地不动
            }
        }

        void refresh() {
            String s;
            try {
                s = sum == null ? null : sum.get();
            } catch (Throwable t) {
                s = null;
            }
            tvSum.setText(s == null ? "" : s);
            tvSum.setVisibility(s == null || s.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    private void refreshSummaries() {
        if (!ready) return;
        for (Section s : allSections) s.refresh();
    }

    /** 展开原地进行：别的分组收起后这一轮布局做完，如果 v 在屏幕上的位置变了，就把滚动补回去，v 保持在原来的位置。 */
    private void keepInPlace(final View v, final int before) {
        v.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                v.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                int now = v.getTop() - scroll.getScrollY();
                if (now != before) scroll.scrollBy(0, now - before);
            }
        });
    }

    private static String oneLine(String s) {
        return s.replace("\n", " ↵ ");
    }

    /** 输入框内容变了 → 刷新摘要。 */
    private void watch(EditText e) {
        e.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence cs, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence cs, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable ed) {
                refreshSummaries();
            }
        });
    }

    /** 分组里一行说明（小灰字）。 */
    private TextView note(String s) {
        TextView t = tv(s, 13, cSub, false);
        t.setPadding(0, 0, 0, dp(10));
        return t;
    }

    /** 分组里一个小标题（「字号」「颜色」……）。 */
    private TextView sub(String s) {
        TextView t = tv(s, 14, cText, true);
        t.setPadding(0, dp(14), 0, dp(6));
        return t;
    }

    private LinearLayout.LayoutParams nestLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(4);
        lp.bottomMargin = dp(4);
        return lp;
    }

    // ------------------------------------------------------------ 位置：去抖音里调

    private void updateLivePos() {
        if (tvLivePos != null) tvLivePos.setText("水平 " + liveX + "%  ·  垂直 " + liveY + "%");
        refreshSummaries();
    }

    /** 保存并应用当前设置（带上「位置调节模式」），成功后打开抖音；位置在抖音的直播间里用浮窗拖。 */
    private void adjustLivePos() {
        final String pkgs = etPkg.getText().toString().trim();
        final List<String> list = DanmakuConfig.split(pkgs);
        if (list.isEmpty()) {
            showStatus("请先在「高级」里填目标包名", 2);
            return;
        }
        DanmakuConfig c = read();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("cfg", c.toText()).putString("pkg", pkgs).apply();
        c.liveAdjust = true; // 只推给抖音；设置页自己存的配置里没有这一项
        push(list, c.toText(), true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (etPkg != null && getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("adj", false)) syncAdjust();
    }

    /** 从抖音那边读回浮窗里调好的位置（用 root 读抖音自己的配置文件）。 */
    private void syncAdjust() {
        final List<String> list = DanmakuConfig.split(etPkg.getText().toString().trim());
        if (list.isEmpty()) return;
        final String pkg = list.get(0);
        new Thread(() -> {
            final String text = RootShell.readConfig(pkg);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (text == null) {
                    showStatus("没读到抖音里调好的位置（没 root，或抖音还没运行过）。位置还是上次保存的：水平 " + liveX + "% · 垂直 " + liveY + "%", 2);
                    return;
                }
                DanmakuConfig got = DanmakuConfig.fromText(text);
                if (got.liveAdjust) {
                    showStatus("抖音里的位置浮窗还没点「完成」或「取消」。进直播间调好、点完成后，再回到这里。", 0);
                    return;
                }
                liveX = got.liveX;
                liveY = got.liveY;
                updateLivePos();
                SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
                DanmakuConfig mine = DanmakuConfig.fromText(sp.getString("cfg", ""));
                mine.liveX = liveX;
                mine.liveY = liveY;
                sp.edit().putString("cfg", mine.toText()).putBoolean("adj", false).apply();
                showStatus("已同步抖音里的位置：水平 " + liveX + "% · 垂直 " + liveY + "%", 1);
            });
        }).start();
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
        swPub.setChecked(c.pubAlways);
        timeSizeF.set(c.timeSize);
        pubSizeF.set(c.pubSize);
        parseTemplate(c.pubFormat);
        renderBlocks();
        timeColorF.set(c.timeColor);
        pubColorF.set(c.pubColor);
        swCapOutline.setChecked(c.capOutline);
        capColorF.set(c.capColor);
        setSeg(segMode, c.mode == 1 ? 1 : 0);
        swLive.setChecked(c.liveTime);
        setSeg(segLiveSrc, c.liveSrc == 1 ? 1 : 0);
        etLiveFmt.setText(c.liveFormat);
        liveSizeF.set(c.liveSize);
        liveColorF.set(c.liveColor);
        liveX = c.liveX;
        liveY = c.liveY;
        updateLivePos();
        bgF.set(c.lineBg);
    }

    private DanmakuConfig read() {
        DanmakuConfig c = new DanmakuConfig();
        c.enabled = swEnabled.isChecked();
        c.mode = segIndex(segMode) == 1 ? 1 : 0;
        c.likeBtnToComment = swLikeBtn.isChecked();
        c.dtapToComment = swDtap.isChecked();
        c.timeAlways = swTime.isChecked();
        c.pubAlways = swPub.isChecked();
        c.timeSize = timeSizeF.v;
        c.pubSize = pubSizeF.v;
        c.pubFormat = toTemplate();
        c.timeColor = timeColorF.color;
        c.pubColor = pubColorF.color;
        c.capOutline = swCapOutline.isChecked();
        c.capColor = capColorF.color;
        c.liveTime = swLive.isChecked();
        c.liveSrc = segIndex(segLiveSrc) == 1 ? 1 : 0;
        String lft = etLiveFmt.getText().toString();
        c.liveFormat = lft.trim().isEmpty() ? LiveFormat.DEFAULT : lft;
        c.liveSize = liveSizeF.v;
        c.liveColor = liveColorF.color;
        c.lineBg = bgF.v;
        c.liveX = liveX; // 位置只在抖音里用浮窗调，这里原样带着
        c.liveY = liveY;
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
            showStatus("请先在「高级」里填目标包名", 2);
            return;
        }
        push(list, cfgText, false);
    }

    /** 用 root 把配置推给每个包；launch = true 时全部成功后打开第一个包（抖音），并记下「在等抖音里调好位置」。 */
    private void push(final List<String> list, final String cfgText, final boolean launch) {
        final TextView busyBtn = launch ? btnAdjust : btnPush;
        final String idle = launch ? "去抖音里调整" : "保存并应用";
        setBusy(busyBtn, true, "正在推送…");
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
            final boolean ok = sb.indexOf("✗") < 0;
            if (launch) sb.append("\n进入直播间后，在屏幕上拖动文字，点浮窗里的「完成」；回到这里会自动同步新位置。");
            else sb.append("\n抖音回到前台就生效，不用重启。抖音清除数据/重装后需要重新推送（不推送则用默认：开启+鲜艳）。");
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                setBusy(busyBtn, false, idle);
                showStatus(sb.toString(), ok ? 1 : 2);
                if (launch && ok) {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("adj", true).apply();
                    Intent it = getPackageManager().getLaunchIntentForPackage(list.get(0));
                    if (it != null) {
                        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(it);
                    } else {
                        showStatus(sb + "\n没找到可以打开的 " + list.get(0) + "，请手动打开抖音进直播间。", 2);
                    }
                }
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
            {"pub.text", "发布日期地点：认文案"},
            {"pub.bind", "发布日期地点：视频数据"},
            {"pub.anchor", "视频文案：描边 / 改色"},
            {"pub.row", "发布日期地点 / 视频时长：所在容器"},
            {"cap.draw", "视频文案：绘制时改色"},
            {"live.page", "直播间开播时间：直播间页面"},
            {"live.room", "直播间开播时间：房间数据"},
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

    // ------------------------------------------------------------ 字号 / 颜色（时长、发布信息、文案共用）

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

    /** 预览用的时间：2026-10-19 11:50:00（按手机时区）。 */
    private static final long PREVIEW_SEC;

    static {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(2026, Calendar.OCTOBER, 19, 11, 50, 0);
        PREVIEW_SEC = c.getTimeInMillis() / 1000L;
    }

    /** 字号行：「−  字号 N sp  +」，范围 SIZE_MIN～SIZE_MAX。 */
    private final class SizeField {
        int v;
        TextView label;
        /** 值变了要做的事（比如刷新预览）；可以不设。 */
        Runnable changed;

        SizeField(int def) {
            v = def;
        }

        View build() {
            LinearLayout sz = new LinearLayout(MainActivity.this);
            sz.setOrientation(LinearLayout.HORIZONTAL);
            sz.setGravity(Gravity.CENTER_VERTICAL);
            TextView minus = pillButton("−", false);
            minus.setTextSize(20);
            minus.setOnClickListener(x -> set(v - 1));
            sz.addView(minus, new LinearLayout.LayoutParams(dp(56), dp(48)));
            label = tv("", 16, cText, true);
            label.setGravity(Gravity.CENTER);
            sz.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView plus = pillButton("+", false);
            plus.setTextSize(20);
            plus.setOnClickListener(x -> set(v + 1));
            sz.addView(plus, new LinearLayout.LayoutParams(dp(56), dp(48)));
            show();
            return sz;
        }

        void set(int n) {
            v = Math.max(DanmakuConfig.SIZE_MIN, Math.min(DanmakuConfig.SIZE_MAX, n));
            show();
            refreshSummaries();
            if (changed != null) changed.run();
        }

        void show() {
            if (label != null) label.setText("字号 " + v + " sp");
        }
    }

    /** 百分比行：「−  不透明度 N%  +」，每次 5%，0～100。 */
    private final class PctField {
        int v;
        TextView label;

        PctField(int def) {
            v = def;
        }

        View build() {
            LinearLayout r = new LinearLayout(MainActivity.this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            TextView minus = pillButton("−", false);
            minus.setTextSize(20);
            minus.setOnClickListener(x -> set(v - 5));
            r.addView(minus, new LinearLayout.LayoutParams(dp(56), dp(48)));
            label = tv("", 16, cText, true);
            label.setGravity(Gravity.CENTER);
            r.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView plus = pillButton("+", false);
            plus.setTextSize(20);
            plus.setOnClickListener(x -> set(v + 5));
            r.addView(plus, new LinearLayout.LayoutParams(dp(56), dp(48)));
            show();
            return r;
        }

        void set(int n) {
            v = Math.max(0, Math.min(100, n));
            show();
            refreshSummaries();
        }

        void show() {
            if (label != null) label.setText("不透明度 " + v + "%");
        }
    }

    /** 颜色行：色块 + 当前颜色名 + 「选色」按钮。color = 0 表示默认。 */
    private final class ColorField {
        int color;
        Runnable changed; // 颜色变了要做的事（比如刷新预览）；可以不设
        final String defName, dialogTitle;
        View swatch;
        TextView label;

        ColorField(String defName, String dialogTitle) {
            this.defName = defName;
            this.dialogTitle = dialogTitle;
        }

        View build() {
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            swatch = new View(MainActivity.this);
            row.addView(swatch, new LinearLayout.LayoutParams(dp(28), dp(28)));
            label = tv("", 14, cSub, false);
            label.setPadding(dp(12), 0, 0, 0);
            row.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView pick = pillButton("选色", true);
            pick.setTextSize(16);
            pick.setOnClickListener(x -> showPalette(this));
            row.addView(pick, new LinearLayout.LayoutParams(dp(88), dp(48)));
            show();
            return row;
        }

        void set(int c) {
            color = c;
            show();
            refreshSummaries();
            if (changed != null) changed.run();
        }

        void show() {
            if (swatch == null || label == null) return;
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setStroke(dp(1), cStroke);
            if (color == 0) {
                g.setColor(0x00000000);
                label.setText(defName);
            } else {
                g.setColor(color);
                String name = "";
                for (int i = 0; i < PAL.length; i++) if ((PAL[i] | 0xFF000000) == color) name = PAL_NAMES[i] + "  ";
                label.setText("当前：" + name + String.format("#%06X", color & 0xFFFFFF));
            }
            swatch.setBackground(g);
        }
    }

    /** 弹出色表：点一个颜色就选中并关掉；「默认」恢复成默认；也可以输入 6 位色值（如 FF8800）。 */
    private void showPalette(final ColorField f) {
        final AlertDialog[] dlg = new AlertDialog[1];
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(8), dp(16), dp(4));
        TextView none = pillButton((f.color == 0 ? "✓ " : "") + f.defName, false);
        none.setTextSize(15);
        none.setOnClickListener(v -> {
            f.set(0);
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
                if (rgb == f.color) {
                    sw.setText("✓");
                    sw.setTextColor(luminance(rgb) > 140 ? 0xFF000000 : 0xFFFFFFFF);
                }
                sw.setOnClickListener(v -> {
                    f.set(rgb);
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
        LinearLayout hex = new LinearLayout(this);
        hex.setOrientation(LinearLayout.HORIZONTAL);
        hex.setGravity(Gravity.CENTER_VERTICAL);
        final EditText et = field();
        et.setHint("自定义色值，如 FF8800");
        hex.addView(et, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView use = pillButton("用这个", true);
        use.setTextSize(15);
        use.setOnClickListener(v -> {
            int rgb = parseHex(et.getText().toString());
            if (rgb < 0) {
                et.setError("要 6 位 16 进制数，如 FF8800");
                return;
            }
            f.set(rgb | 0xFF000000);
            dlg[0].dismiss();
        });
        LinearLayout.LayoutParams ul = new LinearLayout.LayoutParams(dp(92), dp(48));
        ul.leftMargin = dp(8);
        hex.addView(use, ul);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, -2);
        hl.topMargin = dp(4);
        col.addView(hex, hl);
        ScrollView sv = new ScrollView(this);
        sv.addView(col, new ViewGroup.LayoutParams(-1, -2));
        dlg[0] = new AlertDialog.Builder(this).setTitle(f.dialogTitle).setView(sv).setNegativeButton("取消", null).create();
        dlg[0].show();
    }

    /** 6 位 16 进制（可以带 #）→ 0～0xFFFFFF；不是返回 -1。 */
    private static int parseHex(String s) {
        String t = s == null ? "" : s.trim();
        if (t.startsWith("#")) t = t.substring(1);
        if (t.length() != 6) return -1;
        try {
            return Integer.parseInt(t, 16);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int luminance(int rgb) {
        return (((rgb >> 16) & 0xFF) * 299 + ((rgb >> 8) & 0xFF) * 587 + (rgb & 0xFF) * 114) / 1000;
    }

    // ------------------------------------------------------------ 发布信息的积木列表

    /** 一项：tok 是「日期 / 时间 / 地点 / 省 / 市 / 换行」之一；tok 为空就是用户自己写的文字 text。 */
    private static final class Block {
        final String tok;
        String text;

        Block(String tok, String text) {
            this.tok = tok;
            this.text = text == null ? "" : text;
        }
    }

    private static boolean isDataKey(String k) {
        return k.equals("日期") || k.equals("时间") || k.equals("地点") || k.equals("省") || k.equals("市");
    }

    private static boolean isData(Block b) {
        return b.tok != null && !b.tok.equals("换行");
    }

    /** 积木 → 配置里存的模板：数据项之间自动空一格，文字原样，换行写成 \n。没有内容就用默认模板。 */
    private String toTemplate() {
        StringBuilder sb = new StringBuilder();
        boolean prevData = false;
        for (Block b : blocks) {
            if (b.tok == null) {
                sb.append(b.text);
                prevData = false;
            } else if (b.tok.equals("换行")) {
                sb.append("\\n");
                prevData = false;
            } else {
                if (prevData) sb.append(' ');
                sb.append('{').append(b.tok).append('}');
                prevData = true;
            }
        }
        String t = sb.toString();
        return t.trim().isEmpty() ? PubFormat.DEFAULT : t;
    }

    /** 配置里存的模板 → 积木（两个数据项之间只有空白的文字，是自动加的空格，不显示成一项）。 */
    private void parseTemplate(String tpl) {
        blocks.clear();
        String t = tpl == null || tpl.trim().isEmpty() ? PubFormat.DEFAULT : tpl;
        StringBuilder lit = new StringBuilder();
        int n = t.length();
        for (int i = 0; i < n; ) {
            char ch = t.charAt(i);
            if (ch == '{') {
                int e = t.indexOf('}', i + 1);
                if (e > i) {
                    String key = t.substring(i + 1, e);
                    if (isDataKey(key)) {
                        flushLit(lit);
                        blocks.add(new Block(key, null));
                        i = e + 1;
                        continue;
                    }
                }
            } else if (ch == '\\' && i + 1 < n && t.charAt(i + 1) == 'n') {
                flushLit(lit);
                blocks.add(new Block("换行", null));
                i += 2;
                continue;
            }
            lit.append(ch);
            i++;
        }
        flushLit(lit);
        for (int i = blocks.size() - 2; i >= 1; i--) {
            Block b = blocks.get(i);
            if (b.tok == null && b.text.trim().isEmpty() && isData(blocks.get(i - 1)) && isData(blocks.get(i + 1))) blocks.remove(i);
        }
    }

    private void flushLit(StringBuilder lit) {
        if (lit.length() > 0) {
            blocks.add(new Block(null, lit.toString()));
            lit.setLength(0);
        }
    }

    /** 「+ 日期」这样的添加按钮：先执行 action（改 blocks），再重画列表和预览。 */
    private TextView addButton(String label, final Runnable action) {
        TextView b = pillButton(label, false);
        b.setTextSize(14);
        b.setOnClickListener(x -> {
            action.run();
            renderBlocks();
        });
        return b;
    }

    private TextView smallButton(String s, View.OnClickListener l) {
        TextView b = pillButton(s, false);
        b.setTextSize(16);
        b.setOnClickListener(l);
        return b;
    }

    private void renderBlocks() {
        if (blockList == null) return;
        blockList.removeAllViews();
        for (int i = 0; i < blocks.size(); i++) {
            final int idx = i;
            final Block b = blocks.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            if (b.tok == null) {
                EditText et = field();
                et.setHint("输入文字，如 发布时间：");
                et.setText(b.text); // 先设文字再挂监听，免得触发一次
                et.addTextChangedListener(new TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int st, int c, int a) {
                    }

                    @Override
                    public void onTextChanged(CharSequence s, int st, int bf, int c) {
                    }

                    @Override
                    public void afterTextChanged(Editable e) {
                        b.text = e.toString();
                        updatePubPreview();
                    }
                });
                row.addView(et, new LinearLayout.LayoutParams(0, -2, 1f));
            } else {
                TextView name = tv(b.tok.equals("换行") ? "↵ 换行" : b.tok, 16, cText, true);
                name.setPadding(dp(16), dp(12), dp(16), dp(12));
                name.setBackground(round(cPrimarySoft, 0, 14));
                row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
            }
            row.addView(smallButton("↑", x -> moveBlock(idx, -1)), smallLp());
            row.addView(smallButton("↓", x -> moveBlock(idx, 1)), smallLp());
            row.addView(smallButton("✕", x -> removeBlock(idx)), smallLp());
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
            rl.bottomMargin = dp(8);
            blockList.addView(row, rl);
        }
        updatePubPreview();
    }

    private LinearLayout.LayoutParams smallLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
        lp.leftMargin = dp(6);
        return lp;
    }

    private void moveBlock(int idx, int d) {
        int j = idx + d;
        if (j < 0 || j >= blocks.size()) return;
        java.util.Collections.swap(blocks, idx, j);
        renderBlocks();
    }

    private void removeBlock(int idx) {
        if (idx < 0 || idx >= blocks.size()) return;
        blocks.remove(idx);
        renderBlocks();
    }

    private void updatePubPreview() {
        refreshSummaries(); // 预览在「显示内容和顺序」分组的摘要里
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
        lp.bottomMargin = dp(8);
        return lp;
    }

    private TextView groupLabel(String s) {
        TextView t = tv(s, 13, cSub, true);
        t.setPadding(dp(6), dp(18), 0, dp(8));
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
        styleSwitch(sw);
        row.addView(sw);
        row.setOnClickListener(v -> sw.toggle());
        return row;
    }

    /** 开关配色，并且开关一变就刷新分组摘要。 */
    private void styleSwitch(Switch sw) {
        int[][] st = {{android.R.attr.state_checked}, {}};
        sw.setThumbTintList(new ColorStateList(st, new int[]{cPrimary, dark ? 0xFF8A90A0 : 0xFFFFFFFF}));
        sw.setTrackTintList(new ColorStateList(st, new int[]{dark ? 0xFF35417A : 0xFFC5CFFA, dark ? 0xFF3A3F4D : 0xFFD5D8E2}));
        sw.setOnCheckedChangeListener((b, on) -> refreshSummaries());
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
            t.setOnClickListener(v -> {
                setSeg(seg, idx);
                refreshSummaries();
            });
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
