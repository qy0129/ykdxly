package com.example.ilink.capabilities.radar;

import com.example.ilink.capabilities.web.SearchResult;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 兴趣订阅、新闻去重、视频连续发送与亮点提炼的应用服务。 */
public final class InterestRadarService {
    private static final Pattern VIDEO_INSIGHT = Pattern.compile(
            ".*(?:提炼|总结|分析).*视频.*(?:亮点|重点|内容|观点).*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern VIDEO_INDEX = Pattern.compile("第\\s*(\\d+)\\s*个");
    private static final Pattern CONTINUE = Pattern.compile(
            "^(?:接着发|继续发|继续|再来(?:几个|一些)?|下一批|接着发视频)[。！! ]*$");
    private static final Pattern DIGEST_HOURS = Pattern.compile(
            ".*每\\s*(\\d{1,2})\\s*小时(?:推送|推荐)(?:一次)?.*");
    private static final int NEWS_LIMIT = 5;
    private static final int DEFAULT_PROACTIVE_MIN_SCORE = 60;
    private static final int VIDEO_BATCH_SIZE = 3;
    private static final int VIDEO_CANDIDATE_LIMIT = 12;
    private static final int DISCOVERY_TOPIC_LIMIT = 6;
    private static final int DISCOVERY_RESULT_LIMIT = 5;
    private static final int BREAKING_MIN_SCORE = 85;
    private static final Set<String> TRUSTED_SOURCE_MARKERS = Set.of(
            "官方", "政府", "新华社", "央视", "人民网", "github", "openai", "anthropic",
            "google", "microsoft", "阿里云", "腾讯云", "字节跳动");

    private final InterestRadarStore store;
    private final NewsProvider newsProvider;
    private final VideoProvider videoProvider;
    private final MaterialAnalyzer analyzer;
    private final int proactiveMinScore;
    private final GoalProvider goalProvider;
    private final VideoMaterialProvider videoMaterialProvider;
    private final WebProvider webProvider;
    private final PlanTopicExtractor planTopicExtractor = new PlanTopicExtractor();

    public InterestRadarService(InterestRadarStore store, NewsProvider newsProvider,
                                VideoProvider videoProvider, MaterialAnalyzer analyzer) {
        this(store, newsProvider, videoProvider, analyzer, DEFAULT_PROACTIVE_MIN_SCORE,
                userId -> List.of(), InterestRadarService::descriptionMaterial,
                (query, limit) -> List.of());
    }

    public InterestRadarService(InterestRadarStore store, NewsProvider newsProvider,
                                VideoProvider videoProvider, MaterialAnalyzer analyzer,
                                int proactiveMinScore) {
        this(store, newsProvider, videoProvider, analyzer, proactiveMinScore,
                userId -> List.of(), InterestRadarService::descriptionMaterial,
                (query, limit) -> List.of());
    }

    public InterestRadarService(InterestRadarStore store, NewsProvider newsProvider,
                                VideoProvider videoProvider, MaterialAnalyzer analyzer,
                                int proactiveMinScore, GoalProvider goalProvider) {
        this(store, newsProvider, videoProvider, analyzer, proactiveMinScore, goalProvider,
                InterestRadarService::descriptionMaterial, (query, limit) -> List.of());
    }

    public InterestRadarService(InterestRadarStore store, NewsProvider newsProvider,
                                VideoProvider videoProvider, MaterialAnalyzer analyzer,
                                int proactiveMinScore, GoalProvider goalProvider,
                                VideoMaterialProvider videoMaterialProvider) {
        this(store, newsProvider, videoProvider, analyzer, proactiveMinScore, goalProvider,
                videoMaterialProvider, (query, limit) -> List.of());
    }

    public InterestRadarService(InterestRadarStore store, NewsProvider newsProvider,
                                VideoProvider videoProvider, MaterialAnalyzer analyzer,
                                int proactiveMinScore, GoalProvider goalProvider,
                                VideoMaterialProvider videoMaterialProvider, WebProvider webProvider) {
        this.store = store;
        this.newsProvider = newsProvider;
        this.videoProvider = videoProvider;
        this.analyzer = analyzer;
        this.proactiveMinScore = Math.max(0, Math.min(100, proactiveMinScore));
        this.goalProvider = goalProvider;
        this.videoMaterialProvider = videoMaterialProvider;
        this.webProvider = webProvider;
    }

    /** 返回 null 表示不是兴趣雷达命令。 */
    public String handleCommand(String userId, String text) throws Exception {
        String value = text == null ? "" : text.trim();
        if (value.isBlank()) return null;

        String settingsReply = handleSettingsCommand(userId, value);
        if (settingsReply != null) return settingsReply;
        if (value.matches("^(?:请)?同步(?:一下)?我的计划[。！!]?$")) return syncPlanReply(userId);
        if (value.matches("^(?:查看)?自动关注(?:主题)?[。！!]?$")) {
            syncPlanTopics(userId);
            return formatAutoTopics(userId);
        }
        if (isTopicList(value)) {
            syncPlanTopics(userId);
            return formatTopics(userId);
        }
        if (value.startsWith("删除关注") || value.startsWith("不再关注")) {
            String selector = value.replaceFirst("^(?:删除关注|不再关注)", "")
                    .replaceAll("[。！!]$", "").trim();
            String removed = store.removeTopic(userId, selector);
            return removed.isBlank() ? "没有找到这个关注主题。" : "已删除关注：" + removed + "。";
        }
        if (value.startsWith("暂停关注")) return toggleTopic(userId, value.substring(4).trim(), false);
        if (value.startsWith("恢复关注")) return toggleTopic(userId, value.substring(4).trim(), true);
        if (isTopicCreation(value)) return createTopics(userId, value);

        if (CONTINUE.matcher(value).matches()) return nextVideos(userId);
        if (VIDEO_INSIGHT.matcher(value).matches()) return videoInsight(userId, videoIndex(value));
        if (isVideoRequest(value)) {
            syncPlanTopics(userId);
            return startVideos(userId, extractVideoQuery(userId, value));
        }
        if (isNewsRequest(value)) return latestNews(userId, false);
        return null;
    }

    /** 定时扫描时只返回此前未推送的新闻。 */
    public String collectNewsUpdates(String userId) {
        try {
            return latestNews(userId, true);
        } catch (Exception error) {
            System.err.println("[兴趣雷达] 定时新闻扫描失败: " + error.getMessage());
            return "";
        }
    }

    /** 到达独立的视频推送周期时轮换主题，并只发送此前未发过的链接。 */
    public String collectVideoUpdates(String userId, Duration minimumInterval) {
        LocalDateTime lastPush = store.lastVideoPushAt(userId);
        Duration interval = minimumInterval == null || minimumInterval.isNegative()
                ? Duration.ofHours(24) : minimumInterval;
        if (lastPush != null && Duration.between(lastPush, LocalDateTime.now()).compareTo(interval) < 0) {
            return "";
        }
        InterestTopic topic = store.nextVideoTopic(userId, enabledTopics(userId));
        if (topic == null) return "";
        try {
            String reply = startVideos(userId, topic.name(), true);
            store.markVideoPushed(userId, LocalDateTime.now());
            return reply.isBlank() || reply.startsWith("暂时没有找到")
                    ? "" : "定期视频推荐：\n" + reply;
        } catch (Exception error) {
            System.err.println("[兴趣雷达] 定期视频检索失败 " + topic.name() + ": " + error.getMessage());
            return "";
        }
    }

    /** 执行一次发现与推送判断；调度器可以每 30 分钟调用，普通摘要仍按用户的小时设置发送。 */
    public List<String> collectScheduledUpdates(String userId, LocalDateTime now) {
        LocalDateTime current = now == null ? LocalDateTime.now() : now;
        RadarPreferences preferences = store.preferences(userId);
        if (!preferences.enabled()) return List.of();
        syncPlanTopics(userId);
        List<InterestTopic> topics = enabledTopics(userId);
        if (topics.isEmpty()) return List.of();

        List<InterestTopic> discoveryTopics = store.nextDiscoveryTopics(
                userId, topics, DISCOVERY_TOPIC_LIMIT);
        store.saveCandidates(userId, discoverContent(userId, discoveryTopics, current));
        if (store.dailyPushCount(userId, current) >= preferences.dailyMaxPushes()) return List.of();
        List<RadarContentItem> eligible = eligibleCandidates(userId, preferences);
        if (eligible.isEmpty()) return List.of();

        if (preferences.breakingEnabled()) {
            RadarContentItem breaking = eligible.stream()
                    .filter(item -> item.type() == RadarContentType.NEWS && item.score() >= BREAKING_MIN_SCORE)
                    .findFirst().orElse(null);
            if (breaking != null) {
                return List.of(deliverDigest(userId, List.of(breaking), current, true));
            }
        }
        if (preferences.quietAt(current.toLocalTime()) || !digestDue(userId, current, preferences)) {
            return List.of();
        }
        List<RadarContentItem> selected = selectDigestItems(eligible, preferences.maxItems());
        return selected.isEmpty() ? List.of()
                : List.of(deliverDigest(userId, selected, current, false));
    }

    private List<RadarContentItem> discoverContent(String userId, List<InterestTopic> topics,
                                                   LocalDateTime now) {
        List<RadarContentItem> discovered = new ArrayList<>();
        Set<String> seenVideos = store.seenVideoUrls(userId);
        for (InterestTopic topic : topics) {
            try {
                for (SearchResult result : newsProvider.search(topic.name(), DISCOVERY_RESULT_LIMIT)) {
                    addCandidate(discovered, topic.name(), RadarContentType.NEWS, result, now);
                }
            } catch (Exception error) {
                System.err.println("[兴趣雷达] 新闻发现失败 " + topic.name() + ": " + error.getMessage());
            }
            try {
                for (SearchResult result : webProvider.search(
                        topic.name() + " 官方 文档 最新", DISCOVERY_RESULT_LIMIT)) {
                    addCandidate(discovered, topic.name(), RadarContentType.WEB_PAGE, result, now);
                }
            } catch (Exception error) {
                System.err.println("[兴趣雷达] 网页发现失败 " + topic.name() + ": " + error.getMessage());
            }
            try {
                for (SearchResult result : videoProvider.search(topic.name(), DISCOVERY_RESULT_LIMIT)) {
                    if (!seenVideos.contains(result.url())) {
                        addCandidate(discovered, topic.name(), RadarContentType.VIDEO, result, now);
                    }
                }
            } catch (Exception error) {
                System.err.println("[兴趣雷达] 视频发现失败 " + topic.name() + ": " + error.getMessage());
            }
        }
        return discovered;
    }

    private void addCandidate(List<RadarContentItem> output, String topic, RadarContentType type,
                              SearchResult result, LocalDateTime now) {
        if (result == null || result.url() == null || result.url().isBlank()
                || result.title() == null || result.title().isBlank()) return;
        NewsAssessment assessment = assessNews(topic, result);
        int adjusted = switch (type) {
            case NEWS -> assessment.score();
            case WEB_PAGE -> Math.min(100, assessment.score() + 5);
            case VIDEO -> Math.min(100, assessment.score());
        };
        String key = eventKey(result);
        output.add(new RadarContentItem(topic, type, result.title(), result.summary(), result.source(),
                result.publishedAt(), result.url(), key, adjusted, assessment.rationale(),
                trustedSource(result), now));
    }

    private List<RadarContentItem> eligibleCandidates(String userId, RadarPreferences preferences) {
        Set<RadarContentType> allowed = Set.copyOf(preferences.contentTypes());
        return store.pendingCandidates(userId).stream()
                .filter(item -> item.score() >= proactiveMinScore)
                .filter(item -> allowed.contains(item.type()))
                .filter(item -> !preferences.officialOnly() || item.officialSignal())
                .sorted(Comparator.comparingInt(RadarContentItem::score).reversed()
                        .thenComparing(RadarContentItem::discoveredAt, Comparator.reverseOrder()))
                .toList();
    }

    private boolean digestDue(String userId, LocalDateTime now, RadarPreferences preferences) {
        LocalDateTime last = store.lastDigestAt(userId);
        if (last == null) return true;
        Duration elapsed = Duration.between(last, now);
        return !elapsed.isNegative() && elapsed.compareTo(Duration.ofHours(preferences.digestHours())) >= 0;
    }

    private List<RadarContentItem> selectDigestItems(List<RadarContentItem> eligible, int maxItems) {
        LinkedHashMap<String, RadarContentItem> selected = new LinkedHashMap<>();
        for (RadarContentType type : List.of(
                RadarContentType.NEWS, RadarContentType.WEB_PAGE, RadarContentType.VIDEO)) {
            eligible.stream().filter(item -> item.type() == type).findFirst()
                    .ifPresent(item -> selected.put(item.eventKey(), item));
            if (selected.size() >= maxItems) return List.copyOf(selected.values());
        }
        for (RadarContentItem item : eligible) {
            if (item.type() == RadarContentType.VIDEO
                    && selected.values().stream().anyMatch(
                    value -> value.type() == RadarContentType.VIDEO)) continue;
            selected.putIfAbsent(item.eventKey(), item);
            if (selected.size() >= maxItems) break;
        }
        return List.copyOf(selected.values());
    }

    private String deliverDigest(String userId, List<RadarContentItem> selected,
                                 LocalDateTime now, boolean breaking) {
        prepareVideoSession(userId, selected);
        store.markNewsSeen(userId, selected.stream()
                .filter(item -> item.type() == RadarContentType.NEWS)
                .map(RadarContentItem::eventKey).toList());
        store.markVideosSeen(userId, selected.stream()
                .filter(item -> item.type() == RadarContentType.VIDEO)
                .map(RadarContentItem::url).toList());
        store.markContentPushed(userId, selected.stream().map(RadarContentItem::eventKey).toList(), now);

        StringBuilder reply = new StringBuilder(breaking ? "重大动态提醒：\n" : "与你当前计划相关的三小时摘要：\n");
        for (int index = 0; index < selected.size(); index++) {
            RadarContentItem item = selected.get(index);
            reply.append('\n').append(index + 1).append(". ").append(typeName(item.type()))
                    .append("｜").append(item.title()).append('\n');
            if (!item.summary().isBlank()) reply.append(shorten(item.summary(), 160)).append('\n');
            reply.append("对应主题：").append(item.topic()).append('\n')
                    .append("推荐依据：").append(item.rationale())
                    .append("（信号分 ").append(item.score()).append("/100）\n");
            if (!item.source().isBlank()) reply.append("来源：").append(item.source()).append('\n');
            reply.append(item.url()).append('\n');
        }
        String impact = goalImpactItems(userId, selected);
        if (!impact.isBlank()) {
            reply.append("\n与当前目标的关系：\n").append(impact)
                    .append("\n以上是建议，只有你确认后才会转为执行任务。\n");
        }
        if (!selected.stream().filter(item -> item.type() == RadarContentType.VIDEO).toList().isEmpty()) {
            reply.append("\n回复“接着发”继续视频列表，或说“提炼第1个视频的亮点”。");
        }
        reply.append("\n说明：信号分只用于排序，不代表内容事实已经核验。");
        return reply.toString().trim();
    }

    private void prepareVideoSession(String userId, List<RadarContentItem> selected) {
        List<RadarContentItem> videos = selected.stream()
                .filter(item -> item.type() == RadarContentType.VIDEO).toList();
        if (videos.isEmpty()) return;
        RadarContentItem first = videos.getFirst();
        LinkedHashMap<String, SearchResult> results = new LinkedHashMap<>();
        for (RadarContentItem item : videos) results.put(item.url(), toSearchResult(item));
        for (RadarContentItem item : store.pendingCandidates(userId)) {
            if (item.type() == RadarContentType.VIDEO && item.topic().equals(first.topic())) {
                results.putIfAbsent(item.url(), toSearchResult(item));
            }
        }
        List<SearchResult> values = List.copyOf(results.values());
        List<SearchResult> lastBatch = videos.stream().map(InterestRadarService::toSearchResult).toList();
        store.saveVideoSession(userId, new VideoFeedSession(first.topic(), values,
                lastBatch.size(), lastBatch, LocalDateTime.now()));
    }

    private String goalImpactItems(String userId, List<RadarContentItem> items) {
        List<String> goals = safeGoals(userId);
        if (goals.isEmpty() || !items.stream().anyMatch(item -> goals.stream()
                .map(InterestRadarService::normalizeComparable)
                .anyMatch(goal -> goal.contains(normalizeComparable(item.topic()))))) return "";
        StringBuilder material = new StringBuilder("当前未结束目标：\n");
        goals.stream().limit(5).forEach(goal -> material.append("- ").append(goal).append('\n'));
        material.append("\n候选内容：\n");
        items.forEach(item -> material.append("- ").append(item.title()).append(" | ")
                .append(item.summary()).append(" | ").append(item.url()).append('\n'));
        String analyzed = analyzer.analyze(userId,
                "判断候选内容是否会实质影响当前目标。区分材料事实与推断，最多给出 3 条具体建议。"
                        + "不得修改目标、创建任务或声称已经执行。没有明确影响时只回复‘无明确影响’。",
                material.toString());
        return analyzed == null || analyzed.isBlank() || analyzed.trim().equals("无明确影响")
                ? "" : analyzed.trim();
    }

    private List<String> safeGoals(String userId) {
        try {
            List<String> values = goalProvider.activeGoals(userId);
            return values == null ? List.of() : values.stream()
                    .filter(value -> value != null && !value.isBlank()).distinct().toList();
        } catch (RuntimeException error) {
            System.err.println("[兴趣雷达] 读取计划失败: " + error.getMessage());
            return List.of();
        }
    }

    private boolean syncPlanTopics(String userId) {
        PlanTopicExtractor.ExtractedPlan extracted = planTopicExtractor.extract(safeGoals(userId));
        return store.syncPlanTopics(userId, extracted.fingerprint(), extracted.topics());
    }

    private String syncPlanReply(String userId) {
        boolean changed = syncPlanTopics(userId);
        List<String> automatic = store.topics(userId).stream()
                .filter(topic -> topic.origin() != RadarTopicOrigin.EXPLICIT_USER)
                .map(InterestTopic::name).toList();
        if (automatic.isEmpty()) return "当前计划中没有提取到适合联网关注的主题。";
        return (changed ? "已根据当前计划更新自动关注：" : "计划没有变化，当前自动关注：")
                + String.join("、", automatic) + "。";
    }

    private String formatAutoTopics(String userId) {
        List<InterestTopic> automatic = store.topics(userId).stream()
                .filter(topic -> topic.origin() != RadarTopicOrigin.EXPLICIT_USER).toList();
        if (automatic.isEmpty()) return "当前没有计划自动关注主题。";
        StringBuilder reply = new StringBuilder("计划自动关注：\n");
        automatic.forEach(topic -> reply.append("- ").append(topic.name())
                .append(" [").append(topic.priority()).append("]\n"));
        return reply.toString().trim();
    }

    public boolean hasEnabledTopics(String userId) {
        return store.topics(userId).stream().anyMatch(InterestTopic::enabled);
    }

    private String createTopics(String userId, String text) {
        List<String> names = parseTopicNames(text);
        if (names.isEmpty()) return "请告诉我需要关注的主题，例如：我关注 Java Agent、Qwen 和 Personal Executive Agent。";
        List<InterestTopic> added = store.addTopics(userId, names);
        if (added.isEmpty()) return "这些主题已经在关注列表中。";
        return "已开始关注：" + String.join("、", added.stream().map(InterestTopic::name).toList())
                + "。你可以说“关注的最新新闻”或“给我发相关视频”。";
    }

    private String handleSettingsCommand(String userId, String value) {
        RadarPreferences current = store.preferences(userId);
        Matcher hours = DIGEST_HOURS.matcher(value);
        if (hours.matches()) {
            int requested = number(hours.group(1), 3);
            RadarPreferences updated = current.withDigestHours(requested);
            store.savePreferences(userId, updated);
            return "已调整为每 " + updated.digestHours() + " 小时最多推送一批；没有新内容时保持静默。";
        }
        if (value.matches("^(?:请)?暂停(?:主动)?推送[。！!]?$")) {
            store.savePreferences(userId, current.withEnabled(false));
            return "已暂停主动推送。计划和已保存的关注主题不会删除。";
        }
        if (value.matches("^(?:请)?恢复(?:主动)?推送[。！!]?$")) {
            store.savePreferences(userId, current.withEnabled(true));
            return "已恢复主动推送，当前间隔为 " + current.digestHours() + " 小时。";
        }
        if (value.matches("^只看视频[。！!]?$")) {
            store.savePreferences(userId, current.withContentTypes(List.of(RadarContentType.VIDEO)));
            return "已调整为只推送视频。";
        }
        if (value.matches("^只看新闻和官方文档[。！!]?$")) {
            store.savePreferences(userId, current
                    .withContentTypes(List.of(RadarContentType.NEWS, RadarContentType.WEB_PAGE))
                    .withOfficialOnly(true));
            return "已调整为只推送新闻和带官方来源信号的网页。";
        }
        if (value.matches("^只看官方来源[。！!]?$")) {
            store.savePreferences(userId, current.withOfficialOnly(true));
            return "已启用官方来源优先过滤。来源标识用于筛选，仍建议打开原文核实。";
        }
        if (value.matches("^(?:恢复全部来源|全部类型都推送)[。！!]?$")) {
            store.savePreferences(userId, new RadarPreferences(current.enabled(), current.digestHours(),
                    current.quietStart(), current.quietEnd(), current.maxItems(), current.dailyMaxPushes(),
                    List.of(RadarContentType.NEWS, RadarContentType.WEB_PAGE, RadarContentType.VIDEO),
                    false, current.breakingEnabled()));
            return "已恢复新闻、网页和视频的综合推送。";
        }
        if (value.matches("^少发一点[。！!]?$")) {
            RadarPreferences updated = current.withMaxItems(current.maxItems() - 1);
            store.savePreferences(userId, updated);
            return "已减少为每批最多 " + updated.maxItems() + " 条。";
        }
        if (value.matches("^多发一些[。！!]?$")) {
            RadarPreferences updated = current.withMaxItems(current.maxItems() + 1);
            store.savePreferences(userId, updated);
            return "已增加为每批最多 " + updated.maxItems() + " 条。";
        }
        if (value.matches("^重大消息立即告诉我[。！!]?$")) {
            store.savePreferences(userId, current.withBreakingEnabled(true));
            return "已开启重大消息即时提醒，普通内容仍按摘要间隔发送。";
        }
        if (value.matches("^关闭重大消息即时推送[。！!]?$")) {
            store.savePreferences(userId, current.withBreakingEnabled(false));
            return "已关闭重大消息即时提醒，所有内容按摘要间隔发送。";
        }
        if (value.matches("^(?:查看)?推送设置[。！!]?$")) return formatPreferences(current);
        return null;
    }

    private String formatPreferences(RadarPreferences preferences) {
        return "当前推送设置：\n"
                + "- 状态：" + (preferences.enabled() ? "已开启" : "已暂停") + "\n"
                + "- 摘要间隔：每 " + preferences.digestHours() + " 小时\n"
                + "- 每批上限：" + preferences.maxItems() + " 条\n"
                + "- 每日上限：" + preferences.dailyMaxPushes() + " 批\n"
                + "- 静默时段：" + preferences.quietStart() + " 至 " + preferences.quietEnd() + "\n"
                + "- 内容类型：" + String.join("、", preferences.contentTypes().stream()
                .map(InterestRadarService::typeName).toList()) + "\n"
                + "- 官方来源过滤：" + (preferences.officialOnly() ? "开启" : "关闭") + "\n"
                + "- 重大消息即时提醒：" + (preferences.breakingEnabled() ? "开启" : "关闭");
    }

    private String toggleTopic(String userId, String selector, boolean enabled) {
        if (selector.isBlank()) return enabled ? "请告诉我要恢复哪个主题。" : "请告诉我要暂停哪个主题。";
        boolean changed = store.setEnabled(userId, selector, enabled);
        return changed ? (enabled ? "已恢复关注：" : "已暂停关注：") + selector
                : "没有找到这个关注主题。";
    }

    private String formatTopics(String userId) {
        List<InterestTopic> topics = store.topics(userId);
        if (topics.isEmpty()) return "目前还没有关注主题。可以说：我关注 Java Agent、Qwen。";
        StringBuilder reply = new StringBuilder("当前关注主题：\n");
        for (InterestTopic topic : topics) {
            reply.append(topic.enabled() ? "- " : "- [已暂停] ").append(topic.name())
                    .append(topic.origin() == RadarTopicOrigin.EXPLICIT_USER ? " [手动]" : " [计划自动]")
                    .append('\n');
        }
        return reply.toString().trim();
    }

    private String latestNews(String userId, boolean onlyUnseen) throws Exception {
        syncPlanTopics(userId);
        List<InterestTopic> topics = enabledTopics(userId);
        if (topics.isEmpty()) return onlyUnseen ? "" : "请先设置关注主题，例如：我关注 Java Agent 和 Qwen。";
        Set<String> seen = store.seenNewsKeys(userId);
        Map<String, NewsCandidate> unique = new LinkedHashMap<>();
        List<String> failedTopics = new ArrayList<>();
        for (InterestTopic topic : topics) {
            try {
                for (SearchResult result : newsProvider.search(topic.name(), NEWS_LIMIT)) {
                    String key = eventKey(result);
                    if (onlyUnseen && seen.contains(key)) continue;
                    NewsAssessment assessment = assessNews(topic.name(), result);
                    NewsCandidate candidate = new NewsCandidate(topic.name(), result, key, assessment);
                    unique.merge(key, candidate, (left, right) ->
                            left.assessment().score() >= right.assessment().score() ? left : right);
                }
            } catch (Exception error) {
                failedTopics.add(topic.name());
                System.err.println("[兴趣雷达] 主题检索失败 " + topic.name() + ": " + error.getMessage());
            }
        }
        List<NewsCandidate> selected = unique.values().stream()
                .filter(item -> !onlyUnseen || item.assessment().score() >= proactiveMinScore)
                .sorted(Comparator.comparingInt((NewsCandidate item) -> item.assessment().score()).reversed())
                .limit(NEWS_LIMIT).toList();
        if (selected.isEmpty()) {
            if (onlyUnseen) return "";
            return failedTopics.size() == topics.size()
                    ? "新闻源当前不可用，本次没有生成可能失真的结果。"
                    : "目前没有检索到新的可靠新闻。";
        }
        store.markNewsSeen(userId, selected.stream().map(NewsCandidate::key).toList());

        StringBuilder reply = new StringBuilder(onlyUnseen ? "你关注的主题有新消息：\n" : "你关注的最近消息：\n");
        for (int index = 0; index < selected.size(); index++) {
            NewsCandidate item = selected.get(index);
            SearchResult result = item.result();
            reply.append(index + 1).append(". [").append(item.topic()).append("] ")
                    .append(result.title()).append('\n');
            if (result.summary() != null && !result.summary().isBlank()) {
                reply.append(shorten(result.summary(), 140)).append('\n');
            }
            if (result.source() != null && !result.source().isBlank()) {
                reply.append("来源：").append(result.source());
                if (result.publishedAt() != null && !result.publishedAt().isBlank()) {
                    reply.append(" · ").append(result.publishedAt());
                }
                reply.append('\n');
            }
            reply.append("筛选：").append(item.assessment().rationale())
                    .append("（信号分 ").append(item.assessment().score()).append("/100）\n");
            reply.append(result.url());
            if (index < selected.size() - 1) reply.append("\n\n");
        }
        if (!onlyUnseen && !failedTopics.isEmpty()) {
            reply.append("\n\n部分主题检索失败：").append(String.join("、", failedTopics))
                    .append("。以上结果并不完整。");
        }
        reply.append("\n\n说明：信号分只用于排序，不代表新闻事实已经核验。");
        String impact = goalImpact(userId, selected);
        if (!impact.isBlank()) {
            reply.append("\n\n与当前目标的关系：\n").append(impact)
                    .append("\n说明：以上是建议，只有你确认后才会转为执行任务。");
        }
        return reply.toString();
    }

    private String goalImpact(String userId, List<NewsCandidate> news) {
        List<String> goals;
        try {
            goals = goalProvider.activeGoals(userId);
        } catch (RuntimeException error) {
            System.err.println("[兴趣雷达] 读取当前目标失败: " + error.getMessage());
            return "";
        }
        if (goals == null || goals.isEmpty() || !hasPotentialGoalOverlap(goals, news)) return "";

        StringBuilder material = new StringBuilder("当前未结束目标：\n");
        goals.stream().filter(goal -> goal != null && !goal.isBlank()).limit(5)
                .forEach(goal -> material.append("- ").append(goal.trim()).append('\n'));
        material.append("\n本次新闻：\n");
        news.stream().limit(5).forEach(item -> material.append("- ")
                .append(item.result().title()).append(" | ")
                .append(item.result().summary() == null ? "" : shorten(item.result().summary(), 180))
                .append(" | ").append(item.result().url()).append('\n'));
        String analyzed = analyzer.analyze(userId,
                "判断新闻是否会实质影响当前目标。只输出有明确依据的关系，区分已知事实与推断；"
                        + "最多给出 3 条具体建议动作。若没有实质影响，只回复‘无明确影响’。"
                        + "不得修改目标、创建任务或声称已执行。",
                material.toString());
        if (analyzed == null || analyzed.isBlank() || analyzed.trim().equals("无明确影响")) return "";
        return analyzed.trim();
    }

    static boolean hasPotentialGoalOverlap(List<String> goals, List<NewsCandidate> news) {
        for (String goal : goals == null ? List.<String>of() : goals) {
            String normalizedGoal = normalizeComparable(goal);
            if (normalizedGoal.isBlank()) continue;
            for (NewsCandidate item : news) {
                String topic = normalizeComparable(item.topic());
                if (!topic.isBlank() && (normalizedGoal.contains(topic) || topic.contains(normalizedGoal))) {
                    return true;
                }
                Set<String> goalTerms = latinTerms(goal);
                Set<String> newsTerms = latinTerms(item.topic() + " " + item.result().title());
                if (goalTerms.stream().anyMatch(newsTerms::contains)) return true;
            }
        }
        return false;
    }

    private static String normalizeComparable(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static Set<String> latinTerms(String value) {
        Set<String> terms = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("[a-zA-Z][a-zA-Z0-9.+#-]{2,}").matcher(value == null ? "" : value);
        while (matcher.find()) terms.add(matcher.group().toLowerCase(Locale.ROOT));
        return terms;
    }

    private String startVideos(String userId, String query) throws Exception {
        return startVideos(userId, query, false);
    }

    private String startVideos(String userId, String query, boolean onlyUnseen) throws Exception {
        if (query.isBlank()) return "请先设置关注主题，或告诉我想看什么视频。";
        List<SearchResult> results = distinctVideos(videoProvider.search(query, VIDEO_CANDIDATE_LIMIT));
        if (onlyUnseen) {
            Set<String> seen = store.seenVideoUrls(userId);
            results = results.stream().filter(result -> !seen.contains(result.url())).toList();
        }
        if (results.isEmpty()) return "暂时没有找到可确认的公开视频。";
        VideoFeedSession session = new VideoFeedSession(query, results, 0, List.of(), LocalDateTime.now());
        store.saveVideoSession(userId, session);
        return deliverNextBatch(userId, session);
    }

    private String nextVideos(String userId) {
        VideoFeedSession session = store.videoSession(userId);
        if (session == null) return "目前没有可以继续的视频列表。请先说“给我发某个主题的视频”。";
        return deliverNextBatch(userId, session);
    }

    private String deliverNextBatch(String userId, VideoFeedSession session) {
        if (session.cursor() >= session.results().size()) return "这一批视频已经发完了。你可以换一个主题继续找。";
        int end = Math.min(session.results().size(), session.cursor() + VIDEO_BATCH_SIZE);
        List<SearchResult> batch = session.results().subList(session.cursor(), end);
        VideoFeedSession updated = new VideoFeedSession(session.query(), session.results(), end,
                batch, LocalDateTime.now());
        store.saveVideoSession(userId, updated);
        store.markVideosSeen(userId, batch.stream().map(SearchResult::url).toList());

        StringBuilder reply = new StringBuilder("与“").append(session.query()).append("”相关的视频：\n");
        for (int index = 0; index < batch.size(); index++) {
            SearchResult result = batch.get(index);
            reply.append(index + 1).append(". ").append(result.title()).append('\n')
                    .append(result.url());
            if (index < batch.size() - 1) reply.append("\n\n");
        }
        if (end < session.results().size()) reply.append("\n\n回复“接着发”继续这一主题。 ");
        reply.append("\n回复“提炼第2个视频的亮点”可继续分析公开字幕或描述。 ");
        return reply.toString().trim();
    }

    private String videoInsight(String userId, int displayIndex) {
        VideoFeedSession session = store.videoSession(userId);
        if (session == null || session.lastBatch().isEmpty()) {
            return "请先让我发送一批视频，再指定要提炼第几个。";
        }
        int index = displayIndex - 1;
        if (index < 0 || index >= session.lastBatch().size()) {
            return "最近一批只有 " + session.lastBatch().size() + " 个视频，请重新选择。";
        }
        SearchResult video = session.lastBatch().get(index);
        VideoMaterial videoMaterial;
        try {
            videoMaterial = videoMaterialProvider.load(video);
        } catch (Exception error) {
            System.err.println("[兴趣雷达] 视频公开材料读取失败，退回搜索描述: " + error.getMessage());
            videoMaterial = descriptionMaterial(video);
        }
        String evidenceLabel = videoMaterial.subtitle() ? "公开字幕" : "公开描述";
        String material = "标题：" + video.title() + "\n" + evidenceLabel + "："
                + videoMaterial.content() + "\n来源：" + video.url();
        String analyzed = analyzer.analyze(userId,
                "只根据给定的视频标题和" + evidenceLabel + "提炼可确认的信息。不得声称已经完整观看视频，"
                        + "只有材料中存在时间点时才能引用时间点。输出：公开信息亮点、与主题的关联、"
                        + "需要打开原视频核实的内容。",
                material);
        if (analyzed == null || analyzed.isBlank()) {
            return "这个视频目前没有可可靠分析的公开字幕或描述。\n" + video.url();
        }
        String limitation = videoMaterial.subtitle()
                ? "当前结果基于公开视频字幕，并非模型完整观看视频；时间点仅来自字幕文件。"
                : "当前结果基于公开描述，不等同于完整观看视频。";
        return "视频：" + video.title() + "\n"
                + analyzed.trim() + "\n\n证据：" + videoMaterial.note()
                + "\n说明：" + limitation + "\n" + video.url();
    }

    private static VideoMaterial descriptionMaterial(SearchResult video) {
        return new VideoMaterial(video.summary() == null ? "" : video.summary(),
                "public_description", "搜索结果公开描述");
    }

    private List<InterestTopic> enabledTopics(String userId) {
        return store.topics(userId).stream().filter(InterestTopic::enabled).toList();
    }

    private String extractVideoQuery(String userId, String text) {
        String value = text.replaceFirst("^(请)?(给我|帮我)?(发|找|推荐|搜索)?", "")
                .replaceAll("(一些|几个|三条|3条)?(相关的)?视频.*$", "").trim();
        if (!value.isBlank() && !value.equals("关注的") && !value.equals("相关")) return value;
        return enabledTopics(userId).stream().map(InterestTopic::name).findFirst().orElse("");
    }

    static List<String> parseTopicNames(String text) {
        String value = text == null ? "" : text.trim();
        value = value.replaceFirst("^(?:请)?(?:帮我)?(?:开始)?(?:我想)?(?:我)?(?:关注|订阅)", "").trim();
        int sentenceEnd = value.indexOf('。');
        if (sentenceEnd >= 0) value = value.substring(0, sentenceEnd);
        value = value.replaceAll("(?:方面|相关)?(?:的)?(?:最新)?(?:新闻|消息|资讯)$", "").trim();
        if (value.isBlank()) return List.of();
        return List.of(value.split("\\s*(?:、|，|,|以及|和)\\s*")).stream()
                .map(String::trim).filter(item -> !item.isBlank()).distinct().limit(10).toList();
    }

    static String newsKey(SearchResult result) {
        String url = result.url() == null ? "" : result.url().trim();
        try {
            URI uri = URI.create(url);
            url = new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), null, null).toString();
        } catch (Exception ignored) {
        }
        String title = result.title() == null ? "" : result.title()
                .toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
        return url + "|" + title;
    }

    static String eventKey(SearchResult result) {
        String title = result.title() == null ? "" : result.title().trim();
        title = title.replaceFirst("\\s*[-|｜]\\s*[^-|｜]{1,40}$", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
        return title.isBlank() ? newsKey(result) : "event|" + title;
    }

    static NewsAssessment assessNews(String topic, SearchResult result) {
        String source = result.source() == null ? "" : result.source().toLowerCase(Locale.ROOT);
        int sourceScore = source.isBlank() ? 10 : 25;
        boolean trusted = TRUSTED_SOURCE_MARKERS.stream().anyMatch(source::contains);
        if (trusted) sourceScore = 40;

        String query = topic == null ? "" : topic.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        String title = result.title() == null ? "" : result.title().toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "");
        String summary = result.summary() == null ? "" : result.summary().toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "");
        int relevanceScore = !query.isBlank() && title.contains(query) ? 30
                : (!query.isBlank() && summary.contains(query) ? 15 : 0);

        long ageHours = publishedAgeHours(result.publishedAt());
        int recencyScore = ageHours < 0 ? 5
                : ageHours <= 24 ? 30
                : ageHours <= 72 ? 22
                : ageHours <= 168 ? 12 : 0;
        int score = sourceScore + relevanceScore + recencyScore;

        List<String> reasons = new ArrayList<>();
        reasons.add(trusted ? "官方或高可信来源信号" : source.isBlank() ? "来源未标明" : "来源明确");
        reasons.add(relevanceScore == 30 ? "标题强相关" : relevanceScore == 15 ? "摘要相关" : "主题相关性待核实");
        reasons.add(ageHours < 0 ? "发布时间未知" : ageHours <= 24 ? "24 小时内"
                : ageHours <= 72 ? "3 天内" : ageHours <= 168 ? "7 天内" : "超过 7 天");
        return new NewsAssessment(score, String.join("、", reasons));
    }

    private static boolean trustedSource(SearchResult result) {
        String value = ((result.source() == null ? "" : result.source()) + " "
                + (result.url() == null ? "" : result.url())).toLowerCase(Locale.ROOT);
        return TRUSTED_SOURCE_MARKERS.stream().anyMatch(value::contains)
                || value.contains(".gov.") || value.contains("docs.")
                || value.contains("github.com") || value.contains("developer.");
    }

    private static SearchResult toSearchResult(RadarContentItem item) {
        return new SearchResult(item.title(), item.summary(), item.source(), item.publishedAt(), item.url());
    }

    private static String typeName(RadarContentType type) {
        return switch (type) {
            case NEWS -> "新闻";
            case WEB_PAGE -> "网页";
            case VIDEO -> "视频";
        };
    }

    private static long publishedAgeHours(String value) {
        if (value == null || value.isBlank()) return -1;
        try {
            ZonedDateTime published = ZonedDateTime.parse(value,
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z"));
            return Math.max(0, Duration.between(published.toInstant(), java.time.Instant.now()).toHours());
        } catch (RuntimeException ignored) {
        }
        try {
            LocalDate published = LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE);
            return Math.max(0, Duration.between(published.atStartOfDay(ZoneId.systemDefault()).toInstant(),
                    java.time.Instant.now()).toHours());
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private List<SearchResult> distinctVideos(List<SearchResult> values) {
        Map<String, SearchResult> unique = new LinkedHashMap<>();
        for (SearchResult value : values == null ? List.<SearchResult>of() : values) {
            if (value.url() == null || value.url().isBlank()) continue;
            unique.putIfAbsent(value.url(), value);
        }
        return List.copyOf(unique.values());
    }

    private boolean isTopicCreation(String value) {
        return value.matches("^(?:请)?(?:帮我)?(?:开始)?(?:我想)?(?:我)?(?:关注|订阅).+")
                && !value.contains("新闻") && !value.contains("消息") && !value.contains("视频");
    }

    private boolean isTopicList(String value) {
        return value.matches(".*(?:查看|我的|当前)?关注(?:了)?(?:什么|哪些|主题|列表).*");
    }

    private boolean isNewsRequest(String value) {
        return value.matches(".*(?:关注|感兴趣).*(?:新闻|消息|资讯).*")
                || value.matches(".*(?:最近|最新|重大|大).*(?:新闻|消息).*");
    }

    private boolean isVideoRequest(String value) {
        return value.contains("视频") && value.matches(".*(?:发|找|推荐|搜索|看看|看).*");
    }

    private static int videoIndex(String value) {
        Matcher matcher = VIDEO_INDEX.matcher(value);
        return matcher.find() ? number(matcher.group(1), 1) : 1;
    }

    private static int number(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String shorten(String value, int limit) {
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit) + "...";
    }

    record NewsAssessment(int score, String rationale) { }

    private record NewsCandidate(String topic, SearchResult result, String key,
                                 NewsAssessment assessment) { }

    @FunctionalInterface
    public interface NewsProvider {
        List<SearchResult> search(String query, int limit) throws Exception;
    }

    @FunctionalInterface
    public interface VideoProvider {
        List<SearchResult> search(String query, int limit) throws Exception;
    }

    @FunctionalInterface
    public interface WebProvider {
        List<SearchResult> search(String query, int limit) throws Exception;
    }

    @FunctionalInterface
    public interface MaterialAnalyzer {
        String analyze(String userId, String instruction, String material);
    }

    @FunctionalInterface
    public interface GoalProvider {
        List<String> activeGoals(String userId);
    }

    @FunctionalInterface
    public interface VideoMaterialProvider {
        VideoMaterial load(SearchResult video) throws Exception;
    }

    public record VideoMaterial(String content, String evidenceLevel, String note) {
        public VideoMaterial {
            content = content == null ? "" : content.trim();
            evidenceLevel = evidenceLevel == null ? "public_description" : evidenceLevel.trim();
            note = note == null ? "" : note.trim();
        }

        public boolean subtitle() {
            return evidenceLevel.equals("public_subtitle");
        }
    }
}
