package com.rltranslator.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/** 全屏框选：手指拖一个矩形，只翻这一块。 */
final class RegionPicker {

    interface Callback {
        void onRegion(Rect rect);
    }

    private final WindowManager wm;
    private View view;

    RegionPicker(Context context) {
        this.wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    void show(Context context, Callback callback) {
        if (view != null) return;
        SelectionView selection = new SelectionView(context);
        selection.callback = callback;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
        view = selection;
        try {
            wm.addView(view, params);
        } catch (Throwable error) {
            view = null;
        }
    }

    void remove() {
        if (view == null) return;
        try {
            wm.removeView(view);
        } catch (Throwable ignored) {
        }
        view = null;
    }

    private final class SelectionView extends View {
        Callback callback;
        float startX;
        float startY;
        float currentX;
        float currentY;
        boolean dragging;

        final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint scrim = new Paint();
        final Paint clear = new Paint();

        SelectionView(Context context) {
            super(context);
            setLayerType(LAYER_TYPE_SOFTWARE, null);
            scrim.setColor(0x99000000);
            clear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(4f);
            border.setColor(0xFF3B82F6);
        }

        Rect current() {
            return new Rect(
                (int) Math.min(startX, currentX),
                (int) Math.min(startY, currentY),
                (int) Math.max(startX, currentX),
                (int) Math.max(startY, currentY));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawRect(0, 0, getWidth(), getHeight(), scrim);
            if (dragging) {
                Rect rect = current();
                canvas.drawRect(rect, clear);
                canvas.drawRect(rect, border);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = currentX = event.getRawX();
                    startY = currentY = event.getRawY();
                    dragging = true;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    currentX = event.getRawX();
                    currentY = event.getRawY();
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP: {
                    dragging = false;
                    invalidate();
                    Rect rect = current();
                    remove();
                    if (rect.width() >= 48 && rect.height() >= 48 && callback != null) callback.onRegion(rect);
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    remove();
                    return true;
                default:
                    return false;
            }
        }
    }
}
