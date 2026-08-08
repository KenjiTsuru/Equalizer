package com.example.autoeq;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.MotionEvent;

import androidx.appcompat.widget.AppCompatSeekBar;

public class VerticalSeekBar extends AppCompatSeekBar {

    // AbsSeekBar keeps its own listener reference private, so onTouchEvent
    // below (which bypasses super.onTouchEvent() entirely to compute progress
    // from Y instead of X) has no way to reach it directly. Keeping our own
    // copy here is what lets onStartTrackingTouch/onStopTrackingTouch actually
    // fire - previously they never did, for any caller of this view.
    private OnSeekBarChangeListener changeListener;

    public VerticalSeekBar(Context context) {
        super(context);
    }

    public VerticalSeekBar(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public VerticalSeekBar(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public void setOnSeekBarChangeListener(OnSeekBarChangeListener l) {
        this.changeListener = l;
        super.setOnSeekBarChangeListener(l);
    }

    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(h, w, oldh, oldw);
    }

    @Override
    protected synchronized void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(heightMeasureSpec, widthMeasureSpec);
        setMeasuredDimension(getMeasuredHeight(), getMeasuredWidth());
    }

    protected void onDraw(Canvas c) {
        c.rotate(-90);
        c.translate(-getHeight(), 0);

        super.onDraw(c);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) {
            return false;
        }

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                if (changeListener != null) changeListener.onStartTrackingTouch(this);
                updateProgressFromTouch(event);
                break;

            case MotionEvent.ACTION_MOVE:
                updateProgressFromTouch(event);
                break;

            case MotionEvent.ACTION_UP:
                updateProgressFromTouch(event);
                if (changeListener != null) changeListener.onStopTrackingTouch(this);
                break;

            case MotionEvent.ACTION_CANCEL:
                if (changeListener != null) changeListener.onStopTrackingTouch(this);
                break;
        }
        return true;
    }

    private void updateProgressFromTouch(MotionEvent event) {
        int i = getMax() - (int) (getMax() * event.getY() / getHeight());
        setProgress(i);
        onSizeChanged(getWidth(), getHeight(), 0, 0);
    }

}