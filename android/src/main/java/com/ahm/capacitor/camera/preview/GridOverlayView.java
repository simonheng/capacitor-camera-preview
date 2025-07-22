package com.ahm.capacitor.camera.preview;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

public class GridOverlayView extends View {
    private Paint gridPaint;
    private String gridMode = "none";

    public GridOverlayView(Context context) {
        super(context);
        init();
    }

    public GridOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public GridOverlayView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        gridPaint = new Paint();
        gridPaint.setColor(0x80FFFFFF); // Semi-transparent white
        gridPaint.setStrokeWidth(2f);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setAntiAlias(true);
    }

    public void setGridMode(String mode) {
        this.gridMode = mode != null ? mode : "none";
        setVisibility("none".equals(this.gridMode) ? View.GONE : View.VISIBLE);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if ("none".equals(gridMode)) {
            return;
        }

        int width = getWidth();
        int height = getHeight();

        if (width <= 0 || height <= 0) {
            return;
        }

        if ("3x3".equals(gridMode)) {
            drawGrid(canvas, width, height, 3);
        } else if ("4x4".equals(gridMode)) {
            drawGrid(canvas, width, height, 4);
        }
    }

    private void drawGrid(Canvas canvas, int width, int height, int divisions) {
        float stepX = (float) width / divisions;
        float stepY = (float) height / divisions;

        // Draw vertical lines
        for (int i = 1; i < divisions; i++) {
            float x = i * stepX;
            canvas.drawLine(x, 0, x, height, gridPaint);
        }

        // Draw horizontal lines
        for (int i = 1; i < divisions; i++) {
            float y = i * stepY;
            canvas.drawLine(0, y, width, y, gridPaint);
        }
    }
}
