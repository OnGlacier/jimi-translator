package com.rltranslator.overlay;

import java.util.List;

/** 提示词：极简、命令式。模型爱写分析，所以只给最少的说明和最硬的要求。 */
final class Prompt {
    private Prompt() {}

    static final String DEFAULT_SYSTEM =
        "你是专业的英文学术文献翻译引擎：理工科论文（强化学习、机器人、控制、计算机视觉、"
            + "生物医学等方向）都能准确翻译。你只输出 JSON，绝不输出分析、计划或解释。";

    static final String REPAIR =
        "你上一条回复是分析，不合格。不要分析、不要解释、不要列提纲。"
            + "现在只输出 JSON：{\"segments\":[{\"en\":\"英文原文\",\"zh\":\"中文译文\"}]}，"
            + "第一个字符必须是 {。没有可翻的新内容就输出 {\"segments\":[]}。";

    static String user(List<String> known, String glossary, boolean regionMode) {
        StringBuilder sb = new StringBuilder();
        if (regionMode) {
            sb.append("一共两张图：第一张是你选中的区域（放大图，**只翻这一张**），第二张是整屏截图（只作上下文参考，不要翻它）。\n");
            sb.append("边缘可能截到半行字或半个单词：按能读到的内容照常翻完，不要跳过，也不要说无法翻译。\n");
        } else {
            sb.append("把这张截图里新出现的英文正文逐句译成中文，只输出下面这个 JSON，不要任何解释：\n");
        }
        sb.append("{\"segments\":[{\"i\":1,\"en\":\"英文原文\",\"zh\":\"中文译文\"}]}\n\n");
        sb.append("规则：\n");
        sb.append("1) 忽略状态栏、页码、工具栏、浮动窗口、中文界面文字；公式、变量名、算法名（PPO/SAC/MuJoCo 等）保持原样；\n");
        sb.append("1b) 截图边缘可能截到半行字或半个单词：按能读到的内容照常翻译，不要因为不完整就跳过整段；\n");
        sb.append("2) i 从 1 开始连续递增，严格对应截图从上到下的段序；截图里最上面那段正文必须是 i=1，不许跳段；"
            + "含公式、只有两行的段落也要翻，公式本身保持原样；\n");
        sb.append("3) 与下面「已翻译」重复的半句一律不要；没有新内容就输出 {\"segments\":[]}；\n");
        sb.append("4) 这一屏有多少段就输出多少段（通常 20–40 条），绝对不要只翻一部分；宁可拆细也别漏；en 只放对应那一句英文原文；zh 不超过 240 字；\n");
        sb.append("5) 译文里不要出现 $ 或 \\ 开头的 LaTeX 标记，公式写成普通字符（例如 μθ(·|s)、Δt_i=t_{i+1}-t_i）；\n");
        sb.append("6) 翻译要书面、忠实、不扩写，按原文所属学科使用规范译名。常见术语（出现则优先采用）：policy 策略、value function 价值函数、policy gradient 策略梯度、");
        sb.append("actor-critic 演员-评论家、reward 奖励、return 回报、discount factor 折扣因子、trajectory 轨迹、");
        sb.append("on-policy 同策略、off-policy 异策略、advantage 优势、entropy 熵、sample efficiency 样本效率、");
        sb.append("exploration 探索、manipulation 操作、locomotion 运动控制、generalization 泛化、ablation 消融。\n\n");
        if (known.isEmpty()) {
            sb.append("已翻译：〈无〉");
        } else {
            sb.append("已翻译（不要重复）：\n");
            for (int i = 0; i < known.size(); i++) {
                sb.append("- ").append(known.get(i)).append('\n');
            }
        }
        if (glossary != null && !glossary.trim().isEmpty()) {
            sb.append("\n额外术语（必须优先遵守）：\n").append(glossary.trim()).append('\n');
        }
        return sb.toString();
    }
}
