package com.example.autoeq;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;

import androidx.appcompat.widget.AppCompatSeekBar;

public class VerticalSeekBar extends AppCompatSeekBar {

    public VerticalSeekBar(Context context) { super(context); }
    public VerticalSeekBar(Context context, AttributeSet attrs) { super(context, attrs); }
    public VerticalSeekBar(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(h, w, oldh, oldw); // swap
    }

    @Override
    protected synchronized void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(heightMeasureSpec, widthMeasureSpec); // swap
        setMeasuredDimension(getMeasuredHeight(), getMeasuredWidth());
    }

    @Override
    protected void onDraw(android.graphics.Canvas c) {
        c.rotate(-90);
        c.translate(-getHeight(), 0);
        super.onDraw(c);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP:
                float y = event.getY();
                int max = getMax();
                int progress = max - Math.round(max * (y / getHeight()));
                progress = Math.max(0, Math.min(max, progress));
                setProgress(progress, true);
                return true;
        }
        return super.onTouchEvent(event);
    }
}