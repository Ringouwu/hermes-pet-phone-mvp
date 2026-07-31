package com.yangshuo.petphone;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/** Decorative-only background for the neon magical-girl skin. */
public class MagicBackgroundView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public MagicBackgroundView(Context context) { super(context); }

    @Override protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        paint.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{Color.rgb(29, 7, 19), Color.rgb(75, 5, 43), Color.rgb(18, 5, 16)},
                null, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(null);

        // Fixed stars keep the screen decorative without becoming a distracting animation.
        int[][] stars = {{45, 180}, {120, 430}, {w - 85, 260}, {w - 55, 610}, {70, 1120},
                {w - 115, 1240}, {150, 1580}, {w - 60, 1770}, {78, 2020}, {w - 150, 2140}};
        paint.setColor(0xffff3d91);
        for (int[] star : stars) drawSparkle(canvas, star[0], star[1], 8);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3);
        paint.setColor(0x99ff3b91);
        float cx = w * .5f, cy = h * .62f;
        canvas.drawCircle(cx, cy, Math.min(w, h) * .29f, paint);
        canvas.drawCircle(cx, cy, Math.min(w, h) * .21f, paint);
        for (int i = 0; i < 5; i++) {
            double a = -Math.PI / 2 + i * Math.PI * 2 / 5;
            double b = -Math.PI / 2 + (i + 2) * Math.PI * 2 / 5;
            canvas.drawLine(cx + (float)Math.cos(a) * w * .22f, cy + (float)Math.sin(a) * w * .22f,
                    cx + (float)Math.cos(b) * w * .22f, cy + (float)Math.sin(b) * w * .22f, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x88ff1f7c);
        drawHeart(canvas, w * .16f, h * .42f, 32);
        drawHeart(canvas, w * .82f, h * .38f, 26);
        drawHeart(canvas, w * .54f, h * .48f, 24);
    }

    private void drawSparkle(Canvas c, float x, float y, float r) {
        c.drawCircle(x, y, r * .35f, paint);
        c.drawRect(x - r, y - 1, x + r, y + 1, paint);
        c.drawRect(x - 1, y - r, x + 1, y + r, paint);
    }

    private void drawHeart(Canvas c, float x, float y, float s) {
        Path heart = new Path();
        heart.moveTo(x, y + s);
        heart.cubicTo(x - 2 * s, y - s * .2f, x - s, y - 1.4f * s, x, y - .35f * s);
        heart.cubicTo(x + s, y - 1.4f * s, x + 2 * s, y - s * .2f, x, y + s);
        c.drawPath(heart, paint);
    }
}
