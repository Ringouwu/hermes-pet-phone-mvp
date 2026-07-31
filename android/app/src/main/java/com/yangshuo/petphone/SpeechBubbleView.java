package com.yangshuo.petphone;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/** Exact outline based on the supplied reference bubble: broad left arc and low left-curving tail. */
public class SpeechBubbleView extends FrameLayout {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    public SpeechBubbleView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setWillNotDraw(false);
        setPadding(dp(18), dp(16), dp(18), dp(42));
        fill.setColor(0xe61d111b);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(dp(2));
        line.setColor(0xffe6a449);
    }

    private int dp(int value) { return (int) (value * density + .5f); }

    @Override protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight(), tail = dp(78), base = h - tail;
        Path path = new Path();
        path.moveTo(w * .14f, dp(10));
        path.cubicTo(w * .36f, dp(2), w * .69f, dp(5), w * .84f, dp(15));
        path.cubicTo(w * .97f, dp(25), w - dp(12), dp(95), w - dp(26), base - dp(92));
        path.cubicTo(w - dp(40), base - dp(18), w * .86f, base, w * .72f, base);
        path.lineTo(w * .69f, base);
        path.cubicTo(w * .68f, base + dp(42), w * .65f, h - dp(16), w * .58f, h - dp(2));
        path.cubicTo(w * .64f, h - dp(54), w * .65f, base + dp(4), w * .63f, base);
        path.lineTo(w * .28f, base);
        path.cubicTo(w * .12f, base, dp(30), base - dp(48), dp(15), base - dp(128));
        path.cubicTo(dp(3), base - dp(190), dp(18), dp(40), w * .14f, dp(10));
        path.close();
        fill.setShader(new LinearGradient(0, 0, w, h, 0xff2c142a, 0xff100d14, Shader.TileMode.CLAMP));
        canvas.drawPath(path, fill);
        fill.setShader(null);
        canvas.drawPath(path, line);
    }
}
