package com.danmaku.randcolor;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;

/**
 * 直播间开播时间的「位置浮窗」：设置页不再调位置，改成在抖音的直播间里直接拖。
 *
 * 流程：设置页点「去抖音里调整」→ 把 liveAdjust = true 推给抖音并打开抖音 → 进直播间后，LiveHook 发现 liveAdjust，
 * 把当前在屏幕上的直播间页面交给 {@link #sync}，这里在它所在窗口的最上层盖一层半透明触摸层 + 一个小浮窗：
 *  - 在屏幕任意位置按住拖动：开播时间文字跟着手指走（按页面剩余空间的百分比换算，和平时放文字用的是同一个公式）；
 *  - 浮窗里 ◀ ▶ ▲ ▼ 微调 1%，「默认」回到 3% / 10%，「取消」回到打开时的位置，「完成」保存；
 *  - 浮窗标题可以拖，挡住文字时把它挪开。
 * 完成 / 取消都会把 liveAdjust 改回 false 并写回抖音自己的配置文件，设置页回到前台时用 root 读回新位置。
 *
 * 不是钩子：不挂任何方法，只在 LiveHook 的刷新（主线程）里被调到；没有定时器、没有轮询。触摸层只在调节模式里存在，
 * 平时一个字节都不占。全部用代码画（抖音进程里读不到模块的资源）。
 */
final class LivePosPanel {
    private LivePosPanel() {}

    private static WeakReference<FrameLayout> layerRef;
    private static WeakReference<ViewGroup> pageRef;
    private static TextView tvPos;
    /** 这次调节开始时的位置（「取消」回到这里）；opened = 已经记过。 */
    private static int origX, origY;
    private static boolean opened;
    /** 一次拖动的起点：手指的屏幕坐标、文字当时的百分比。 */
    private static float downX, downY, startX, startY;
    /** 拖浮窗标题时：手指起点和浮窗当时的位移。 */
    private static float pdX, pdY, ptX, ptY;

    private static final int DARK = 0xF0171A22, BTN = 0xFF2B303C, PRIMARY = 0xFF3B5BDB;

    // ------------------------------------------------------------ 进出

    /** root = 现在在屏幕上的直播间页面根控件；浮窗已经在它的窗口里就只刷新读数。主线程。 */
    static void sync(ViewGroup root) {
        if (root == null) {
            detach();
            return;
        }
        pageRef = new WeakReference<>(root);
        View top = root.getRootView();
        if (!(top instanceof FrameLayout)) return; // 普通 Activity 的窗口根是 FrameLayout；不是就不显示
        FrameLayout decor = (FrameLayout) top;
        FrameLayout layer = layerRef == null ? null : layerRef.get();
        if (layer != null && layer.getParent() == decor) {
            showPos();
            return;
        }
        detach();
        if (!opened) {
            origX = DanmakuHook.cfg.liveX;
            origY = DanmakuHook.cfg.liveY;
            opened = true;
        }
        layer = build(decor.getContext());
        decor.addView(layer, new FrameLayout.LayoutParams(-1, -1));
        layerRef = new WeakReference<>(layer);
        showPos();
    }

    /** 调节结束（或模式被关掉）：撤掉浮窗，下次重新记原位置。 */
    static void end() {
        if (layerRef == null && !opened) return;
        detach();
        opened = false;
    }

    private static void detach() {
        FrameLayout layer = layerRef == null ? null : layerRef.get();
        layerRef = null;
        if (layer != null && layer.getParent() instanceof ViewGroup) ((ViewGroup) layer.getParent()).removeView(layer);
    }

    // ------------------------------------------------------------ 动作

    private static void finish(boolean keep) {
        DanmakuConfig c = DanmakuHook.cfg;
        if (!keep) {
            c.liveX = origX;
            c.liveY = origY;
        }
        c.liveAdjust = false;
        DanmakuHook.saveCfg();
        end();
        LiveHook.requestRefresh();
    }

    private static void nudge(int dx, int dy) {
        DanmakuConfig c = DanmakuHook.cfg;
        c.liveX = clamp(c.liveX + dx);
        c.liveY = clamp(c.liveY + dy);
        LiveHook.placeAll();
        showPos();
    }

    private static void reset() {
        DanmakuConfig c = DanmakuHook.cfg;
        c.liveX = 3;
        c.liveY = 10;
        LiveHook.placeAll();
        showPos();
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(100, v));
    }

    private static void showPos() {
        TextView t = tvPos;
        if (t == null) return;
        DanmakuConfig c = DanmakuHook.cfg;
        String s = "水平 " + c.liveX + "%   垂直 " + c.liveY + "%";
        if (!s.contentEquals(t.getText())) t.setText(s);
    }

    // ------------------------------------------------------------ 触摸

    /** 触摸层：全部吃掉（不让手势穿到直播间），拖动 = 移动文字。 */
    private static final View.OnTouchListener DRAG = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent e) {
            try {
                ViewGroup root = pageRef == null ? null : pageRef.get();
                if (root == null) return true;
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) {
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startX = DanmakuHook.cfg.liveX;
                    startY = DanmakuHook.cfg.liveY;
                } else if (a == MotionEvent.ACTION_MOVE) {
                    LiveHook.moveTo(root, startX, startY, e.getRawX() - downX, e.getRawY() - downY);
                    showPos();
                }
            } catch (Throwable ignored) {
            }
            return true;
        }
    };

    // ------------------------------------------------------------ 界面

    private static FrameLayout build(Context ctx) {
        final FrameLayout layer = new FrameLayout(ctx);
        layer.setBackgroundColor(0x2E000000);
        layer.setClickable(true);
        layer.setOnTouchListener(DRAG);

        final LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 12));
        panel.setBackground(round(ctx, DARK, 18));
        panel.setClickable(true); // 浮窗自己吃触摸，不传给下面的触摸层

        TextView head = label(ctx, "开播时间位置  ·  拖这里挪浮窗", 12, 0xFF9AA1B2, false);
        head.setPadding(0, 0, 0, dp(ctx, 6));
        head.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                try {
                    int a = e.getActionMasked();
                    if (a == MotionEvent.ACTION_DOWN) {
                        pdX = e.getRawX();
                        pdY = e.getRawY();
                        ptX = panel.getTranslationX();
                        ptY = panel.getTranslationY();
                    } else if (a == MotionEvent.ACTION_MOVE) {
                        panel.setTranslationX(ptX + e.getRawX() - pdX);
                        panel.setTranslationY(ptY + e.getRawY() - pdY);
                    }
                } catch (Throwable ignored) {
                }
                return true;
            }
        });
        panel.addView(head);

        TextView pos = label(ctx, "", 17, 0xFFFFFFFF, true);
        tvPos = pos;
        panel.addView(pos);
        TextView hint = label(ctx, "在屏幕上任意位置拖动，文字跟着手指走", 12, 0xFF9AA1B2, false);
        hint.setPadding(0, dp(ctx, 2), 0, dp(ctx, 10));
        panel.addView(hint);

        LinearLayout nudge = new LinearLayout(ctx);
        nudge.setOrientation(LinearLayout.HORIZONTAL);
        nudge.addView(button(ctx, "◀", BTN, new Runnable() {
            public void run() {
                nudge(-1, 0);
            }
        }), btnLp(ctx, false));
        nudge.addView(button(ctx, "▶", BTN, new Runnable() {
            public void run() {
                nudge(1, 0);
            }
        }), btnLp(ctx, true));
        nudge.addView(button(ctx, "▲", BTN, new Runnable() {
            public void run() {
                nudge(0, -1);
            }
        }), btnLp(ctx, true));
        nudge.addView(button(ctx, "▼", BTN, new Runnable() {
            public void run() {
                nudge(0, 1);
            }
        }), btnLp(ctx, true));
        panel.addView(nudge, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout act = new LinearLayout(ctx);
        act.setOrientation(LinearLayout.HORIZONTAL);
        act.addView(button(ctx, "默认", BTN, new Runnable() {
            public void run() {
                reset();
            }
        }), btnLp(ctx, false));
        act.addView(button(ctx, "取消", BTN, new Runnable() {
            public void run() {
                finish(false);
            }
        }), btnLp(ctx, true));
        act.addView(button(ctx, "完成", PRIMARY, new Runnable() {
            public void run() {
                finish(true);
            }
        }), btnLp(ctx, true));
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-1, -2);
        al.topMargin = dp(ctx, 8);
        panel.addView(act, al);

        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(dp(ctx, 264), -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        pl.bottomMargin = dp(ctx, 120);
        layer.addView(panel, pl);
        return layer;
    }

    private static TextView label(Context ctx, String s, int sp, int color, boolean bold) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    private static TextView button(Context ctx, String s, int bg, final Runnable r) {
        TextView t = label(ctx, s, 15, 0xFFFFFFFF, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(round(ctx, bg, 12));
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    r.run();
                } catch (Throwable ignored) {
                }
            }
        });
        return t;
    }

    private static LinearLayout.LayoutParams btnLp(Context ctx, boolean gap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(ctx, 42), 1f);
        if (gap) lp.leftMargin = dp(ctx, 6);
        return lp;
    }

    private static GradientDrawable round(Context ctx, int fill, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(ctx, radiusDp));
        return g;
    }

    private static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
