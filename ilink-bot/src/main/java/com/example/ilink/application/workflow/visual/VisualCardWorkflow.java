package com.example.ilink.application.workflow.visual;

import com.example.ilink.application.messaging.ReplyChannel;
import com.example.ilink.application.messaging.AgentContext;

import com.example.ilink.application.conversation.PlanSessionStore;
import com.example.ilink.capabilities.calendar.CalendarService;
import com.example.ilink.capabilities.express.ExpressService;
import com.example.ilink.capabilities.food.FoodOrderService;
import com.example.ilink.capabilities.food.LinkShortener;
import com.example.ilink.capabilities.mail.QqMailService;
import com.example.ilink.capabilities.media.MediaKnowledgeResponse;
import com.example.ilink.capabilities.media.MediaKnowledgeService;
import com.example.ilink.capabilities.planning.TodoService;
import com.example.ilink.capabilities.travel.AmapService;
import com.example.ilink.capabilities.visual.FunInteractionService;
import com.example.ilink.capabilities.visual.PlanSpreadsheetService;
import com.example.ilink.capabilities.visual.VisualCard;
import com.example.ilink.capabilities.visual.VisualCardFactory;
import com.example.ilink.capabilities.web.BilibiliSearchService;
import com.example.ilink.capabilities.web.NewsSearchService;
import com.example.ilink.capabilities.calendar.CalendarEvent;
import com.example.ilink.capabilities.planning.PlanTask;
import com.example.ilink.capabilities.web.SearchResult;
import com.example.ilink.capabilities.planning.TaskPlan;
import com.example.ilink.capabilities.planning.TodoItem;
import com.example.ilink.application.tooling.ToolContext;
import com.example.ilink.application.tooling.ToolManager;
import com.example.ilink.application.tooling.ToolResult;
import com.example.ilink.capabilities.express.ExpressTool;
import com.google.gson.JsonObject;

import java.awt.Color;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** 处理显式视觉卡片、计划导出和轻量互动命令。 */
public final class VisualCardWorkflow {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("M月d日 HH:mm");
    private final VisualDeckSender sender;
    private final VisualCardFactory cards;
    private final PlanSessionStore plans;
    private final CalendarService calendarService;
    private final TodoService todoService;
    private final ToolManager toolManager;
    private final FoodOrderService foodOrderService;
    private final QqMailService mailService;
    private final NewsSearchService newsService;
    private final MediaKnowledgeService mediaService;
    private final BilibiliSearchService bilibiliService;
    private final AmapService amapService;
    private final PlanSpreadsheetService spreadsheetService = new PlanSpreadsheetService();
    private final FunInteractionService fun = new FunInteractionService();

    public VisualCardWorkflow(VisualDeckSender sender, VisualCardFactory cards,
                              PlanSessionStore plans, CalendarService calendarService,
                              TodoService todoService, ToolManager toolManager,
                              FoodOrderService foodOrderService, QqMailService mailService,
                              NewsSearchService newsService, MediaKnowledgeService mediaService,
                              BilibiliSearchService bilibiliService, AmapService amapService) {
        this.sender = sender;
        this.cards = cards;
        this.plans = plans;
        this.calendarService = calendarService;
        this.todoService = todoService;
        this.toolManager = toolManager;
        this.foodOrderService = foodOrderService;
        this.mailService = mailService;
        this.newsService = newsService;
        this.mediaService = mediaService;
        this.bilibiliService = bilibiliService;
        this.amapService = amapService;
    }

    public boolean hasPending(String userId) {
        return fun.hasPending(userId);
    }

    public boolean acceptsPendingReply(String userId, String text) {
        return fun.acceptsPendingReply(userId, text);
    }

    public void clearPending(String userId) {
        fun.clearPending(userId);
    }

    public boolean handle(AgentContext context, String rawText) throws Exception {
        return handle(context.replyChannel(), context.principalId(), rawText);
    }

    public boolean handle(ReplyChannel client, String userId, String rawText) throws Exception {
        String text = rawText == null ? "" : rawText.trim();
        FunInteractionService.Response interaction = fun.handle(userId, text);
        if (interaction != null) {
            if (interaction.selection()) {
                sender.send(client, userId, interaction.cards(), interaction.text());
            } else {
                sender.sendText(client, userId, interaction.text());
            }
            return true;
        }

        if (text.matches("^(卡片菜单|视觉菜单|功能卡片)$")) {
            sendMenu(client, userId);
            return true;
        }
        if (text.matches(".*(今天|今日).*(安排|日程).*(卡片).*|.*(卡片).*(今天|今日).*(安排|日程).*")) {
            sendToday(client, userId);
            return true;
        }
        if (text.matches(".*(本月|这个月|月历).*(卡片|月历).*")) {
            sendMonth(client, userId);
            return true;
        }
        if (text.matches(".*(计划|学习进度|完成进度).*(卡片).*|.*(卡片).*(计划|学习进度|完成进度).*")) {
            sendPlan(client, userId);
            return true;
        }
        if (text.matches("^(导出计划表|导出我的计划|计划导出|生成计划表格)$")) {
            exportPlan(client, userId);
            return true;
        }
        if (text.matches("^(生成)?(计划)?完成证书$")) {
            sendCertificate(client, userId);
            return true;
        }
        if (text.contains("快递卡片") || text.contains("物流卡片")) {
            sendExpress(client, userId, text);
            return true;
        }
        if (text.contains("邮箱卡片") || text.contains("邮件卡片")) {
            sendMail(client, userId, text);
            return true;
        }
        if (text.contains("新闻卡片") || text.contains("资讯卡片")) {
            sendNews(client, userId, text);
            return true;
        }
        if (text.matches(".*(影视|动漫|音乐|歌曲|歌词|哔哩哔哩|B站)卡片.*")) {
            sendMedia(client, userId, text);
            return true;
        }
        if (text.contains("外卖卡片") || text.contains("点餐卡片")) {
            sendFood(client, userId, text);
            return true;
        }
        if (text.contains("导航卡片")) {
            sendNavigation(client, userId, text);
            return true;
        }
        return false;
    }

    public void sendTextResult(AgentContext context, String title,
                               String subtitle, String text) throws Exception {
        sendTextResult(context.replyChannel(), context.principalId(), title, subtitle, text);
    }

    public void sendTextResult(ReplyChannel client, String userId, String title,
                               String subtitle, String body) throws Exception {
        sender.sendText(client, userId, body);
    }

    public void sendSearchResults(AgentContext context, String title,
                                  List<SearchResult> results, String textFallback) throws Exception {
        sendSearchResults(context.replyChannel(), context.principalId(), title, results, textFallback);
    }

    public void sendSearchResults(ReplyChannel client, String userId, String title,
                                  List<SearchResult> results, String textFallback) throws Exception {
        if (results == null || results.isEmpty()) {
            sender.sendText(client, userId, textFallback);
            return;
        }
        if (results.size() == 1) {
            sender.sendText(client, userId, textFallback);
            return;
        }
        List<VisualCard> deck = new ArrayList<>();
        for (SearchResult result : results.stream().limit(5).toList()) {
            String body = result.summary().isBlank() ? "扫码查看完整内容。" : result.summary();
            deck.add(cards.linkCard(result.title(), result.source(), body,
                    result.url(), new Color(48, 103, 166)));
        }
        sender.send(client, userId, deck, textFallback);
    }

    public void sendBilibiliResults(AgentContext context,
                                    List<SearchResult> results,
                                    String textFallback) throws Exception {
        sendBilibiliResults(context.replyChannel(), context.principalId(), results, textFallback);
    }

    public void sendBilibiliResults(ReplyChannel client, String userId,
                                     List<SearchResult> results, String textFallback) throws Exception {
        if (results == null || results.size() <= 1) {
            sender.sendText(client, userId, textFallback);
            return;
        }
        List<VisualCard> deck = new ArrayList<>();
        for (SearchResult result : results.stream().limit(5).toList()) {
            deck.add(cards.linkCard(result.title(), "哔哩哔哩",
                    "扫码打开相关视频或官方搜索结果。", result.url(), new Color(190, 88, 120)));
        }
        sender.send(client, userId, deck, textFallback);
    }

    public void sendMediaResults(AgentContext context, String title,
                                 String description,
                                 List<SearchResult> videos,
                                 String textFallback) throws Exception {
        sendMediaResults(context.replyChannel(), context.principalId(), title,
                description, videos, textFallback);
    }

    public void sendMediaResults(ReplyChannel client, String userId, String title,
                                 String knowledgeText, List<SearchResult> videos,
                                 String textFallback) throws Exception {
        if (videos == null || videos.size() <= 1) {
            sender.sendText(client, userId, textFallback);
            return;
        }
        sender.sendText(client, userId, knowledgeText);
        List<VisualCard> deck = new ArrayList<>();
        for (SearchResult video : videos.stream().limit(3).toList()) {
            deck.add(cards.linkCard(video.title(), "哔哩哔哩",
                    "扫码打开相关视频或官方搜索结果。", video.url(), new Color(190, 88, 120)));
        }
        if (deck.isEmpty()) return;
        sender.send(client, userId, deck, textFallback);
    }

    public void sendFoodOrder(AgentContext context, String restaurants,
                              String textFallback) throws Exception {
        sendFoodOrder(context.replyChannel(), context.principalId(), restaurants, textFallback);
    }

    public void sendFoodOrder(ReplyChannel client, String userId, String restaurants,
                              String textFallback) throws Exception {
        List<String> names = List.of(restaurants.split("[,，、]")).stream().map(String::trim)
                .filter(value -> !value.isBlank()).limit(2).toList();
        List<VisualCard> deck = new ArrayList<>();
        for (String name : names) {
            deck.add(cards.linkCard(name + " · 美团", "外卖搜索入口", "扫码后在美团中搜索“" + name + "”。",
                    LinkShortener.meituanUrl(name), new Color(238, 181, 38)));
            deck.add(cards.linkCard(name + " · 饿了么", "外卖搜索入口", "扫码后在饿了么中搜索“" + name + "”。",
                    LinkShortener.elemeUrl(name), new Color(38, 126, 218)));
        }
        sender.send(client, userId, deck, textFallback);
    }

    private void sendMenu(ReplyChannel client, String userId) throws Exception {
        List<VisualCard> deck = List.of(
                VisualCard.of("生活卡片", "日常信息一眼看清",
                        "今日安排卡片\n生成本月月历\n快递卡片 + 单号\n邮箱卡片\n新闻卡片 + 主题"),
                VisualCard.of("行动卡片", "链接统一改为二维码",
                        "计划卡片\n导出计划表\n生成完成证书\n导航卡片 从A到B\n外卖卡片 + 餐厅"),
                VisualCard.of("轻松一下", "直接发送命令即可开始",
                        "今日盲盒\n每日答题\n猜歌\n成语接龙\n分支故事"));
        sender.send(client, userId, deck, "卡片菜单：\n今日安排、月历、计划、快递、邮箱、新闻、媒体、导航、外卖、计划导出和互动游戏。");
    }

    private void sendToday(ReplyChannel client, String userId) throws Exception {
        LocalDate today = LocalDate.now();
        StringBuilder body = new StringBuilder();
        List<CalendarEvent> events = calendarService.eventsForDay(userId, today);
        body.append("日历\n");
        if (events.isEmpty()) body.append("今天没有日历安排。\n");
        for (CalendarEvent event : events) {
            body.append("- ").append(event.startAt().toLocalTime().withSecond(0).withNano(0))
                    .append(' ').append(event.title()).append('\n');
        }
        body.append("\n待办\n");
        List<TodoItem> todos = todoService.activeItems(userId);
        if (todos.isEmpty()) body.append("没有待完成事项。\n");
        for (TodoItem todo : todos.stream().limit(8).toList()) {
            body.append("- ").append(todo.title());
            if (todo.dueAt() != null) body.append("（").append(todo.dueAt().format(TIME)).append("）");
            body.append('\n');
        }
        TaskPlan plan = plans.get(userId);
        if (plan != null) {
            body.append("\n计划\n");
            plan.tasks().stream().filter(task -> today.toString().equals(task.scheduledDate()))
                    .forEach(task -> body.append("- ").append(task.title()).append("（")
                            .append(task.estimatedMinutes()).append("分钟）\n"));
        }
        sendTextDeck(client, userId, "今日安排", today.toString(), body.toString());
    }

    private void sendMonth(ReplyChannel client, String userId) throws Exception {
        YearMonth month = YearMonth.now();
        List<CalendarEvent> events = calendarService.eventsBetween(userId, month.atDay(1), month.atEndOfMonth());
        StringBuilder body = new StringBuilder("本月共有 ").append(events.size()).append(" 项日历安排。\n\n");
        if (events.isEmpty()) body.append("本月还没有日历安排。 ");
        LocalDate current = null;
        for (CalendarEvent event : events) {
            if (!event.startAt().toLocalDate().equals(current)) {
                current = event.startAt().toLocalDate();
                body.append(current.format(DateTimeFormatter.ofPattern("M月d日 EEEE"))).append('\n');
            }
            body.append("  ").append(event.startAt().toLocalTime().withSecond(0).withNano(0))
                    .append("  ").append(event.title()).append('\n');
        }
        sendTextDeck(client, userId, "本月月历", month.toString(), body.toString());
    }

    private void sendPlan(ReplyChannel client, String userId) throws Exception {
        TaskPlan plan = plans.get(userId);
        if (plan == null) {
            sendTextDeck(client, userId, "计划进度", "尚未创建计划", "先告诉我一个想完成的目标和预计时间，我会帮你拆成计划。 ");
            return;
        }
        String body = plan.toDisplayText();
        sendTextDeck(client, userId, "计划进度", progress(plan), body);
    }

    private void exportPlan(ReplyChannel client, String userId) throws Exception {
        TaskPlan plan = plans.get(userId);
        if (plan == null) {
            client.sendText(userId, "你还没有可以导出的计划。 ");
            return;
        }
        byte[] bytes = spreadsheetService.export(plan);
        client.sendFile(userId, bytes, "我的计划.xlsx", "计划 Excel 表格");
    }

    private void sendCertificate(ReplyChannel client, String userId) throws Exception {
        TaskPlan plan = plans.get(userId);
        if (plan == null) {
            sendTextDeck(client, userId, "完成证书", "还没有计划", "完成一份计划后，我会为你生成专属图片证书。 ");
            return;
        }
        long completed = plan.completedCount();
        boolean done = !plan.tasks().isEmpty() && completed == plan.tasks().size();
        String body = done
                ? "兹记录你已完成计划：\n\n" + plan.goal() + "\n\n共完成 " + completed + " 项任务。每一步认真投入，都值得被看见。"
                : "当前计划还在进行中。\n\n" + plan.goal() + "\n\n已完成 " + completed + " / " + plan.tasks().size() + " 项。完成全部任务后即可生成正式证书。";
        sendTextDeck(client, userId, done ? "计划完成证书" : "计划进度证明", progress(plan), body);
    }

    private void sendExpress(ReplyChannel client, String userId, String text) throws Exception {
        String trackingNo = ExpressService.extractTrackingNo(text);
        if (trackingNo.isBlank()) {
            sendTextDeck(client, userId, "快递卡片", "缺少单号", "请在“快递卡片”后面加上快递单号。 ");
            return;
        }
        JsonObject args = new JsonObject();
        args.addProperty("tracking_no", trackingNo);
        ToolResult result = toolManager.execute(ExpressTool.NAME, new ToolContext(userId), args);
        sendTextDeck(client, userId, "物流进度", trackingNo, result.output());
    }

    private void sendMail(ReplyChannel client, String userId, String text) throws Exception {
        String action = text.contains("重要") ? "important" : "unread";
        String result = mailService.query(userId, action, "");
        sendTextDeck(client, userId, "QQ 邮箱", "近期邮件摘要", result);
    }

    private void sendNews(ReplyChannel client, String userId, String text) throws Exception {
        String query = text.replaceAll("(新闻|资讯)卡片", "").replaceAll("^[：:，, ]+", "").trim();
        if (query.isBlank()) query = "今日最新新闻";
        List<SearchResult> results = newsService.search(query + " when:1d", 3);
        if (results.isEmpty()) {
            sendTextDeck(client, userId, "实时新闻", query, "暂时没有找到可靠的实时结果。 ");
            return;
        }
        if (results.size() == 1) {
            sender.sendText(client, userId, newsText(results));
            return;
        }
        List<VisualCard> deck = new ArrayList<>();
        for (SearchResult result : results) {
            String body = result.summary().isBlank() ? "扫码查看完整报道。" : result.summary();
            deck.add(cards.linkCard(result.title(), result.source(), body, result.url(), new Color(48, 103, 166)));
        }
        sender.send(client, userId, deck, newsText(results));
    }

    private void sendMedia(ReplyChannel client, String userId, String text) throws Exception {
        String category = text.matches(".*(动漫|动画|番剧).*" ) ? "anime"
                : text.contains("歌词") ? "lyrics" : "music";
        String query = text.replaceAll("(影视|动漫|音乐|歌曲|歌词|哔哩哔哩|B站)卡片", "")
                .replaceAll("^[：:，, ]+", "").trim();
        if (query.isBlank()) query = category.equals("anime") ? "热门动漫" : "热门音乐";
        MediaKnowledgeResponse knowledge = mediaService.lookup(query, category, text);
        List<SearchResult> videos = bilibiliService.search(knowledge.bilibiliQuery(), knowledge.bilibiliCategory());
        String fallback = knowledge.text() + "\n\n" + bilibiliService.formatReply(videos);
        if (videos.size() <= 1) {
            sender.sendText(client, userId, fallback);
            return;
        }
        sender.sendText(client, userId, knowledge.text());
        List<VisualCard> deck = new ArrayList<>();
        for (SearchResult video : videos) {
            deck.add(cards.linkCard(video.title(), "哔哩哔哩", "扫码打开相关视频或搜索结果。",
                    video.url(), new Color(190, 88, 120)));
        }
        sender.send(client, userId, deck, fallback);
    }

    private void sendFood(ReplyChannel client, String userId, String text) throws Exception {
        String query = text.replaceAll("(外卖|点餐)卡片", "").replaceAll("^[：:，, ]+", "").trim();
        if (query.isBlank()) {
            sendTextDeck(client, userId, "外卖卡片", "缺少餐厅", "请告诉我餐厅或菜品名称，例如：外卖卡片 外婆家。 ");
            return;
        }
        sendFoodOrder(client, userId, query, foodOrderService.generateLinks(query));
    }

    private void sendNavigation(ReplyChannel client, String userId, String text) throws Exception {
        String route = text.replace("导航卡片", "").replaceAll("^[：:，, ]+", "").trim();
        int fromIndex = route.indexOf('从');
        int toIndex = route.lastIndexOf('到');
        if (fromIndex < 0 || toIndex <= fromIndex + 1 || toIndex >= route.length() - 1) {
            sendTextDeck(client, userId, "导航卡片", "请补充路线", "请使用：导航卡片 从起点途经某地到终点。\n途经点可以省略。 ");
            return;
        }
        String middle = route.substring(fromIndex + 1, toIndex).trim();
        String destination = route.substring(toIndex + 1).trim();
        String[] viaParts = middle.split("途经", 2);
        List<String> names = new ArrayList<>();
        names.add(viaParts[0].trim());
        if (viaParts.length > 1 && !viaParts[1].isBlank()) names.add(viaParts[1].trim());
        names.add(destination);
        List<AmapService.Place> itinerary = new ArrayList<>();
        for (String name : names) {
            AmapService.Place place = amapService.geocode(name);
            if (place == null) {
                sendTextDeck(client, userId, "导航卡片", "地点未找到", "没有找到地点：“" + name + "”。请补充城市或更完整的名称。 ");
                return;
            }
            itinerary.add(place);
        }
        String url = amapService.navigationUrl(itinerary);
        StringBuilder body = new StringBuilder("路线\n");
        for (int index = 0; index < itinerary.size(); index++) {
            body.append(index + 1).append(". ").append(itinerary.get(index).name()).append('\n');
        }
        body.append("\n扫码后在高德地图中打开完整导航。 ");
        sender.sendText(client, userId, body + "\n" + url);
    }

    private void sendTextDeck(ReplyChannel client, String userId, String title,
                              String subtitle, String body) throws Exception {
        sender.sendText(client, userId, body);
    }

    private String progress(TaskPlan plan) {
        int total = plan.tasks().size();
        int percent = total == 0 ? 0 : (int) Math.round(plan.completedCount() * 100.0 / total);
        return "已完成 " + plan.completedCount() + " / " + total + "（" + percent + "%）";
    }

    private String newsText(List<SearchResult> results) {
        StringBuilder text = new StringBuilder("实时新闻：\n");
        for (SearchResult result : results) text.append("- ").append(result.title()).append('\n').append(result.url()).append('\n');
        return text.toString().trim();
    }
}
