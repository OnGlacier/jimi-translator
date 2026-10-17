package com.jimi.desktop;

import java.util.List;

/** 提示词：和 Android 版同一套规则，只是输出契约统一成 src / dst。 */
final class Prompt {

    static final String DEFAULT_SYSTEM =
        "你是专业的学术与技术文本翻译引擎：英译中、中译英都能准确翻译（理工科论文、技术文档、"
            + "网页与软件界面都适用）。你只输出 JSON，绝不输出分析、计划或解释。";

    private Prompt() {}

    static String system(String custom) {
        if (custom != null && !custom.trim().isEmpty()) return custom.trim();
        return DEFAULT_SYSTEM;
    }

    static String user(String direction, List<String> known, String glossary, boolean regionMode) {
        StringBuilder sb = new StringBuilder();
        String task;
        if ("en2zh".equals(direction)) {
            task = "把截图里新出现的英文正文逐句译成中文";
        } else if ("zh2en".equals(direction)) {
            task = "把截图里新出现的中文正文逐句译成英文";
        } else {
            task = "把截图里新出现的正文翻译成另一种语言（英文译成中文、中文译成英文，已经是目标语言的文字保持原样）";
        }
        if (regionMode) {
            sb.append("一共两张图：第一张是你选中的区域（放大图，只翻这一张），第二张是整屏截图（只作上下文参考，不要翻它）。\n");
            sb.append("边缘可能截到半行字或半个单词：按能读到的内容照常翻完，不要跳过，也不要说无法翻译。\n");
        }
        sb.append(task).append("，只输出下面这个 JSON，不要任何解释：\n");
        sb.append("{\"segments\":[{\"i\":1,\"src\":\"原文\",\"dst\":\"译文\"}]}\n\n");
        sb.append("规则：\n");
        sb.append("1) 忽略状态栏、页码、工具栏、浮动窗口、界面本身的文字；公式、变量名、算法名（PPO/SAC/MuJoCo 等）保持原样；\n");
        sb.append("2) i 从 1 开始连续递增，严格对应截图从上到下的段序；最上面那段正文必须是 i=1，不许跳段；\n");
        sb.append("3) 与下面「已翻译」重复的半句一律不要；没有新内容就输出 {\"segments\":[]}；\n");
        sb.append("4) 这一屏有多少段就输出多少段（通常 20-40 条），绝对不要只翻一部分；宁可拆细也别漏；src 只放对应那一句原文；dst 不超过 240 字；\n");
        sb.append("5) 译文里不要出现 $ 或反斜杠开头的 LaTeX 标记，公式写成普通字符（例如 μθ(·|s)）；\n");
        sb.append("6) 翻译要书面、忠实、不扩写，按原文所属学科使用规范译名。\n\n");
        if (glossary != null && !glossary.trim().isEmpty()) {
            sb.append("额外术语（必须优先遵守）：\n").append(glossary.trim()).append("\n\n");
        }
        if (known == null || known.isEmpty()) {
            sb.append("已翻译：〈无〉");
        } else {
            sb.append("已翻译（不要重复）：\n");
            for (String item : known) {
                sb.append("- ").append(item).append('\n');
            }
        }
        return sb.toString();
    }
}
