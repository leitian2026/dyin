package com.danmaku.randcolor;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {
    static final String PREFS = "ui";
    static final String DEFAULT_PKG = "com.ss.android.ugc.aweme";

    private EditText etPkg;
    private CheckBox cbEnabled;
    private RadioGroup rgMode, rgKey;
    private RadioButton rbVivid, rbAny, rbObj, rbText;
    private TextView tvStatus;
    private Button btnPush, btnCheck, btnTabs;
    private LinearLayout tabBox;
    private final ArrayList<CheckBox> tabChecks = new ArrayList<>();
    /** 抖音首页顶部标签的默认列表（来自实测，从左到右）；点「读取标签」后以抖音里实际的为准。 */
    private static final String DEFAULT_TABS = "热点\n经验\n游戏\n少儿\n同城\n精选\n团购\n免费看剧\n商城\n短剧\n直播\n关注\n推荐";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("弹幕随机色 / 首页标签");
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        sv.addView(root, new ViewGroup.LayoutParams(-1, -2));
        setContentView(sv);

        root.addView(text("使用步骤：\n"
                + "1. LSPosed 里启用本模块，作用域勾选抖音，然后强杀抖音重新打开。\n"
                + "2. 下面选好样式，点「保存并推送配置」（需要 root 授权）。不推送也行：默认就是开启 + 鲜艳随机。\n"
                + "3. 去看有弹幕的视频 / 发一条弹幕。\n"
                + "4. 没效果就点「检查状态」，按提示排查。", 13));

        root.addView(label("目标 App 包名"));
        etPkg = new EditText(this);
        etPkg.setSingleLine(true);
        etPkg.setTextSize(14);
        etPkg.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        root.addView(etPkg);

        cbEnabled = new CheckBox(this);
        cbEnabled.setText("启用随机弹幕颜色");
        cbEnabled.setTextSize(15);
        root.addView(cbEnabled);

        root.addView(label("颜色风格"));
        rgMode = new RadioGroup(this);
        rbVivid = radio("鲜艳（高饱和、亮色，看得清）");
        rbAny = radio("任意（含浅色、粉彩，更杂）");
        rgMode.addView(rbVivid);
        rgMode.addView(rbAny);
        root.addView(rgMode);

        root.addView(label("同一条弹幕如何固定颜色（弹幕滚动每帧都在重绘，必须固定，否则会闪）"));
        rgKey = new RadioGroup(this);
        rbObj = radio("按弹幕对象（每条都不同，推荐）");
        rbText = radio("按文字内容（相同文字同色；如果上面那种会闪就选这个）");
        rgKey.addView(rbObj);
        rgKey.addView(rbText);
        root.addView(rgKey);

        root.addView(label("首页顶部标签（勾选 = 隐藏）"));
        root.addView(text("只隐藏标签栏上的这一项，首页左右滑动仍然会切到对应页面。勾选后点下面的「保存并推送配置」，抖音回到前台生效。", 12));
        tabBox = new LinearLayout(this);
        tabBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(tabBox);
        btnTabs = new Button(this);
        btnTabs.setText("读取抖音当前的标签（root）");
        btnTabs.setOnClickListener(v -> readTabs());
        root.addView(btnTabs);

        btnPush = new Button(this);
        btnPush.setText("保存并推送配置");
        btnPush.setOnClickListener(v -> save());
        root.addView(btnPush);

        btnCheck = new Button(this);
        btnCheck.setText("检查状态");
        btnCheck.setOnClickListener(v -> check());
        root.addView(btnCheck);

        tvStatus = text("", 13);
        tvStatus.setTypeface(Typeface.MONOSPACE);
        tvStatus.setTextIsSelectable(true);
        root.addView(tvStatus);

        load();
    }

    private void load() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        etPkg.setText(sp.getString("pkg", DEFAULT_PKG));
        DanmakuConfig c = DanmakuConfig.fromText(sp.getString("cfg", ""));
        cbEnabled.setChecked(c.enabled);
        (c.mode == 1 ? rbAny : rbVivid).setChecked(true);
        (c.keyByText ? rbText : rbObj).setChecked(true);
        buildTabs(DanmakuConfig.split(sp.getString("tabs", DEFAULT_TABS)), c.hideTabs);
    }

    /** 每行 3 个复选框。labels = 全部标签（屏幕顺序），hide = 当前勾选（隐藏）的。 */
    private void buildTabs(List<String> labels, Set<String> hide) {
        tabBox.removeAllViews();
        tabChecks.clear();
        LinearLayout row = null;
        for (int i = 0; i < labels.size(); i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                tabBox.addView(row);
            }
            CheckBox cb = new CheckBox(this);
            cb.setText(labels.get(i));
            cb.setTextSize(14);
            cb.setChecked(hide.contains(labels.get(i)));
            row.addView(cb, new LinearLayout.LayoutParams(0, -2, 1f));
            tabChecks.add(cb);
        }
    }

    private List<String> tabLabels() {
        ArrayList<String> l = new ArrayList<>();
        for (CheckBox cb : tabChecks) l.add(cb.getText().toString());
        return l;
    }

    private void readTabs() {
        final List<String> list = DanmakuConfig.split(etPkg.getText().toString());
        if (list.isEmpty() || !RootShell.validPkg(list.get(0))) {
            tvStatus.setText("请先填目标包名");
            return;
        }
        final String pkg = list.get(0);
        btnTabs.setEnabled(false);
        tvStatus.setText("正在读取标签…");
        new Thread(() -> {
            final RootShell.Result r = RootShell.readTabs(pkg);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                btnTabs.setEnabled(true);
                if (r.out.contains("__NOPKG__")) {
                    tvStatus.setText(RootShell.nopkgMsg(r.out));
                    return;
                }
                List<String> found = DanmakuConfig.split(r.out);
                if (found.isEmpty()) {
                    tvStatus.setText("还没读到标签：确认模块已启用，强杀抖音重开，停在首页等顶部标签栏出现后再点。");
                    return;
                }
                // 以抖音里的实际顺序为准；已勾选但这次没看到的（比如已经被隐藏）也保留，免得没法取消
                Set<String> checked = new HashSet<>();
                for (CheckBox cb : tabChecks) if (cb.isChecked()) checked.add(cb.getText().toString());
                ArrayList<String> all = new ArrayList<>(found);
                for (String s : tabLabels()) if (checked.contains(s) && !all.contains(s)) all.add(s);
                buildTabs(all, checked);
                tvStatus.setText("已读到 " + found.size() + " 个标签，勾选要隐藏的，再点「保存并推送配置」。");
            });
        }).start();
    }

    private DanmakuConfig read() {
        DanmakuConfig c = new DanmakuConfig();
        c.enabled = cbEnabled.isChecked();
        c.mode = rbAny.isChecked() ? 1 : 0;
        c.keyByText = rbText.isChecked();
        for (CheckBox cb : tabChecks) if (cb.isChecked()) c.hideTabs.add(cb.getText().toString());
        return c;
    }

    private void save() {
        final String cfgText = read().toText();
        final String pkgs = etPkg.getText().toString().trim();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("cfg", cfgText).putString("pkg", pkgs)
                .putString("tabs", String.join("\n", tabLabels())).apply();
        final List<String> list = DanmakuConfig.split(pkgs);
        if (list.isEmpty()) {
            tvStatus.setText("请先填目标包名");
            return;
        }
        btnPush.setEnabled(false);
        tvStatus.setText("正在推送…（首次会弹出 root 授权，请允许）");
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
                btnPush.setEnabled(true);
                tvStatus.setText(sb.toString());
            });
        }).start();
    }

    private void check() {
        final List<String> list = DanmakuConfig.split(etPkg.getText().toString());
        if (list.isEmpty()) {
            tvStatus.setText("请先填目标包名");
            return;
        }
        btnCheck.setEnabled(false);
        tvStatus.setText("正在读取状态…");
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
                btnCheck.setEnabled(true);
                tvStatus.setText(sb.toString());
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
        String[] lines = out.trim().split("\n");
        int from = Math.max(0, lines.length - 12);
        sb.append("\n最近状态：\n");
        for (int i = from; i < lines.length; i++) sb.append(lines[i]).append('\n');
        return sb.toString();
    }

    // ---------- 小工具 ----------
    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView text(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setPadding(0, dp(4), 0, dp(8));
        return t;
    }

    private TextView label(String s) {
        TextView t = text(s, 13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(12), 0, dp(2));
        return t;
    }

    private RadioButton radio(String s) {
        RadioButton r = new RadioButton(this);
        r.setText(s);
        r.setTextSize(14);
        r.setId(View.generateViewId());
        return r;
    }
}
