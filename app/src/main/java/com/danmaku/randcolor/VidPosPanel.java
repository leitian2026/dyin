package com.danmaku.randcolor;

import android.app.Activity;
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
 * 发布日期地点、视频时长两个浮层的「位置浮窗」：设置页点「去抖音里调整」→ 配置里 vidAdjust = true → 抖音回到前台时
 * PubHook.onResume 把当前 Activity 交给 {@link #show}：在窗口最上层盖一层半透明触摸层 + 一个小浮窗。
 *  - 浮窗里先选「发布地」或「时长」，再在屏幕任意位置按住拖动：所选的浮层的左上角跟着手指走（全屏任意位置，单位 dp，水平竖直都能调）；
 *  - ◀ ▶ ▲ ▼ 微调 1 dp，「归零」回默认位置，「取消」回到打开时的位置，「完成」保存；浮窗标题可以拖。
 * 完成 / 取消把 vidAdjust 改回 false 并写回抖音自己的配置文件；设置页回到前台时用 root 读回新位置。
 * 不是钩子，没有定时器；触摸层只在调节模式里存在。对应的开关（发布日期地点 / 视频时长）要先打开，浮层才看得到。
 */
final class VidPosPanel {
    private VidPosPanel() {}

    private static WeakReference<FrameLayout> layerRef;
    private static TextView tvPos, btnPub, btnTime;
    private static int target; // 0 = 发布地  1 = 时长
    private static int[] o = new int[4]; // 打开时的 pubX, pubY, timeX, timeY（「取消」回到这里）
    private static boolean opened;
    private static float downX, downY, startX, startY;
    private static float pdX, pdY, ptX, ptY;
    private static final int LIM = 4000;
    private static final int DARK = 0xF0171A22, BTN = 0xFF2B303C, PRIMARY = 0xFF3B5BDB;

    // ------------------------------------------------------------ 进出

    static void show(Activity a) {
        if (a == null || a.isFinishing() || a.isDestroyed()) return;
        View top = a.getWindow().getDecorView();
        if (!(top instanceof FrameLayout)) return;
        FrameLayout decor = (FrameLayout) top;
        FrameLayout layer = layerRef == null ? null : layerRef.get();
        if (layer != null && layer.getParent() == decor) return;
        detach();
        if (!opened) {
            DanmakuConfig c = DanmakuHook.cfg;
            o = new int[]{c.pubX, c.pubY, c.timeX, c.timeY};
            opened = true;
        }
        layer = build(decor.getContext());
        decor.addView(layer, new FrameLayout.LayoutParams(-1, -1));
        layerRef = new WeakReference<>(layer);
        refresh();
    }

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

    /** 所选浮层现在的位置（dp）：配置里是 -1（默认）时取屏幕上它现在的位置，没有就 0。 */
    private static int get(boolean x) {
        DanmakuConfig c = DanmakuHook.cfg;
        int v = target == 0 ? (x ? c.pubX : c.pubY) : (x ? c.timeX : c.timeY);
        if (v >= 0) return v;
        float[] p = PubHook.shownPos(target == 0);
        return p == null ? 0 : Math.round(x ? p[0] : p[1]);
    }

    private static void put(int vx, int vy) {
        DanmakuConfig c = DanmakuHook.cfg;
        vx = Math.max(0, Math.min(LIM, vx));
        vy = Math.max(0, Math.min(LIM, vy));
        if (target == 0) {
            c.pubX = vx;
            c.pubY = vy;
        } else {
            c.timeX = vx;
            c.timeY = vy;
        }
    }

    private static void changed() {
        PubHook.reapplyPositions();
        refresh();
    }

    private static void finish(boolean keep) {
        DanmakuConfig c = DanmakuHook.cfg;
        if (!keep) {
            c.pubX = o[0];
            c.pubY = o[1];
            c.timeX = o[2];
            c.timeY = o[3];
        }
        c.vidAdjust = false;
        DanmakuHook.saveCfg();
        end();
        PubHook.reapplyPositions();
    }

    private static void nudge(int dx, int dy) {
        put(get(true) + dx, get(false) + dy);
        changed();
    }

    private static void zero() {
        DanmakuConfig c = DanmakuHook.cfg;
        if (target == 0) {
            c.pubX = -1;
            c.pubY = -1;
        } else {
            c.timeX = -1;
            c.timeY = -1;
        }
        changed();
    }

    private static void pick(int t) {
        target = t;
        refresh();
    }

    private static void refresh() {
        if (tvPos != null) {
            DanmakuConfig c = DanmakuHook.cfg;
            boolean def = target == 0 ? c.pubX < 0 : c.timeX < 0;
            String s = (target == 0 ? "发布地" : "时长") + (def ? "  默认位置" : "  左 " + get(true) + " / 上 " + get(false) + " dp");
            if (!s.contentEquals(tvPos.getText())) tvPos.setText(s);
        }
        paint(btnPub, target == 0);
        paint(btnTime, target == 1);
    }

    private static void paint(TextView b, boolean on) {
        if (b != null) b.setBackground(round(b.getContext(), on ? PRIMARY : BTN, 12));
    }

    // ------------------------------------------------------------ 触摸

    private static final View.OnTouchListener DRAG = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent e) {
            try {
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) {
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startX = get(true);
                    startY = get(false);
                } else if (a == MotionEvent.ACTION_MOVE) {
                    float d = v.getResources().getDisplayMetrics().density;
                    put(Math.round(startX + (e.getRawX() - downX) / d), Math.round(startY + (e.getRawY() - downY) / d));
                    changed();
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
        panel.setClickable(true);

        TextView head = label(ctx, "文字位置  ·  拖这里挪浮窗", 12, 0xFF9AA1B2, false);
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

        LinearLayout tg = new LinearLayout(ctx);
        tg.setOrientation(LinearLayout.HORIZONTAL);
        btnPub = button(ctx, "发布地", BTN, new Runnable() {
            public void run() {
                pick(0);
            }
        });
        btnTime = button(ctx, "时长", BTN, new Runnable() {
            public void run() {
                pick(1);
            }
        });
        tg.addView(btnPub, lp(ctx, false));
        tg.addView(btnTime, lp(ctx, true));
        panel.addView(tg, new LinearLayout.LayoutParams(-1, -2));

        TextView pos = label(ctx, "", 15, 0xFFFFFFFF, true);
        pos.setPadding(0, dp(ctx, 8), 0, 0);
        tvPos = pos;
        panel.addView(pos);
        TextView hint = label(ctx, "选好上面的一项，在屏幕上任意拖动", 12, 0xFF9AA1B2, false);
        hint.setPadding(0, dp(ctx, 2), 0, dp(ctx, 8));
        panel.addView(hint);

        LinearLayout nudge = new LinearLayout(ctx);
        nudge.setOrientation(LinearLayout.HORIZONTAL);
        String[] ar = {"◀", "▶", "▲", "▼"};
        final int[][] dd = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        for (int i = 0; i < 4; i++) {
            final int k = i;
            nudge.addView(button(ctx, ar[i], BTN, new Runnable() {
                public void run() {
                    nudge(dd[k][0], dd[k][1]);
                }
            }), lp(ctx, i > 0));
        }
        panel.addView(nudge, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout act = new LinearLayout(ctx);
        act.setOrientation(LinearLayout.HORIZONTAL);
        act.addView(button(ctx, "归零", BTN, new Runnable() {
            public void run() {
                zero();
            }
        }), lp(ctx, false));
        act.addView(button(ctx, "取消", BTN, new Runnable() {
            public void run() {
                finish(false);
            }
        }), lp(ctx, true));
        act.addView(button(ctx, "完成", PRIMARY, new Runnable() {
            public void run() {
                finish(true);
            }
        }), lp(ctx, true));
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-1, -2);
        al.topMargin = dp(ctx, 8);
        panel.addView(act, al);

        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(dp(ctx, 272), -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        pl.topMargin = dp(ctx, 120); // 浮窗默认放在上面，可以拖走
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

    private static LinearLayout.LayoutParams lp(Context ctx, boolean gap) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(ctx, 42), 1f);
        if (gap) p.leftMargin = dp(ctx, 6);
        return p;
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
