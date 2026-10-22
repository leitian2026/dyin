package com.danmaku.randcolor;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.text.Layout;
import android.widget.TextView;

/**
 * 文字灰色底（1.25.0）：视频文案、视频时长、发布日期地点，每一行字后面画一条灰色底，透明度 lineBg（0～100%）在设置页调，0 = 不画。
 * 底 = 这一行的文字框向四周各放大 1sp（宽、高都比字大一个字号档），圆角；多行的底画成一个整体（Path 并集），重叠处不会变深。
 *
 * 画在文字之前：自己的两行（发布信息、时长）用 {@link Row}（TextView 子类，onDraw 先画底再 super）；
 * 抖音的文案控件在 PubHook.DrawHook（MentionTextView#onDraw 之前）里调 {@link #drawBars}。
 * 绘制路径里不分配对象：Path / Paint 是静态复用的（都在主线程绘制，不重入）。
 */
final class LineBg {
    private LineBg() {}

    private static final Path PATH = new Path();
    private static final Paint PAINT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final int GRAY = 0x00666666;

    /** 底比字多出的边：1sp。模糊边的半径：1.5sp。 */
    private static final float BAR_SP = 1f, BLUR_SP = 1.5f;
    private static BlurMaskFilter blur;
    private static float blurR;

    /** 自己放的两行的内边距：3sp，给底和它的模糊边留地方（底只比字多 1sp，模糊边再往外一点）。PubHook 用它抵消行距 / 左边距。 */
    static int pad(Context c) {
        return Math.round(3f * c.getResources().getDisplayMetrics().scaledDensity);
    }

    /** 在 cv 上给 t 的每一行文字画底。t 还没排好版（没有 Layout）或 pct <= 0 就什么都不画。 */
    static void drawBars(Canvas cv, TextView t, int pct) {
        if (pct <= 0 || cv == null) return;
        Layout l = t.getLayout();
        if (l == null) return;
        int n = l.getLineCount();
        if (n <= 0) return;
        float sd = t.getResources().getDisplayMetrics().scaledDensity;
        float pad = BAR_SP * sd;
        float ox = t.getTotalPaddingLeft(), oy = t.getTotalPaddingTop();
        PATH.rewind();
        for (int i = 0; i < n; i++) {
            float lf = l.getLineLeft(i), rt = l.getLineRight(i);
            if (rt <= lf) continue; // 空行不画
            PATH.addRoundRect(ox + lf - pad, oy + l.getLineTop(i) - pad, ox + rt + pad, oy + l.getLineBottom(i) + pad, pad * 2f, pad * 2f, Path.Direction.CW);
        }
        float br = BLUR_SP * sd;
        if (blur == null || blurR != br) { // 半径只随屏幕密度变，几乎不会重建
            blur = new BlurMaskFilter(br, BlurMaskFilter.Blur.NORMAL);
            blurR = br;
        }
        PAINT.setMaskFilter(blur); // 模糊边
        PAINT.setStyle(Paint.Style.FILL);
        PAINT.setColor(GRAY | (Math.min(100, pct) * 255 / 100) << 24);
        cv.drawPath(PATH, PAINT);
    }

    /** 自己放的两行（发布信息、时长）用的 TextView：先画底再画字；四边留出 1sp 的内边距，底才不会被控件边界裁掉。 */
    static final class Row extends TextView {
        Row(Context c) {
            super(c);
            int p = pad(c);
            setPadding(p, p, p, p);
            setIncludeFontPadding(false); // 行高 = 字本身的行高，和文案的行距一样
        }

        @Override
        protected void onDraw(Canvas canvas) {
            try {
                drawBars(canvas, this, DanmakuHook.cfg.lineBg);
            } catch (Throwable ignored) {
            }
            super.onDraw(canvas);
        }
    }
}
