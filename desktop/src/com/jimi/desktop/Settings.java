package com.jimi.desktop;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/** 设置窗口：和手机端一致的橙色哈基米配色。 */
final class Settings {

    private static final Color CREAM = new Color(0xFF, 0xF7, 0xEC);
    private static final Color INK = new Color(0x3D, 0x2B, 0x1F);
    private static final Color INK_SOFT = new Color(0x8C, 0x6F, 0x55);
    private static final Color ORANGE = new Color(0xF5, 0x9E, 0x0B);
    private static final Color ORANGE_DEEP = new Color(0xB4, 0x53, 0x09);

    private final Config config;
    private Controller controller;

    private final JTextField apiKey = new JPasswordField(48);
    private final JTextField baseUrl = new JTextField();
    private final JTextField model = new JTextField();
    private final JTextField interval = new JTextField();
    private final JComboBox<String> direction = new JComboBox<>(new String[] {"双向", "英译中", "中译英"});
    private final JCheckBox stream = new JCheckBox("流式输出（边生成边显示）");
    private final JCheckBox think = new JCheckBox("精译模式（开思考，慢一些但更准）");
    private final JTextArea prompt = new JTextArea(5, 40);
    private final JTextArea glossary = new JTextArea(3, 40);
    private final JLabel status = new JLabel(" ");

    Settings(Config config) {
        this.config = config;
    }

    void show() {
        JFrame frame = new JFrame("基米翻译");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setMinimumSize(new Dimension(640, 720));

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(CREAM);
        root.setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));

        // 头部
        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        header.setOpaque(false);
        JLabel cat = new JLabel("🐱");
        cat.setFont(cat.getFont().deriveFont(34f));
        header.add(cat);
        JPanel titles = new JPanel();
        titles.setOpaque(false);
        titles.setLayout(new javax.swing.BoxLayout(titles, javax.swing.BoxLayout.Y_AXIS));
        JLabel title = new JLabel("基米翻译");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 24f));
        title.setForeground(ORANGE_DEEP);
        JLabel subtitle = new JLabel("看屏幕 · 只翻新内容 · 英中双向");
        subtitle.setForeground(INK_SOFT);
        titles.add(title);
        titles.add(subtitle);
        header.add(titles);

        // 表单
        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 0, 4, 8);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;

        int row = 0;
        row = addRow(form, c, row, "API key", apiKey);
        row = addRow(form, c, row, "接口地址", baseUrl);
        row = addRow(form, c, row, "模型名", model);
        row = addRow(form, c, row, "自动间隔（毫秒）", interval);

        c.gridx = 0;
        c.gridy = row;
        c.gridwidth = 1;
        c.weightx = 0;
        form.add(label("翻译方向"), c);
        c.gridx = 1;
        c.weightx = 1;
        form.add(direction, c);
        row++;

        c.gridx = 1;
        c.gridy = row++;
        form.add(stream, c);
        c.gridy = row++;
        form.add(think, c);

        c.gridx = 0;
        c.gridy = row;
        c.weightx = 0;
        form.add(label("系统提示词（留空用内置）"), c);
        row++;
        c.gridx = 0;
        c.gridy = row++;
        c.gridwidth = 2;
        c.weightx = 1;
        c.fill = GridBagConstraints.BOTH;
        prompt.setLineWrap(true);
        prompt.setWrapStyleWord(true);
        form.add(new JScrollPane(prompt), c);

        c.gridx = 0;
        c.gridy = row++;
        c.gridwidth = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        form.add(label("自定义术语（每行一条：policy 策略）"), c);
        c.gridx = 0;
        c.gridy = row++;
        c.gridwidth = 2;
        c.fill = GridBagConstraints.BOTH;
        glossary.setLineWrap(true);
        glossary.setWrapStyleWord(true);
        form.add(new JScrollPane(glossary), c);

        // 按钮
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        buttons.setOpaque(false);
        JButton save = button("保存设置", true);
        save.addActionListener(event -> save());
        JButton showcase = button("显示 / 收回浮窗", false);
        showcase.addActionListener(event -> {
            save();
            if (controller == null) controller = new Controller(config);
            controller.toggleOverlay();
        });
        JButton auto = button("开始 / 停止自动", false);
        auto.addActionListener(event -> {
            save();
            if (controller == null) controller = new Controller(config);
            controller.showOverlay();
            controller.onToggleAuto();
        });
        buttons.add(save);
        buttons.add(showcase);
        buttons.add(auto);

        status.setForeground(INK_SOFT);
        status.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));

        JPanel body = new JPanel(new BorderLayout());
        body.setOpaque(false);
        body.add(form, BorderLayout.NORTH);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setOpaque(false);
        bottom.add(buttons, BorderLayout.NORTH);
        bottom.add(status, BorderLayout.SOUTH);

        root.add(header, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(body);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        root.add(scroll, BorderLayout.CENTER);
        root.add(bottom, BorderLayout.SOUTH);

        frame.setContentPane(root);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        load();
    }

    private int addRow(JPanel form, GridBagConstraints c, int row, String label, JTextField field) {
        c.gridx = 0;
        c.gridy = row;
        c.gridwidth = 1;
        c.weightx = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        form.add(label(label), c);
        c.gridx = 1;
        c.weightx = 1;
        form.add(field, c);
        return row + 1;
    }

    private JLabel label(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(INK_SOFT);
        return label;
    }

    private JButton button(String text, boolean primary) {
        JButton button = new JButton(text);
        button.setFocusPainted(false);
        if (primary) {
            button.setBackground(ORANGE);
            button.setForeground(Color.WHITE);
        } else {
            button.setBackground(CREAM);
            button.setForeground(ORANGE_DEEP);
        }
        return button;
    }

    private void load() {
        apiKey.setText(config.apiKey);
        baseUrl.setText(config.baseUrl);
        model.setText(config.model);
        interval.setText(String.valueOf(config.intervalMs));
        direction.setSelectedIndex("en2zh".equals(config.direction) ? 1 : ("zh2en".equals(config.direction) ? 2 : 0));
        stream.setSelected(config.stream);
        think.setSelected(config.think);
        prompt.setText(config.systemPrompt == null || config.systemPrompt.trim().isEmpty()
            ? Prompt.DEFAULT_SYSTEM : config.systemPrompt);
        glossary.setText(config.glossary);
    }

    private void save() {
        config.apiKey = apiKey.getText().trim();
        config.baseUrl = baseUrl.getText().trim().isEmpty() ? Config.DEFAULT_BASE : baseUrl.getText().trim();
        config.model = model.getText().trim().isEmpty() ? Config.DEFAULT_MODEL : model.getText().trim();
        try {
            config.intervalMs = Math.max(3000, Integer.parseInt(interval.getText().trim()));
        } catch (Throwable error) {
            config.intervalMs = 8000;
        }
        interval.setText(String.valueOf(config.intervalMs));
        config.direction = direction.getSelectedIndex() == 1 ? "en2zh"
            : (direction.getSelectedIndex() == 2 ? "zh2en" : "auto");
        config.stream = stream.isSelected();
        config.think = think.isSelected();
        String promptText = prompt.getText().trim();
        config.systemPrompt = promptText.equals(Prompt.DEFAULT_SYSTEM.trim()) ? "" : promptText;
        config.glossary = glossary.getText().trim();
        config.save();
        status.setText("已保存到 " + Config.file());
        if (controller != null) {
            controller.overlay().setDirection(config.direction);
        }
    }

    static void run(Config config) {
        SwingUtilities.invokeLater(() -> new Settings(config).show());
    }

    static void error(String message) {
        JOptionPane.showMessageDialog(null, message, "基米翻译", JOptionPane.WARNING_MESSAGE);
    }
}
