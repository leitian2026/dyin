package com.danmaku.randcolor;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.lang.ref.WeakReference;

/**
 * 位置浮窗：画在抖音当前界面的最上层，用来调时长文字（或发布日期地点文字）的位置和字号，改动实时可见。同一个浮窗，
 * 调哪一个由 target 决定（TIME / PUB）。
 *  - 三条滑动条（水平 / 垂直 / 字号），左端是减、右端是加。位置：范围是半个屏幕，0 在正中（发布信息有文案锚点时 0 = 文案左下角）；
 *    字号：8～40 sp。
 *  - 两端的 − / + 每点一下：位置移动 2 像素，字号增减 1 号（1 sp）。「归零」只归位置，不动字号。
 *  - 标题栏可以拖动，浮窗挡住文字时把它拖开。
 *  - 松手（滑动条 / 按钮）时写回配置文件；点「完成」写回并关闭。
 * 由模块设置页的「打开浮窗调整位置」触发（推送 timeAdjust=true / pubAdjust=true 后启动抖音）。
 */
final class TimePanel {
    private TimePanel() {}

    private static final int STEP_PX = 2;

    /** 浮窗在调哪一个文字。 */
    static final int TIME = 0, PUB = 1;
    private static int target = TIME;

    private static WeakReference<View> panelRef = new WeakReference<>(null);
    private static boolean open;
    private static float dragX, dragY; // 浮窗自己被拖到的位置，换界面重建时保留
    private static Axis axX, axY, axS;

    static boolean isOpen() {
        return open;
    }

    static int target() {
        return target;
    }

    static View view() {
        return panelRef.get();
    }

    /** 换界面时浮窗跟过去：调的还是原来那个文字。 */
    static void open(Activity a) {
        open(a, target);
    }

    /** 打开；已经开在这个界面就只是提到最前；开在别的界面就搬过来；换了要调的文字就先关掉旧的再开。 */
    static void open(Activity a, int t) {
        if (a == null || a.isFinishing() || a.isDestroyed()) return;
        if (open && t != target) close(); // 旧的先保存、撤掉它的示例文字
        target = t;
        ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
        View old = panelRef.get();
        open = true;
        if (target == PUB) PubHook.showSample(a); // 先放文字，再放浮窗，浮窗才在文字上面
        else TimeHook.showSample(a);
        if (old != null && old.getParent() == decor) {
            old.bringToFront();
            return;
        }
        if (old != null && old.getParent() instanceof ViewGroup) ((ViewGroup) old.getParent()).removeView(old);
        DisplayMetrics dm = a.getResources().getDisplayMetrics();
        View p = build(a, dm);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                Math.min(dp(a, 340), dm.widthPixels - dp(a, 24)), -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = dp(a, 96);
        decor.addView(p, lp);
        p.setTranslationX(dragX);
        p.setTranslationY(dragY);
        panelRef = new WeakReference<>(p);
    }

    static void close() {
        DanmakuHook.saveCfg();
        open = false;
        View p = panelRef.get();
        if (p != null && p.getParent() instanceof ViewGroup) ((ViewGroup) p.getParent()).removeView(p);
        panelRef = new WeakReference<>(null);
        axX = axY = axS = null;
        if (target == PUB) PubHook.onPanelClosed();
        else TimeHook.onPanelClosed();
    }

    // ------------------------------------------------------------ 界面

    private static View build(final Activity a, DisplayMetrics dm) {
        final LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(a, 14), dp(a, 8), dp(a, 14), dp(a, 12));
        box.setBackground(round(0xEB1E2230, dp(a, 18)));
        box.setElevation(dp(a, 10));
        box.setClickable(true); // 吃掉触摸，不让手势漏到下面的视频

        // 标题栏：拖这里移动浮窗
        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = txt(a, target == PUB ? "⠿ 发布信息位置（拖这里移动浮窗）" : "⠿ 时长位置（拖这里移动浮窗）", 13, 0xCCFFFFFF, false);
        title.setPadding(0, dp(a, 10), 0, dp(a, 10));
        final float[] st = new float[4];
        title.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    st[0] = e.getRawX();
                    st[1] = e.getRawY();
                    st[2] = box.getTranslationX();
                    st[3] = box.getTranslationY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    box.setTranslationX(st[2] + e.getRawX() - st[0]);
                    box.setTranslationY(st[3] + e.getRawY() - st[1]);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    dragX = box.getTranslationX();
                    dragY = box.getTranslationY();
                    return true;
                default:
                    return true;
            }
        });
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView reset = chip(a, "归零");
        reset.setOnClickListener(v -> {
            if (axX != null) axX.set(0, false);
            if (axY != null) axY.set(0, false);
        });
        head.addView(reset);
        TextView done = chip(a, "完成");
        done.setOnClickListener(v -> close());
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-2, -2);
        dl.leftMargin = dp(a, 8);
        head.addView(done, dl);
        box.addView(head);

        axX = new Axis(true, dm.widthPixels / 2);
        axY = new Axis(false, dm.heightPixels / 2);
        box.addView(row(a, "水平（左 − ／ 右 +）", axX));
        box.addView(row(a, "垂直（上 − ／ 下 +）", axY));
        axS = Axis.size();
        box.addView(row(a, "字号（小 − ／ 大 +，每点一下 1 号）", axS));
        return box;
    }

    private static View row(Activity a, String label, Axis ax) {
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, dp(a, 8), 0, 0);

        LinearLayout top = new LinearLayout(a);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.addView(txt(a, label, 13, 0xFFFFFFFF, true), new LinearLayout.LayoutParams(0, -2, 1f));
        ax.val = txt(a, "", 13, 0xFF8FA4FF, true);
        top.addView(ax.val);
        col.addView(top);

        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(stepBtn(a, "−", ax, -1), new LinearLayout.LayoutParams(dp(a, 44), dp(a, 40)));

        SeekBar sb = new SeekBar(a);
        sb.setMax(ax.hi() - ax.lo());
        sb.setProgress(Math.max(0, Math.min(ax.hi() - ax.lo(), ax.get() - ax.lo())));
        try {
            sb.setThumbTintList(ColorStateList.valueOf(0xFF8FA4FF));
            sb.setProgressTintList(ColorStateList.valueOf(0xFF6C85F5));
            sb.setProgressBackgroundTintList(ColorStateList.valueOf(0x55FFFFFF));
        } catch (Throwable ignored) {
        }
        final Axis fax = ax;
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) fax.set(p + fax.lo(), true);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                DanmakuHook.saveCfg(); // 松手才落盘
            }
        });
        ax.sb = sb;
        bar.addView(sb, new LinearLayout.LayoutParams(0, dp(a, 40), 1f));
        bar.addView(stepBtn(a, "+", ax, 1), new LinearLayout.LayoutParams(dp(a, 44), dp(a, 40)));
        col.addView(bar);
        ax.refresh();
        return col;
    }

    /** − / + 按钮：每点一下走一步（位置 2 像素 / 字号 1 号），松手落盘。没有定时器 / 长按连发，大幅度的变化用滑动条。 */
    private static TextView stepBtn(Activity a, String label, final Axis ax, final int dir) {
        TextView t = txt(a, label, 22, 0xFFFFFFFF, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(round(0x33FFFFFF, dp(a, 12)));
        t.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                    v.setPressed(true);
                    ax.set(ax.get() + dir * ax.step(), false);
                    return true;
                case MotionEvent.ACTION_UP:
                    DanmakuHook.saveCfg();
                    v.setPressed(false);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    return true;
                default:
                    return true;
            }
        });
        return t;
    }

    /** 一条滑动条的数值：水平 / 垂直位置（像素，0 在正中）或字号（sp）；读写配置、同步滑动条和数字、实时生效（落盘由调用方在松手时做）。 */
    private static final class Axis {
        final boolean horiz;
        final boolean size;
        final int range;
        SeekBar sb;
        TextView val;

        Axis(boolean horiz, int range) {
            this.horiz = horiz;
            this.size = false;
            this.range = Math.max(1, range);
        }

        private Axis() {
            this.horiz = false;
            this.size = true;
            this.range = 1;
        }

        static Axis size() {
            return new Axis();
        }

        int lo() {
            return size ? DanmakuConfig.SIZE_MIN : -range;
        }

        int hi() {
            return size ? DanmakuConfig.SIZE_MAX : range;
        }

        int step() {
            return size ? 1 : STEP_PX;
        }

        int get() {
            DanmakuConfig c = DanmakuHook.cfg;
            if (size) return target == PUB ? c.pubSize : c.timeSize;
            if (target == PUB) return horiz ? c.pubX : c.pubY;
            return horiz ? c.timeX : c.timeY;
        }

        void set(int v, boolean fromSlider) {
            v = Math.max(lo(), Math.min(hi(), v));
            DanmakuConfig c = DanmakuHook.cfg;
            if (size) {
                if (target == PUB) c.pubSize = v;
                else c.timeSize = v;
            } else if (target == PUB) {
                if (horiz) c.pubX = v;
                else c.pubY = v;
            } else {
                if (horiz) c.timeX = v;
                else c.timeY = v;
            }
            if (!fromSlider && sb != null) sb.setProgress(v - lo()); // fromUser=false，不会回调到 set
            refresh();
            if (size) {
                if (target == PUB) PubHook.applySizeNow();
                else TimeHook.applySizeNow();
            } else if (target == PUB) {
                PubHook.applyOffsetNow();
            } else {
                TimeHook.applyOffsetNow();
            }
        }

        void refresh() {
            int v = get();
            if (val == null) return;
            if (size) val.setText(v + " sp");
            else val.setText((v > 0 ? "+" : "") + v + " px");
        }
    }

    // ------------------------------------------------------------ 小工具

    private static int dp(Activity a, int v) {
        return (int) (v * a.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static GradientDrawable round(int fill, int radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radiusPx);
        return g;
    }

    private static TextView txt(Activity a, String s, int sp, int color, boolean bold) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    private static TextView chip(Activity a, String s) {
        TextView t = txt(a, s, 13, 0xFFFFFFFF, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(a, 14), dp(a, 8), dp(a, 14), dp(a, 8));
        t.setBackground(round(0x33FFFFFF, dp(a, 14)));
        return t;
    }
}
