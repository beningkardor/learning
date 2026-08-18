package com.example.xvideodownloader;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

public final class FlowProgressView extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float progress;
    private boolean active;
    private long startedAt;

    public FlowProgressView(Context context, AttributeSet attrs) { super(context, attrs); }

    public void setProgress(float value) { progress = Math.max(0f, Math.min(1f, value)); invalidate(); }
    public void setActive(boolean value) { if (value && !active) startedAt = System.currentTimeMillis(); active = value; invalidate(); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float radius = getHeight() / 2f;
        track.setColor(getResources().getColor(R.color.track));
        canvas.drawRoundRect(new RectF(0, 0, getWidth(), getHeight()), radius, radius, track);
        if (progress <= 0f) return;
        fill.setColor(getResources().getColor(R.color.accent));
        float right = Math.max(getHeight(), getWidth() * progress);
        canvas.drawRoundRect(new RectF(0, 0, right, getHeight()), radius, radius, fill);
        if (active && right > getHeight()) {
            float phase = ((System.currentTimeMillis() - startedAt) % 1400L) / 1400f;
            float x = right * phase;
            fill.setShader(new LinearGradient(x - 45, 0, x + 45, 0,
                    new int[]{0x00FFFFFF, 0x88FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(new RectF(0, 0, right, getHeight()), radius, radius, fill);
            fill.setShader(null);
            postInvalidateDelayed(40);
        }
    }
}
