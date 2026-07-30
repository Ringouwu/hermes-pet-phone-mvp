package com.yangshuo.petphone;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Movie;
import android.os.SystemClock;
import android.view.View;

public class GifView extends View {
    private Movie movie;
    private long started = SystemClock.uptimeMillis();
    public GifView(Context context, int id) { super(context); setGif(id); }
    public void setGif(int id) {
        movie = Movie.decodeStream(getResources().openRawResource(id));
        started = SystemClock.uptimeMillis();
        invalidate();
    }
    @Override protected void onDraw(Canvas c) {
        super.onDraw(c); if (movie == null) return;
        int duration = movie.duration() == 0 ? 1000 : movie.duration();
        movie.setTime((int)((SystemClock.uptimeMillis() - started) % duration));
        float scale = Math.min(getWidth() / (float) movie.width(), getHeight() / (float) movie.height()) * .8f;
        c.save(); c.translate((getWidth()-movie.width()*scale)/2, (getHeight()-movie.height()*scale)/2); c.scale(scale, scale); movie.draw(c,0,0); c.restore();
        postInvalidateOnAnimation();
    }
}
