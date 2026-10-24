package com.brouken.player;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.MotionEvent;

import androidx.annotation.Nullable;
import androidx.media3.ui.DefaultTimeBar;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

class CustomDefaultTimeBar extends DefaultTimeBar {

    Rect scrubberBar;
    private boolean scrubbing;
    private int scrubbingStartX;

    interface ProgressListener {
        void onProgress(long position, long duration);
    }

    interface PlayheadListener {
        void onPlayheadMoved(float x);
    }

    private ProgressListener progressListener;
    private PlayheadListener playheadListener;
    private long position;
    private long duration;

    private Rect progressBar;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float lastPlayheadX = -1;

    public CustomDefaultTimeBar(Context context) {
        this(context, null);
    }

    public CustomDefaultTimeBar(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CustomDefaultTimeBar(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, attrs);
    }

    public CustomDefaultTimeBar(Context context, @Nullable AttributeSet attrs, int defStyleAttr, @Nullable AttributeSet timebarAttrs) {
        this(context, attrs, defStyleAttr, timebarAttrs, 0);
    }

    public CustomDefaultTimeBar(Context context, @Nullable AttributeSet attrs, int defStyleAttr, @Nullable AttributeSet timebarAttrs, int defStyleRes) {
        super(context, attrs, defStyleAttr, timebarAttrs, defStyleRes);
        try {
            Field field = DefaultTimeBar.class.getDeclaredField("scrubberBar");
            field.setAccessible(true);
            scrubberBar = (Rect) field.get(this);
            field = DefaultTimeBar.class.getDeclaredField("progressBar");
            field.setAccessible(true);
            progressBar = (Rect) field.get(this);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            e.printStackTrace();
        }
    }

    // tvOS style: a thin rounded track, the played part in white and a tick at the playhead.
    // Thicker while focused, so it is clear the remote is scrubbing.
    @Override
    public void onDraw(Canvas canvas) {
        if (progressBar == null || scrubberBar == null || progressBar.width() <= 0) {
            super.onDraw(canvas);
            return;
        }
        final boolean focused = isFocused();
        final float barHeight = Utils.dpToPx(focused ? 8 : 5);
        final float radius = barHeight / 2;
        final float centerY = progressBar.exactCenterY();
        final float top = centerY - radius;
        final float bottom = centerY + radius;

        paint.setColor(focused ? 0x66FFFFFF : 0x4DFFFFFF);
        canvas.drawRoundRect(progressBar.left, top, progressBar.right, bottom, radius, radius, paint);

        final float playheadX = Math.max(progressBar.left, Math.min(scrubberBar.right, progressBar.right));
        if (playheadX > progressBar.left) {
            paint.setColor(focused ? 0xFFFFFFFF : 0xD9FFFFFF);
            canvas.drawRoundRect(progressBar.left, top, playheadX, bottom, radius, radius, paint);
        }

        final float tickHalfWidth = Utils.dpToPx(1);
        final float tickHalfHeight = radius + Utils.dpToPx(focused ? 7 : 5);
        paint.setColor(0xFFFFFFFF);
        canvas.drawRoundRect(playheadX - tickHalfWidth, centerY - tickHalfHeight, playheadX + tickHalfWidth, centerY + tickHalfHeight, tickHalfWidth, tickHalfWidth, paint);

        if (playheadListener != null && playheadX != lastPlayheadX) {
            lastPlayheadX = playheadX;
            playheadListener.onPlayheadMoved(playheadX);
        }
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, @Nullable Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        invalidate();
    }

    public void setPlayheadListener(PlayheadListener playheadListener) {
        this.playheadListener = playheadListener;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN && scrubberBar != null) {
            scrubbing = false;
            scrubbingStartX = (int)event.getX();
            final int distanceFromScrubber = Math.abs(scrubberBar.right - scrubbingStartX);
            if (distanceFromScrubber > Utils.dpToPx(24))
                return true;
            else
                scrubbing = true;
        }
        if (!scrubbing && event.getAction() == MotionEvent.ACTION_MOVE && scrubberBar != null) {
            final int distanceFromStart = Math.abs(((int)event.getX()) - scrubbingStartX);
            if (distanceFromStart > Utils.dpToPx(6)) {
                scrubbing = true;
                try {
                    final Method method = DefaultTimeBar.class.getDeclaredMethod("startScrubbing", long.class);
                    method.setAccessible(true);
                    method.invoke(this, (long) 0);
                } catch (NoSuchMethodException | SecurityException | IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
                    e.printStackTrace();
                }
            } else {
                return true;
            }
        }
        return super.onTouchEvent(event);
    }

    public void setProgressListener(ProgressListener progressListener) {
        this.progressListener = progressListener;
    }

    @Override
    public void setPosition(long position) {
        super.setPosition(position);
        this.position = position;
        notifyProgress();
    }

    @Override
    public void setDuration(long duration) {
        super.setDuration(duration);
        this.duration = duration;
        notifyProgress();
    }

    private void notifyProgress() {
        if (progressListener != null) {
            progressListener.onProgress(position, duration);
        }
    }
}
