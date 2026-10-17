package com.rltranslator.overlay;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** 悬浮窗：一张可拖动、可折叠的译文卡片。 */
final class Overlay {

    interface Host {
        void onTranslateNow();
        void onToggleAuto();

        void onDirectionChanged(String direction);
        void onClear();
        void onPickRegion();
    }

    private final Context ctx;
    private final WindowManager wm;
    private final Host host;
    private final Dedupe dedupe;

    private LinearLayout card;
    private ScrollView scroll;
    private LinearLayout feed;
    private TextView statusView;
    private TextView statsView;
    private TextView autoChip;
    private View bubble;
    private WindowManager.LayoutParams params;
    private boolean attached;
    private boolean collapsed;
    private boolean auto;
    /** 0=透明 1=半透明；默认半透明，别挡论文。 */
    private int alphaPct = 70;
    private boolean catTheme;
    private TextView directionChip;
    /** 显示原文：译文上方显示对应的原句。 */
    private boolean bilingual = true;
    private final java.util.List<TextView> englishViews = new java.util.ArrayList<>();
    private final java.util.List<TextView> bodyViews = new java.util.ArrayList<>();
    private TextView titleView;

    Overlay(Context context, Host host, Dedupe dedupe) {
        this.ctx = context;
        this.wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        this.host = host;
        this.dedupe = dedupe;
    }

    private int dp(float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
            ctx.getResources().getDisplayMetrics());
    }

    private LinearLayout.LayoutParams chipMargin() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.leftMargin = dp(6);
        return params;
    }

    private static String directionLabel(String direction) {
        if ("en2zh".equals(direction)) return "英译中";
        if ("zh2en".equals(direction)) return "中译英";
        return "双向";
    }

    private int accent() {
        return catTheme ? 0xFFF59E0B : 0xFF3B82F6;
    }

    private int cardBase() {
        return catTheme ? 0x1E1206 : 0x0F1319;
    }

    private GradientDrawable roundStroke(int color, int stroke, float radiusDp) {
        GradientDrawable drawable = round(color, radiusDp);
        drawable.setStroke(dp(2), stroke);
        return drawable;
    }

    private GradientDrawable round(int color, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private TextView chip(String label, boolean accented, View.OnClickListener click) {
        TextView view = new TextView(ctx);
        view.setText(label);
        view.setTextSize(12f);
        view.setTextColor(accented ? 0xFFFFFFFF : 0xFFE6EDF3);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(8), dp(5), dp(8), dp(5));
        view.setBackground(round(accented ? accent() : 0x332A333F, 9));
        view.setOnClickListener(click);
        return view;
    }

    void show() {
        if (attached) return;
        bilingual = Store.getBool(ctx, "bilingual", true);
        catTheme = Store.getBool(ctx, Store.KEY_CAT, false);
        card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        applyBackground();
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.setElevation(dp(10));

        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(ctx);
        titleView = title;
        title.setText(catTheme ? "🐱 基米翻译" : "基米翻译");
        title.setTextSize(14f);
        title.setTextColor(0xFFE6EDF3);
        title.getPaint().setFakeBoldText(true);

        statusView = new TextView(ctx);
        statusView.setText("待机");
        statusView.setTextSize(11.5f);
        statusView.setTextColor(0xFF8B98A9);
        statusView.setPadding(dp(8), 0, 0, 0);
        statusView.setSingleLine(true);
        statusView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        statusView.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView regionChip = chip("框选", true, v -> host.onPickRegion());
        autoChip = chip("自动", false, v -> host.onToggleAuto());

        // 翻译方向：点一下在「自动 → 英译中 → 中译英」之间循环，按钮上直接显示当前方向
        directionChip = chip(directionLabel(Store.get(ctx, Store.KEY_DIRECTION, "auto")), false, v -> {
            String current = Store.get(ctx, Store.KEY_DIRECTION, "auto");
            String next = "auto".equals(current) ? "en2zh" : ("en2zh".equals(current) ? "zh2en" : "auto");
            Store.put(ctx, Store.KEY_DIRECTION, next);
            directionChip.setText(directionLabel(next));
            directionChip.setBackground(round("auto".equals(next) ? 0x332A333F : accent(), 9));
            host.onDirectionChanged(next);
        });

        final TextView[] dictChip = new TextView[1];
        dictChip[0] = chip(bilingual ? "显示原文 · 开" : "显示原文 · 关", bilingual, v -> {
            bilingual = !bilingual;
            applyBilingual();
            dictChip[0].setText(bilingual ? "显示原文 · 开" : "显示原文 · 关");
            dictChip[0].setBackground(round(bilingual ? accent() : 0x332A333F, 9));
            Store.putBool(ctx, "bilingual", bilingual);
        });

        // 「更多」折叠行：框选 / 清空 / 收起 + 透明度滑杆
        final LinearLayout moreRow = new LinearLayout(ctx);
        moreRow.setOrientation(LinearLayout.VERTICAL);
        moreRow.setVisibility(View.GONE);
        moreRow.setPadding(0, dp(7), 0, 0);

        TextView clearChip = chip("清空", false, v -> host.onClear());
        TextView closeChip = chip("收起", false, v -> collapse(true));

        LinearLayout alphaRow = new LinearLayout(ctx);
        alphaRow.setOrientation(LinearLayout.HORIZONTAL);
        alphaRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        alphaRow.setPadding(0, dp(4), 0, 0);
        TextView alphaText = new TextView(ctx);
        alphaText.setText("背景");
        alphaText.setTextSize(11.5f);
        alphaText.setTextColor(0xFF8B98A9);
        alphaRow.addView(alphaText);
        android.widget.SeekBar alphaBar = new android.widget.SeekBar(ctx);
        alphaBar.setMax(80);
        alphaPct = Store.getInt(ctx, "alphaPct", 70);
        alphaBar.setProgress(Math.max(0, Math.min(80, alphaPct - 20)));
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(0, dp(32), 1f);
        barParams.leftMargin = dp(10);
        alphaBar.setLayoutParams(barParams);
        final TextView alphaValue = new TextView(ctx);
        alphaValue.setText(alphaPct + "%");
        alphaValue.setTextSize(11f);
        alphaValue.setTextColor(0xFF8B98A9);
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        valueParams.leftMargin = dp(8);
        alphaValue.setLayoutParams(valueParams);
        alphaRow.addView(alphaValue);
        alphaBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                alphaPct = 20 + progress;
                alphaValue.setText(alphaPct + "%");
                applyBackground();
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar seekBar) {
                Store.putInt(ctx, "alphaPct", alphaPct);
            }
        });
        alphaRow.addView(alphaBar);
        moreRow.addView(alphaRow);

        LinearLayout displayRow = new LinearLayout(ctx);
        displayRow.setOrientation(LinearLayout.HORIZONTAL);
        displayRow.setPadding(0, dp(6), 0, dp(2));
        displayRow.addView(dictChip[0]);
        moreRow.addView(displayRow);

        TextView moreChip = chip("不透明度", false, v -> moreRow.setVisibility(
            moreRow.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));

        header.addView(title);
        header.addView(statusView);
        header.addView(moreChip, chipMargin());
        card.addView(header);

        // 第二行：框选 / 自动 / 方向（双向·英译中·中译英）/ 清空 / 收起
        LinearLayout actionRow = new LinearLayout(ctx);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setPadding(0, dp(7), 0, 0);
        actionRow.addView(regionChip);
        actionRow.addView(autoChip, chipMargin());
        actionRow.addView(directionChip, chipMargin());
        actionRow.addView(clearChip, chipMargin());
        actionRow.addView(closeChip, chipMargin());
        card.addView(actionRow);
        card.addView(moreRow);

        View divider = new View(ctx);
        divider.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        dividerParams.topMargin = dp(8);
        card.addView(divider, dividerParams);

        scroll = new ScrollView(ctx);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(190));
        scrollParams.topMargin = dp(6);
        scroll.setLayoutParams(scrollParams);
        scroll.setVerticalScrollBarEnabled(false);

        feed = new LinearLayout(ctx);
        feed.setOrientation(LinearLayout.VERTICAL);
        feed.setPadding(0, 0, 0, 0);
        scroll.addView(feed, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(scroll);

        statsView = new TextView(ctx);
        statsView.setTextSize(10.5f);
        statsView.setTextColor(0xFF7C8899);
        statsView.setPadding(0, dp(6), 0, 0);
        statsView.setText("点「框选」圈一块翻；「自动」每 8 秒看一次");
        card.addView(statsView);

        // 拖动
        header.setOnTouchListener(new View.OnTouchListener() {
            float downX;
            float downY;
            int startX;
            int startY;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX();
                        downY = event.getRawY();
                        startX = params.x;
                        startY = params.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        params.x = startX + (int) (event.getRawX() - downX);
                        params.y = startY + (int) (event.getRawY() - downY);
                        clamp();
                        wm.updateViewLayout(card, params);
                        return true;
                    case MotionEvent.ACTION_UP:
                        Store.putInt(ctx, Store.KEY_X, params.x);
                        Store.putInt(ctx, Store.KEY_Y, params.y);
                        return true;
                    default:
                        return false;
                }
            }
        });

        int screenWidth = ctx.getResources().getDisplayMetrics().widthPixels;
        int width = Math.min((int) (screenWidth * 0.88f), dp(430));
        params = new WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            Build.VERSION.SDK_INT >= 22
                ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = Store.getInt(ctx, Store.KEY_X, Math.max(0, (screenWidth - width) / 2));
        params.y = Store.getInt(ctx, Store.KEY_Y,
            Math.max(dp(80), ctx.getResources().getDisplayMetrics().heightPixels - dp(430)));
        clamp();

        applyBackground();
        wm.addView(card, params);
        attached = true;
        collapsed = false;
    }

    private void clamp() {
        int screenWidth = ctx.getResources().getDisplayMetrics().widthPixels;
        int screenHeight = ctx.getResources().getDisplayMetrics().heightPixels;
        int width = params.width > 0 ? params.width : dp(320);
        params.x = Math.max(0, Math.min(params.x, screenWidth - dp(72)));
        params.y = Math.max(0, Math.min(params.y, screenHeight - dp(120)));
    }

    void hide() {
        if (bubble != null && attached) {
            try {
                wm.removeView(bubble);
            } catch (Throwable ignored) {
            }
            bubble = null;
        }
        if (attached && card != null) {
            try {
                wm.removeView(card);
            } catch (Throwable ignored) {
            }
        }
        attached = false;
        collapsed = false;
    }

    /** 折叠成一颗小圆钮，不挡论文。 */
    void collapse(boolean toBubble) {
        if (!attached || !toBubble || collapsed) return;
        try {
            wm.removeView(card);
        } catch (Throwable ignored) {
        }
        collapsed = true;

        // 收起后的圆钮：和 DSH 页面那颗一样的橙色猫猫表情（可拖动）
        TextView knob = new TextView(ctx);
        knob.setText("🐱");
        knob.setTextSize(22f);
        knob.setGravity(Gravity.CENTER);
        GradientDrawable bubbleBg = new GradientDrawable(
            GradientDrawable.Orientation.TL_BR, new int[] {0xFFF59E0B, 0xFFF97316});
        bubbleBg.setCornerRadius(dp(28));
        bubbleBg.setStroke(dp(2), 0xE6FFFFFF);
        knob.setBackground(bubbleBg);
        knob.setElevation(dp(8));
        bubble = knob;

        int screenW = ctx.getResources().getDisplayMetrics().widthPixels;
        int screenH = ctx.getResources().getDisplayMetrics().heightPixels;
        final WindowManager.LayoutParams bubbleParams = new WindowManager.LayoutParams(
            dp(56), dp(56),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT);
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        bubbleParams.x = Store.getInt(ctx, "bubbleX", Math.max(0, screenW - dp(76)));
        bubbleParams.y = Store.getInt(ctx, "bubbleY", Math.max(dp(80), screenH / 2));

        knob.setOnTouchListener(new View.OnTouchListener() {
            float downX;
            float downY;
            int startX;
            int startY;
            boolean moved;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX();
                        downY = event.getRawY();
                        startX = bubbleParams.x;
                        startY = bubbleParams.y;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        int dx = (int) (event.getRawX() - downX);
                        int dy = (int) (event.getRawY() - downY);
                        if (Math.abs(dx) > dp(4) || Math.abs(dy) > dp(4)) moved = true;
                        bubbleParams.x = Math.max(0, Math.min(screenW - dp(56), startX + dx));
                        bubbleParams.y = Math.max(0, Math.min(screenH - dp(56), startY + dy));
                        try {
                            wm.updateViewLayout(bubble, bubbleParams);
                        } catch (Throwable ignored) {
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        if (moved) {
                            Store.putInt(ctx, "bubbleX", bubbleParams.x);
                            Store.putInt(ctx, "bubbleY", bubbleParams.y);
                        } else {
                            expand();
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                    default:
                        return false;
                }
            }
        });
        try {
            wm.addView(bubble, bubbleParams);
        } catch (Throwable ignored) {
        }
    }

    void expand() {
        if (attached && !collapsed) return;
        if (bubble != null) {
            try {
                wm.removeView(bubble);
            } catch (Throwable ignored) {
            }
            bubble = null;
        }
        collapsed = false;
        try {
            applyBackground();
        wm.addView(card, params);
        } catch (Throwable ignored) {
        }
    }

    /** 文字配色：透明 = 深色且更粗，不透明 = 浅色常规；用笔画宽度做「加粗梯度」，不用描边光晕。 */
    private boolean lightSurface() {
        return alphaPct < 55;
    }

    private int primaryColor() {
        return lightSurface() ? 0xFF0A0E14 : 0xFFF4F8FF;
    }

    private int secondaryColor() {
        return lightSurface() ? 0xFF1E2630 : 0xFFB9C4D2;
    }

    private void styleText(TextView view, boolean primary) {
        boolean light = lightSurface();
        view.setTextColor(primary ? primaryColor() : secondaryColor());
        view.setShadowLayer(0f, 0f, 0f, 0);
        // 背景越透（alpha 越低）笔画越粗：20% → 满粗，55% → 常规
        float weight = light ? Math.max(0f, Math.min(1f, (55f - alphaPct) / 35f)) : 0f;
        if (!primary) weight *= 0.6f;
        Paint paint = view.getPaint();
        if (weight > 0.02f) {
            paint.setStyle(Paint.Style.FILL_AND_STROKE);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(dp(0.55f * weight + 0.12f));
        } else {
            paint.setStyle(Paint.Style.FILL);
            paint.setStrokeWidth(0f);
        }
        view.invalidate();
    }

    private void applyBackground() {
        if (card == null) return;
        int alpha = Math.max(20, Math.min(100, alphaPct));
        int alphaByte = Math.round(alpha * 255f / 100f);
        card.setBackground(round((alphaByte << 24) | cardBase(), 16));

        if (titleView != null) styleText(titleView, true);
        if (statusView != null) styleText(statusView, false);
        if (statsView != null) styleText(statsView, false);
        for (TextView view : englishViews) styleText(view, false);
        for (TextView view : bodyViews) styleText(view, true);
    }

    private void applyBilingual() {
        for (TextView view : englishViews) {
            view.setVisibility(bilingual ? View.VISIBLE : View.GONE);
        }
    }

    void setStatus(String text) {
        if (statusView != null) statusView.setText(text);
    }

    void setAuto(boolean enabled) {
        auto = enabled;
        if (autoChip != null) {
            autoChip.setText(enabled ? "自动·开" : "自动");
            autoChip.setBackground(round(enabled ? accent() : 0x332A333F, 9));
            autoChip.setTextColor(enabled ? 0xFFFFFFFF : 0xFFE6EDF3);
        }
    }

    void setStats(String text) {
        if (statsView != null) statsView.setText(text);
    }

    void addEntry(String sourceText, String translation) {
        if (feed == null) return;
        LinearLayout item = new LinearLayout(ctx);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setPadding(0, 0, 0, dp(14));

        // 先给原文：让用户一眼看出这段译文对应页面上的哪一句
        if (sourceText != null && !sourceText.trim().isEmpty()) {
            TextView originView = new TextView(ctx);
            originView.setText(sourceText.trim());
            originView.setTextSize(11f);
            originView.setTextColor(0xFF8B98A9);
            originView.setMaxLines(2);
            originView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            originView.setLineSpacing(dp(2), 1f);
            originView.setVisibility(bilingual ? View.VISIBLE : View.GONE);
            styleText(originView, false);
            englishViews.add(originView);
            item.addView(originView);
        }

        TextView bodyView = new TextView(ctx);
        bodyView.setText(translation);
        bodyView.setTextSize(16f);
        bodyView.setTextColor(0xFFE6EDF3);
        bodyView.setLineSpacing(dp(4), 1f);
        bodyView.setPadding(0, dp(3), 0, 0);
        styleText(bodyView, true);
        bodyViews.add(bodyView);
        item.addView(bodyView);

        feed.addView(item);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    /** 调试信息：模型没按格式回时，把原文贴进流里给用户看。 */
    void addDebug(String text) {
        if (feed == null) return;
        TextView view = new TextView(ctx);
        view.setText(text);
        view.setTextSize(11.5f);
        view.setTextColor(0xFFF59E0B);
        view.setPadding(0, 0, 0, dp(12));
        feed.addView(view);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    void clearFeed() {
        if (feed != null) feed.removeAllViews();
    }
}
