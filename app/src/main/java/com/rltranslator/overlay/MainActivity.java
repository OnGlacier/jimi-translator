package com.rltranslator.overlay;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** 配置页：哈基米配色 + 猫猫头，功能不变。 */
public class MainActivity extends Activity {

    private static final int CREAM = 0xFFFFF7EC;
    private static final int CARD = 0xFFFFFFFF;
    private static final int CARD_BORDER = 0xFFF0DFC8;
    private static final int INK = 0xFF3D2B1F;
    private static final int INK_SOFT = 0xFF8C6F55;
    private static final int ORANGE = 0xFFF59E0B;
    private static final int ORANGE_DEEP = 0xFFB45309;
    private static final int FIELD_BG = 0xFFFFF3E2;

    private EditText keyInput;
    private EditText baseInput;
    private EditText modelInput;
    private EditText intervalInput;
    private EditText promptInput;
    private EditText glossaryInput;
    private CheckBox streamBox;
    private CheckBox thinkBox;
    private CheckBox catBox;
    private android.widget.Spinner dirSpinner;
    private TextView statusView;

    private int dp(float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    private GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private GradientDrawable roundedStroke(int color, int stroke, float radiusDp) {
        GradientDrawable drawable = rounded(color, radiusDp);
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private LinearLayout card(LinearLayout parent, String title) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(roundedStroke(CARD, CARD_BORDER, 18));
        box.setPadding(dp(14), dp(12), dp(14), dp(14));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(12);
        parent.addView(box, params);

        if (title != null) {
            TextView head = new TextView(this);
            head.setText("🐾 " + title);
            head.setTextSize(13f);
            head.setTextColor(ORANGE_DEEP);
            head.getPaint().setFakeBoldText(true);
            head.setPadding(0, 0, 0, dp(2));
            box.addView(head);
        }
        return box;
    }

    private TextView label(LinearLayout parent, String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(12.5f);
        view.setTextColor(INK_SOFT);
        view.setPadding(0, dp(10), 0, dp(4));
        parent.addView(view);
        return view;
    }

    private EditText field(LinearLayout parent, String hint, String value, boolean secret) {
        label(parent, hint);
        EditText input = new EditText(this);
        input.setText(value);
        input.setTextSize(14f);
        input.setTextColor(INK);
        input.setHintTextColor(0xFFBFA184);
        input.setSingleLine(true);
        input.setBackground(rounded(FIELD_BG, 12));
        input.setPadding(dp(12), dp(9), dp(12), dp(9));
        input.setInputType(secret
            ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
            : InputType.TYPE_CLASS_TEXT);
        parent.addView(input, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return input;
    }

    private Button pill(LinearLayout parent, String text, boolean filled) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(14f);
        button.setTextColor(filled ? Color.WHITE : ORANGE_DEEP);
        button.setBackground(filled ? rounded(ORANGE, 14) : roundedStroke(0x00FFFFFF, ORANGE, 14));
        button.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(10);
        parent.addView(button, params);
        return button;
    }

    private CheckBox switchRow(LinearLayout parent, String text, String key, boolean def) {
        CheckBox box = new CheckBox(this);
        box.setText(text);
        box.setTextSize(13.5f);
        box.setTextColor(INK);
        box.setChecked(Store.getBool(this, key, def));
        box.setPadding(0, dp(6), 0, dp(6));
        parent.addView(box);
        return box;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(CREAM);
        root.setPadding(dp(18), dp(20), dp(18), dp(28));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView cat = new ImageView(this);
        int catRes = getResources().getIdentifier("ic_launcher", "drawable", getPackageName());
        if (catRes != 0) cat.setImageResource(catRes);
        cat.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cat.setBackground(roundedStroke(0xFFFFE3B3, 0xFFF59E0B, 16));
        cat.setClipToOutline(true);
        header.addView(cat, new LinearLayout.LayoutParams(dp(64), dp(64)));

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.setPadding(dp(12), 0, 0, 0);
        TextView title = new TextView(this);
        title.setText("基米翻译");
        title.setTextSize(24f);
        title.setTextColor(ORANGE_DEEP);
        title.getPaint().setFakeBoldText(true);
        titleBox.addView(title);
        TextView subtitle = new TextView(this);
        subtitle.setText("看屏幕 · 只翻新内容 · 中英对照");
        subtitle.setTextSize(12.5f);
        subtitle.setTextColor(INK_SOFT);
        titleBox.addView(subtitle);
        header.addView(titleBox);
        root.addView(header);

        android.animation.ValueAnimator blink = android.animation.ValueAnimator.ofPropertyValuesHolder(
            android.animation.PropertyValuesHolder.ofKeyframe("scaleY",
                android.animation.Keyframe.ofFloat(0f, 1f),
                android.animation.Keyframe.ofFloat(0.84f, 1f),
                android.animation.Keyframe.ofFloat(0.89f, 0.16f),
                android.animation.Keyframe.ofFloat(0.94f, 1f),
                android.animation.Keyframe.ofFloat(1f, 1f)));
        blink.setDuration(4300);
        blink.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        blink.setInterpolator(new android.view.animation.LinearInterpolator());
        blink.setTarget(cat);
        blink.start();

        statusView = new TextView(this);
        statusView.setTextSize(13.5f);
        statusView.setPadding(dp(14), dp(11), dp(14), dp(11));
        statusView.setBackground(rounded(0xFFFFF0D9, 14));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = dp(14);
        root.addView(statusView, statusParams);

        LinearLayout modelCard = card(root, "模型");
        keyInput = field(modelCard, "API key（DeepSeek 的 sk-…）", Store.get(this, Store.KEY_API, ""), true);
        baseInput = field(modelCard, "接口地址（OpenAI 兼容）", Store.get(this, Store.KEY_BASE, Store.DEFAULT_BASE), false);
        modelInput = field(modelCard, "模型名", Store.get(this, Store.KEY_MODEL, Store.DEFAULT_MODEL), false);
        intervalInput = field(modelCard, "自动跟读间隔（毫秒，最小 3000）",
            String.valueOf(Store.getInt(this, Store.KEY_INTERVAL, 8000)), false);
        intervalInput.setInputType(InputType.TYPE_CLASS_NUMBER);

        LinearLayout promptCard = card(root, "翻译偏好");
        String savedPrompt = Store.get(this, Store.KEY_PROMPT, "");
        if (savedPrompt == null || savedPrompt.trim().isEmpty()) savedPrompt = Prompt.DEFAULT_SYSTEM;
        promptInput = field(promptCard, "系统提示词（留空用内置）", savedPrompt, false);
        promptInput.setSingleLine(false);
        promptInput.setMinLines(5);
        promptInput.setMaxLines(9);
        promptInput.setGravity(Gravity.TOP | Gravity.START);

        glossaryInput = field(promptCard, "自定义术语（每行一条：policy 策略）", Store.get(this, Store.KEY_GLOSSARY, ""), false);
        glossaryInput.setSingleLine(false);
        glossaryInput.setMinLines(3);
        glossaryInput.setGravity(Gravity.TOP | Gravity.START);

        LinearLayout dirCard = card(root, "翻译方向");
        dirSpinner = new android.widget.Spinner(this);
        android.widget.ArrayAdapter<String> dirAdapter = new android.widget.ArrayAdapter<>(
            this, android.R.layout.simple_spinner_item,
            new String[] {"自动识别（英文→中文 / 中文→英文）", "英译中", "中译英"});
        dirAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        dirSpinner.setAdapter(dirAdapter);
        String savedDir = Store.get(this, Store.KEY_DIRECTION, "auto");
        dirSpinner.setSelection("en2zh".equals(savedDir) ? 1 : ("zh2en".equals(savedDir) ? 2 : 0));
        dirCard.addView(dirSpinner, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout switchCard = card(root, "运行方式");
        streamBox = switchRow(switchCard, "流式输出（边生成边显示）", Store.KEY_STREAM, true);
        thinkBox = switchRow(switchCard, "精译模式（开思考，慢一些但更准）", Store.KEY_THINK, false);
        catBox = switchRow(switchCard, "哈基米风格浮窗（橙色猫猫配色）", Store.KEY_CAT, false);

        Button save = pill(root, "保存设置", true);
        save.setOnClickListener(v -> save());

        Button accessibility = pill(root, "权限设置（开启无障碍服务）", false);
        accessibility.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Throwable error) {
                Toast.makeText(this, "打不开系统设置：" + error.getMessage(), Toast.LENGTH_LONG).show();
            }
        });

        Button show = pill(root, "显示 / 收回浮窗", false);
        show.setOnClickListener(v -> {
            CaptureService service = CaptureService.get();
            if (service == null) {
                Toast.makeText(this, "无障碍服务还没开", Toast.LENGTH_SHORT).show();
                return;
            }
            service.showOverlay();
        });

        TextView note = new TextView(this);
        note.setText("🐱 使用顺序\n"
            + "1. 系统设置 → 无障碍 → 已下载的服务里打开「基米翻译 · 读屏服务」；\n"
            + "2. 浮窗出现后：点「框选」圈一块翻，或点「自动」开始整屏跟读（再点停止）；\n"
            + "3. 浮窗可拖动，「不透明度」调背景深浅，「清空」清掉已翻记录与去重；\n"
            + "4. API key 只存在本机 App 私有目录，不会发到你填的接口以外的地方。");
        note.setTextSize(12f);
        note.setTextColor(INK_SOFT);
        note.setLineSpacing(dp(3), 1f);
        note.setPadding(0, dp(18), 0, 0);
        root.addView(note);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(CREAM);
        scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
    }

    private void save() {
        String key = keyInput.getText().toString().trim();
        String base = baseInput.getText().toString().trim();
        String model = modelInput.getText().toString().trim();
        int interval;
        try {
            interval = Integer.parseInt(intervalInput.getText().toString().trim());
        } catch (Throwable error) {
            interval = 8000;
        }
        if (base.isEmpty()) base = Store.DEFAULT_BASE;
        if (model.isEmpty()) model = Store.DEFAULT_MODEL;
        if (interval < 3000) interval = 3000;
        Store.put(this, Store.KEY_API, key);
        Store.put(this, Store.KEY_BASE, base);
        Store.put(this, Store.KEY_MODEL, model);
        Store.putInt(this, Store.KEY_INTERVAL, interval);
        intervalInput.setText(String.valueOf(interval));
        Store.putBool(this, Store.KEY_STREAM, streamBox == null || streamBox.isChecked());
        Store.putBool(this, Store.KEY_THINK, thinkBox != null && thinkBox.isChecked());
        Store.putBool(this, Store.KEY_CAT, catBox != null && catBox.isChecked());
        int dirIndex = dirSpinner == null ? 0 : dirSpinner.getSelectedItemPosition();
        Store.put(this, Store.KEY_DIRECTION, dirIndex == 1 ? "en2zh" : (dirIndex == 2 ? "zh2en" : "auto"));
        String promptText = promptInput.getText().toString().trim();
        Store.put(this, Store.KEY_PROMPT, promptText.equals(Prompt.DEFAULT_SYSTEM.trim()) ? "" : promptText);
        Store.put(this, Store.KEY_GLOSSARY, glossaryInput.getText().toString().trim());
        Toast.makeText(this, "已保存 🐾", Toast.LENGTH_SHORT).show();
        refresh();
    }

    private void refresh() {
        boolean enabled = isAccessibilityEnabled();
        CaptureService service = CaptureService.get();
        if (service != null) {
            statusView.setText("● 读屏服务已连接，浮窗应该在屏幕上了");
            statusView.setTextColor(0xFF15803D);
            statusView.setBackground(rounded(0xFFE7F7EC, 14));
        } else if (enabled) {
            statusView.setText("● 服务已开启但还没连接：把无障碍开关关掉再打开一次");
            statusView.setTextColor(0xFFB45309);
            statusView.setBackground(rounded(0xFFFFF2DB, 14));
        } else {
            statusView.setText("● 读屏服务未开启：点下面「权限设置（开启无障碍服务）」→ 找「基米翻译 · 读屏服务」→ 打开");
            statusView.setTextColor(0xFFB91C1C);
            statusView.setBackground(rounded(0xFFFDECEC, 14));
        }
        statusView.setGravity(Gravity.START);
    }

    private boolean isAccessibilityEnabled() {
        try {
            String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return enabled != null && enabled.contains(getPackageName());
        } catch (Throwable error) {
            return false;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }
}
