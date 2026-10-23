package com.danmaku.randcolor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.text.Layout;
import android.widget.TextView;

/**
 * 文字灰色底（1.25.0）：视频文案、视频时长、发布日期地点（1.29.0 起还有弹幕、直播间开播时间），每一行字后面画一条黑色半透明底（类名里的「灰色」是旧叫法），透明度 lineBg（0～100%）在设置页调，0 = 不画。
 * 底 = 这一行的文字框向四周各放大 1sp（宽、高都比字大一个字号档），圆角；多行的底画成一个整体（Path 并集），重叠处不会变深。
 *
 * 画在文字之前：自己的两行（发布信息、时长）用 {@link Row}（TextView 子类，onDraw 先画底再 super）；
 * 抖音的文案控件在 PubHook.DrawHook（MentionTextView#onDraw 之前）里调 {@link #drawBars}；
 * 直播间开播时间也是 {@link Row}；弹幕（Compose 画的，没有 TextView）在 DanmakuHook 里按每条弹幕的文字框调 {@link #drawBox}。
 * 绘制路径里不分配对象：Path / Paint 是静态复用的（都在主线程绘制，不重入）。
 */
final class LineBg {
    private LineBg() {}

    private static final Path PATH = new Path();
    private static final Paint PAINT = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 底的默认颜色：接近纯黑（1.29.1 起，原来是中灰 #666666）。用户在设置页选了底色（cfg.lineBgColor）就用用户的，见 fillPath。 */
    private static final int GRAY = 0x00101010;

    /**
     * 以后新增的、由模块自己决定颜色的文字（像随机色的弹幕）：颜色过一遍这里，太暗的（纯蓝、紫、深红等）往白色方向提亮，
     * 直到相对亮度（gamma 2 近似）达到 minLum（0～1，设置页「字的最低亮度」的百分比 / 100）；够亮的原样返回，minLum <= 0 不提亮。
     * 底是半透明的黑、叠在亮一点的画面上，实际比纯黑亮，所以这个值交给用户自己调。用户自己选的颜色（文案、时长、开播时间）不改。只处理 0xRRGGBB，不分配对象。
     */
    static int legible(int rgb, float minLum) {
        if (minLum <= 0f) return rgb;
        float r = ((rgb >> 16) & 255) / 255f, g = ((rgb >> 8) & 255) / 255f, b = (rgb & 255) / 255f;
        float t = 0f;
        while (t < 1f && 0.2126f * sq(r + (1f - r) * t) + 0.7152f * sq(g + (1f - g) * t) + 0.0722f * sq(b + (1f - b) * t) < minLum) t += 0.05f;
        if (t <= 0f) return rgb;
        if (t > 1f) t = 1f;
        return (Math.round((r + (1f - r) * t) * 255f) << 16) | (Math.round((g + (1f - g) * t) * 255f) << 8) | Math.round((b + (1f - b) * t) * 255f);
    }

    private static float sq(float x) {
        return x * x; // 近似 sRGB → 线性（gamma 2）
    }

    /** 底比字多出的边：1sp。柔边向外再延伸 1.5sp（分 3 层逐层变淡）。 */
    private static final float BAR_SP = 1f, SOFT_SP = 1.5f;

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
        fillPath(cv, sd, pct);
    }

    /** 在 cv 上给一个文字框（左上右下，canvas 像素，不含底多出的边）画底，圆角、柔边和 {@link #drawBars} 一样。弹幕用。 */
    static void drawBox(Canvas cv, float l, float t, float r, float b, float sd, int pct) {
        if (pct <= 0 || cv == null || r <= l || b <= t) return;
        float pad = BAR_SP * sd;
        PATH.rewind();
        PATH.addRoundRect(l - pad, t - pad, r + pad, b + pad, pad * 2f, pad * 2f, Path.Direction.CW);
        fillPath(cv, sd, pct);
    }

    /** 把 PATH 画成带柔边的灰色底。 */
    private static void fillPath(Canvas cv, float sd, int pct) {
        int a = Math.min(100, pct) * 255 / 100;
        int gray = DanmakuHook.cfg.lineBgColor == 0 ? GRAY : (DanmakuHook.cfg.lineBgColor & 0xFFFFFF); // 用户选的底色；0 = 默认近黑
        // 柔边：不用 BlurMaskFilter（硬件绘制下这台机器上画不出来）。先在底的外面画 3 圈逐层变淡的描边（只画在底的外侧），再画底本身。
        cv.save();
        cv.clipOutPath(PATH);
        PAINT.setStyle(Paint.Style.STROKE);
        PAINT.setStrokeJoin(Paint.Join.ROUND);
        for (int k = 3; k >= 1; k--) {
            PAINT.setStrokeWidth(2f * SOFT_SP * sd * k / 3f);
            PAINT.setColor(gray | (a / 4) << 24);
            cv.drawPath(PATH, PAINT);
        }
        cv.restore();
        PAINT.setStyle(Paint.Style.FILL);
        PAINT.setColor(gray | a << 24);
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
