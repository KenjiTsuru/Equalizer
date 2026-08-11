package com.example.autoeq;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import com.google.android.material.color.MaterialColors;

/**
 * Pure-rendering overlay drawn behind eq_bands_row's per-band VerticalSeekBars
 * (see equalizer_band_item.xml - their track/thumb are transparent) so all
 * NUM_BANDS values read as one continuous connected graph instead of
 * NUM_BANDS disconnected vertical tracks. Never handles touch itself - the
 * seekbars underneath still own dragging exactly as before; this just needs
 * to be told the current values (setProgress/setAllProgress) alongside every
 * place that already calls VerticalSeekBar.setProgress, using the same
 * 0..maxProgress convention (0 = bottom/min gain).
 */
public class EqualizerCurveView extends View {

    private static final int ACCENT = 0xFFA855F7; // same purple used elsewhere for this screen's EQ UI
    private static final int ACCENT_STROKE = 0xFF710193; // dark end of the old per-band seekbar gradient

    private int bandCount = EqBandConfig.NUM_BANDS;
    private int maxProgress = 1;
    private int[] progress = new int[bandCount];
    private float[] xs = new float[bandCount];
    private float[] ys = new float[bandCount];

    private final Path fillPath = new Path();
    private final Path linePath = new Path();
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final float lineWidthPx = dp(2.5f);
    private final float dotRadiusPx = dp(4.5f);
    private final float dotStrokeWidthPx = dp(1.5f);
    private final float gridWidthPx = dp(1f);
    private final float verticalInsetPx = dp(8f); // keeps dots off the very top/bottom edge

    public EqualizerCurveView(Context context) {
        super(context);
        init();
    }

    public EqualizerCurveView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public EqualizerCurveView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setWillNotDraw(false);

        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(lineWidthPx);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setColor(ACCENT);

        fillPaint.setStyle(Paint.Style.FILL);

        dotPaint.setStyle(Paint.Style.FILL);
        dotPaint.setColor(ACCENT);

        dotStrokePaint.setStyle(Paint.Style.STROKE);
        dotStrokePaint.setStrokeWidth(dotStrokeWidthPx);
        dotStrokePaint.setColor(ACCENT_STROKE);

        // Faint per-band column guides - the seekbars occupying these
        // columns have no visible track anymore (see equalizer_band_item.xml),
        // so without this the entire graph would give no hint at all that
        // it's draggable. Deliberately subtle: a backdrop grid, not a control.
        int outline = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutline, 0xFF888888);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(gridWidthPx);
        gridPaint.setColor(withAlpha(outline, 60));
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    public void setBandCount(int count) {
        count = Math.max(1, count);
        if (count == bandCount) return;
        bandCount = count;
        progress = new int[bandCount];
        xs = new float[bandCount];
        ys = new float[bandCount];
        invalidate();
    }

    public void setMaxProgress(int max) {
        maxProgress = Math.max(1, max);
        invalidate();
    }

    /** Updates one band live while dragging - cheap, meant to be called from onProgressChanged every frame of a drag. */
    public void setProgress(int band, int value) {
        if (band < 0 || band >= progress.length) return;
        progress[band] = value;
        invalidate();
    }

    /** Updates every band at once - called after building/switching presets. */
    public void setAllProgress(int[] values) {
        if (values == null) return;
        if (values.length != bandCount) {
            setBandCount(values.length);
        }
        System.arraycopy(values, 0, progress, 0, Math.min(values.length, progress.length));
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (h <= 0) return;
        // Anchored to the view's fixed height (the full gain range), not to
        // any single band's current value - a band pinned near the top of
        // its range reads as vivid, one near the bottom reads as faded,
        // everything in between blends smoothly. Rebuilt only on size
        // change since it doesn't depend on the current values.
        fillPaint.setShader(new LinearGradient(
                0, verticalInsetPx, 0, h,
                withAlpha(ACCENT, 160), withAlpha(ACCENT, 0),
                Shader.TileMode.CLAMP));
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0 || bandCount <= 0) return;

        float top = verticalInsetPx;
        float bottom = height - verticalInsetPx;
        float usableHeight = Math.max(1f, bottom - top);
        float colWidth = (float) width / bandCount;

        for (int i = 0; i < bandCount; i++) {
            xs[i] = (i + 0.5f) * colWidth;
            float fraction = maxProgress > 0 ? (float) progress[i] / maxProgress : 0f;
            fraction = Math.max(0f, Math.min(1f, fraction));
            ys[i] = bottom - fraction * usableHeight;
            canvas.drawLine(xs[i], top, xs[i], bottom, gridPaint);
        }

        linePath.reset();
        fillPath.reset();
        linePath.moveTo(xs[0], ys[0]);
        fillPath.moveTo(xs[0], bottom);
        fillPath.lineTo(xs[0], ys[0]);
        for (int i = 1; i < bandCount; i++) {
            linePath.lineTo(xs[i], ys[i]);
            fillPath.lineTo(xs[i], ys[i]);
        }
        fillPath.lineTo(xs[bandCount - 1], bottom);
        fillPath.close();

        canvas.drawPath(fillPath, fillPaint);
        canvas.drawPath(linePath, linePaint);

        for (int i = 0; i < bandCount; i++) {
            canvas.drawCircle(xs[i], ys[i], dotRadiusPx, dotPaint);
            canvas.drawCircle(xs[i], ys[i], dotRadiusPx, dotStrokePaint);
        }
    }
}
