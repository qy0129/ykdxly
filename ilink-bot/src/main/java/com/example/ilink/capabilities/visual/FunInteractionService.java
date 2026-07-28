package com.example.ilink.capabilities.visual;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 维护答题、猜歌、成语接龙和分支故事的轻量会话状态。 */
public final class FunInteractionService {

    private static final List<Quiz> QUIZZES = List.of(
            new Quiz("线性代数中，单位矩阵乘任意同阶矩阵的结果是？", "A. 原矩阵\nB. 零矩阵\nC. 转置矩阵", "A"),
            new Quiz("HTTP 状态码 404 通常表示什么？", "A. 请求成功\nB. 资源未找到\nC. 服务永久关闭", "B"),
            new Quiz("一年中通常天数最少的是哪个月？", "A. 2月\nB. 4月\nC. 6月", "A"));
    private static final List<GuessSong> SONGS = List.of(
            new GuessSong("周杰伦歌曲：校园、屋顶、等待下课", "等你下课"),
            new GuessSong("周杰伦歌曲：中国风、青花、烟雨", "青花瓷"),
            new GuessSong("周杰伦歌曲：晴天、青春、故事的小黄花", "晴天"));
    private static final List<String> BOXES = List.of(
            "给未来的自己写一句十个字以内的话。",
            "拍下今天最喜欢的一束光。",
            "用十五分钟整理一个一直拖着的小角落。",
            "随机听一首从未听过的歌，并记下第一感受。",
            "给很久没联系的人发一句真诚问候。"
    );

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    public boolean hasPending(String userId) {
        return pending.containsKey(userId);
    }

    public Response handle(String userId, String text) {
        String value = text == null ? "" : text.trim();
        Pending state = pending.get(userId);
        if (state != null) return continuePending(userId, value, state);

        if (value.equals("今日盲盒") || value.equals("每日盲盒")) {
            int index = Math.floorMod((userId + LocalDate.now()).hashCode(), BOXES.size());
            return response("今日盲盒", "只在今天有效的小任务", BOXES.get(index), false);
        }
        if (value.equals("每日答题") || value.equals("今日答题")) {
            Quiz quiz = QUIZZES.get(Math.floorMod(LocalDate.now().getDayOfYear(), QUIZZES.size()));
            pending.put(userId, new Pending("quiz", quiz.answer(), 0));
            return response("每日答题", "回复 A、B 或 C", quiz.question() + "\n\n" + quiz.options(), true);
        }
        if (value.equals("猜歌") || value.equals("开始猜歌")) {
            GuessSong song = SONGS.get(Math.floorMod((userId + LocalDate.now()).hashCode(), SONGS.size()));
            pending.put(userId, new Pending("song", song.answer(), 0));
            return response("猜歌时间", "回复歌曲名", song.clue(), false);
        }
        if (value.equals("成语接龙") || value.equals("开始成语接龙")) {
            pending.put(userId, new Pending("idiom", "意", 0));
            return response("成语接龙", "从“意”字开始", "我先来：一心一意\n请回复一个“意”字开头的四字成语。回复“结束”可退出。 ", false);
        }
        if (value.equals("分支故事") || value.equals("开始分支故事")) {
            pending.put(userId, new Pending("story", "", 1));
            return story(1);
        }
        return null;
    }

    private Response continuePending(String userId, String value, Pending state) {
        if (value.equals("取消") || value.equals("结束")) {
            pending.remove(userId);
            return response("互动已结束", "随时可以再来", "这段小游戏先停在这里。", false);
        }
        return switch (state.type()) {
            case "quiz" -> finishAnswer(userId, value.equalsIgnoreCase(state.answer()), state.answer());
            case "song" -> finishAnswer(userId, normalize(value).contains(normalize(state.answer())), state.answer());
            case "idiom" -> continueIdiom(userId, value, state.answer());
            case "story" -> continueStory(userId, value, state.step());
            default -> null;
        };
    }

    private Response finishAnswer(String userId, boolean correct, String answer) {
        pending.remove(userId);
        return response(correct ? "回答正确" : "差一点点", "答案：" + answer,
                correct ? "答得很稳，今天的知识点已经拿下。" : "没关系，记住这一次，下次就会更快。", false);
    }

    private Response continueIdiom(String userId, String value, String expected) {
        if (value.length() < 4 || !value.startsWith(expected)) {
            return response("还接不上", "需要“" + expected + "”字开头", "请回复一个四字成语，或者回复“结束”。", false);
        }
        String next = value.substring(value.length() - 1);
        pending.put(userId, new Pending("idiom", next, 0));
        return response("接得漂亮", "下一轮从“" + next + "”开始", "你刚才接的是：“" + value + "”。\n继续回复一个“" + next + "”字开头的四字成语。 ", false);
    }

    private Response continueStory(String userId, String value, int step) {
        if (!value.matches("[12]")) {
            return response("请选择剧情", "回复 1 或 2", "故事正在等你的决定。回复“结束”可以退出。 ", true);
        }
        int next = "1".equals(value) ? step * 2 : step * 2 + 1;
        if (next >= 4) {
            pending.remove(userId);
            String ending = next % 2 == 0
                    ? "你推开门，看见清晨第一束光落在旧书页上，旅程有了答案。"
                    : "你沿着声音走到天台，整座城市正好亮起灯，秘密变成了新的开始。";
            return response("故事结局", "你的选择完成了故事", ending, false);
        }
        pending.put(userId, new Pending("story", "", next));
        return story(next);
    }

    private Response story(int step) {
        String body = step == 1
                ? "深夜，你收到一张没有署名的旧车票。\n\n1. 去车站看看\n2. 先查车票背面的地址"
                : step == 2
                ? "末班车停在空站台，车门里放着一本写有你名字的书。\n\n1. 上车\n2. 打开书"
                : "地址指向一栋旧图书馆，二楼传来轻轻的音乐。\n\n1. 推开阅览室的门\n2. 顺着音乐上楼";
        return response("分支故事", "回复 1 或 2 推进剧情", body, true);
    }

    private Response response(String title, String subtitle, String body, boolean selection) {
        return new Response(List.of(VisualCard.of(title, subtitle, body)),
                title + "\n" + subtitle + "\n" + body, selection);
    }

    private String normalize(String value) {
        return value.replaceAll("[\\s《》<>，,。.!！?？]", "").toLowerCase();
    }

    private record Pending(String type, String answer, int step) { }
    private record Quiz(String question, String options, String answer) { }
    private record GuessSong(String clue, String answer) { }
    public record Response(List<VisualCard> cards, String text, boolean selection) { }
}
