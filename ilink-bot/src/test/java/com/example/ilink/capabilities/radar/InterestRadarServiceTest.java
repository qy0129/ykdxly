package com.example.ilink.capabilities.radar;

import com.example.ilink.capabilities.web.SearchResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.Duration;
import java.time.LocalTime;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterestRadarServiceTest {

    @Test
    void createsAndListsMultipleTopics() throws Exception {
        InterestRadarService service = service();

        String created = service.handleCommand("u1", "我关注 Java Agent、Qwen 和 Personal Executive Agent");
        String listed = service.handleCommand("u1", "查看关注主题");

        assertTrue(created.contains("Java Agent"));
        assertTrue(created.contains("Qwen"));
        assertTrue(listed.contains("Personal Executive Agent"));
        assertNull(service.handleCommand("u1", "今天天气怎么样"));
    }

    @Test
    void keepsVideoCursorAcrossContinueCommands() throws Exception {
        InterestRadarStore store = InterestRadarStore.inMemory();
        InterestRadarService service = service(store);
        service.handleCommand("u1", "我关注 Java Agent");

        String first = service.handleCommand("u1", "给我发 Java Agent 视频");
        String second = service.handleCommand("u1", "接着发");

        assertTrue(first.contains("视频 1"));
        assertTrue(first.contains("视频 3"));
        assertFalse(first.contains("视频 4"));
        assertTrue(second.contains("视频 4"));
        assertTrue(second.contains("视频 6"));
        assertEquals(6, store.videoSession("u1").cursor());
    }

    @Test
    void scheduledVideoPushRespectsIntervalAndContinueUsesItsCursor() throws Exception {
        InterestRadarService service = service();
        service.handleCommand("u1", "我关注 Java Agent");

        String first = service.collectVideoUpdates("u1", Duration.ofHours(24));
        String tooSoon = service.collectVideoUpdates("u1", Duration.ofHours(24));
        String continued = service.handleCommand("u1", "接着发");

        assertTrue(first.contains("定期视频推荐"));
        assertTrue(first.contains("视频 1"));
        assertTrue(tooSoon.isBlank());
        assertTrue(continued.contains("视频 4"));
    }

    @Test
    void scheduledVideoPushRotatesEnabledTopics() throws Exception {
        InterestRadarService service = new InterestRadarService(InterestRadarStore.inMemory(),
                (query, limit) -> List.of(),
                (query, limit) -> List.of(new SearchResult(query + " 视频", "描述", "哔哩哔哩", "",
                        "https://www.bilibili.com/video/BV" + Math.abs(query.hashCode()))),
                (userId, instruction, material) -> "unused");
        service.handleCommand("u1", "我关注 Java Agent、Qwen");

        String first = service.collectVideoUpdates("u1", Duration.ZERO);
        String second = service.collectVideoUpdates("u1", Duration.ZERO);

        assertTrue(first.contains("Java Agent 视频"));
        assertTrue(second.contains("Qwen 视频"));
    }

    @Test
    void insightExplicitlyUsesPublicDescription() throws Exception {
        InterestRadarService service = service();
        service.handleCommand("u1", "给我发 Java Agent 视频");

        String reply = service.handleCommand("u1", "提炼第2个视频的亮点");

        assertTrue(reply.contains("公开描述"));
        assertTrue(reply.contains("不等同于完整观看视频"));
        assertTrue(reply.contains("视频 2"));
    }

    @Test
    void insightUsesPublicSubtitleWhenAvailable() throws Exception {
        InterestRadarService service = new InterestRadarService(InterestRadarStore.inMemory(),
                (query, limit) -> List.of(),
                (query, limit) -> videos(limit),
                (userId, instruction, material) -> {
                    assertTrue(material.contains("[00:00:10] 关键结论"));
                    return "亮点：[00:00:10] 关键结论。";
                },
                60,
                userId -> List.of(),
                video -> new InterestRadarService.VideoMaterial(
                        "[00:00:10] 关键结论", "public_subtitle", "公开视频字幕"));
        service.handleCommand("u1", "给我发 Java Agent 视频");

        String reply = service.handleCommand("u1", "提炼第2个视频的亮点");

        assertTrue(reply.contains("基于公开视频字幕"));
        assertTrue(reply.contains("时间点仅来自字幕文件"));
    }

    @Test
    void proactiveNewsOnlyReturnsUnseenItems() throws Exception {
        InterestRadarService service = service();
        service.handleCommand("u1", "我关注 Java Agent");

        String first = service.collectNewsUpdates("u1");
        String second = service.collectNewsUpdates("u1");

        assertTrue(first.contains("Java Agent 新闻"));
        assertTrue(second.isBlank());
    }

    @Test
    void proactiveNewsFiltersStaleLowConfidenceItemsAndDeduplicatesEvents() throws Exception {
        InterestRadarService service = new InterestRadarService(InterestRadarStore.inMemory(),
                (query, limit) -> List.of(
                        new SearchResult("Java Agent 发布生产运行时 - 官方", "Java Agent 正式发布。",
                                "项目官方", LocalDate.now().toString(), "https://official.example/release"),
                        new SearchResult("Java Agent 发布生产运行时 - 媒体", "同一事件的转载。",
                                "科技媒体", LocalDate.now().toString(), "https://media.example/repost"),
                        new SearchResult("Java Agent 旧教程", "个人整理。", "个人博客",
                                LocalDate.now().minusDays(30).toString(), "https://blog.example/old")),
                (query, limit) -> videos(limit),
                (userId, instruction, material) -> "unused");
        service.handleCommand("u1", "我关注 Java Agent");

        String reply = service.collectNewsUpdates("u1");

        assertTrue(reply.contains("Java Agent 发布生产运行时"));
        assertEquals(1, occurrences(reply, "Java Agent 发布生产运行时"));
        assertFalse(reply.contains("Java Agent 旧教程"));
        assertTrue(reply.contains("信号分"));
    }

    @Test
    void oneFailedTopicDoesNotDiscardOtherTopicNews() throws Exception {
        InterestRadarService service = new InterestRadarService(InterestRadarStore.inMemory(),
                (query, limit) -> {
                    if (query.equals("失败主题")) throw new IllegalStateException("offline");
                    return List.of(new SearchResult(query + " 新闻", query + " 摘要", "官方",
                            LocalDate.now().toString(), "https://example.org/" + query));
                },
                (query, limit) -> videos(limit),
                (userId, instruction, material) -> "unused");
        service.handleCommand("u1", "我关注 失败主题、可用主题");

        String reply = service.handleCommand("u1", "关注的最新新闻");

        assertTrue(reply.contains("可用主题 新闻"));
        assertTrue(reply.contains("以上结果并不完整"));
    }

    @Test
    void relevantNewsProducesAdvisoryGoalImpactWithoutCreatingWork() throws Exception {
        InterestRadarService service = new InterestRadarService(InterestRadarStore.inMemory(),
                (query, limit) -> List.of(new SearchResult(
                        "Java Agent 发布新运行时", "迁移接口发生变化。", "项目官方",
                        LocalDate.now().toString(), "https://official.example/runtime")),
                (query, limit) -> videos(limit),
                (userId, instruction, material) -> {
                    assertTrue(material.contains("完成 Java Agent 项目上线"));
                    assertTrue(material.contains("Java Agent 发布新运行时"));
                    return "推断：可能影响现有接口。\n建议动作：先核对迁移说明。";
                },
                60,
                userId -> List.of("完成 Java Agent 项目上线"));
        service.handleCommand("u1", "我关注 Java Agent");

        String reply = service.collectNewsUpdates("u1");

        assertTrue(reply.contains("与当前目标的关系"));
        assertTrue(reply.contains("先核对迁移说明"));
        assertTrue(reply.contains("只有你确认后才会转为执行任务"));
    }

    @Test
    void parsesTopicNamesWithoutPolicySentence() {
        List<String> names = InterestRadarService.parseTopicNames(
                "我关注 Java Agent、Qwen 和 Personal Executive Agent。只看官方来源。");

        assertEquals(List.of("Java Agent", "Qwen", "Personal Executive Agent"), names);
    }

    @Test
    void radarTimeValuesSurviveJsonRoundTrip() {
        LocalDateTime timestamp = LocalDateTime.of(2026, 7, 30, 12, 30, 45);
        VideoFeedSession original = new VideoFeedSession("Java Agent", videos(2), 1,
                videos(1), timestamp);

        String json = InterestRadarStore.createGson().toJson(original);
        VideoFeedSession restored = InterestRadarStore.createGson()
                .fromJson(json, VideoFeedSession.class);

        assertEquals(timestamp, restored.updatedAt());
        assertEquals(1, restored.cursor());
        assertEquals("视频 1", restored.lastBatch().getFirst().title());
    }

    @Test
    void legacyTopicJsonReceivesStableDefaults() {
        InterestTopic restored = InterestRadarStore.createGson().fromJson("""
                {"id":"TOPIC-1","name":"Java Agent","includeTerms":["Java Agent"],
                 "excludeTerms":[],"enabled":true,"createdAt":"2026-07-30T12:00:00"}
                """, InterestTopic.class);

        assertEquals(RadarTopicOrigin.EXPLICIT_USER, restored.origin());
        assertEquals(RadarTopicPriority.NORMAL, restored.priority());
    }

    @Test
    void planSyncCreatesAutomaticTopicsAndPreservesExplicitTopics() throws Exception {
        InterestRadarStore store = InterestRadarStore.inMemory();
        InterestRadarService service = new InterestRadarService(store,
                (query, limit) -> List.of(), (query, limit) -> List.of(),
                (userId, instruction, material) -> "无明确影响", 60,
                userId -> List.of("完成 Java Agent 项目", "实现任务状态持久化"));
        service.handleCommand("u1", "我关注 Qwen");

        String synced = service.handleCommand("u1", "同步我的计划");
        String listed = service.handleCommand("u1", "查看关注主题");

        assertTrue(synced.contains("Java Agent"));
        assertTrue(listed.contains("Qwen [手动]"));
        assertTrue(listed.contains("Java Agent [计划自动]"));
        assertTrue(store.topics("u1").stream()
                .anyMatch(topic -> topic.origin() == RadarTopicOrigin.EXPLICIT_USER));
    }

    @Test
    void unifiedDigestIncludesNewsWebAndVideoAndWaitsThreeHours() throws Exception {
        InterestRadarStore store = InterestRadarStore.inMemory();
        AtomicReference<String> version = new AtomicReference<>("v1");
        InterestRadarService service = digestService(store, version);
        store.savePreferences("u1", RadarPreferences.defaults().withBreakingEnabled(false));
        LocalDateTime ten = LocalDate.now().atTime(10, 0);

        List<String> first = service.collectScheduledUpdates("u1", ten);
        version.set("v2");
        List<String> tooSoon = service.collectScheduledUpdates("u1", ten.plusHours(1));
        List<String> due = service.collectScheduledUpdates("u1", ten.plusHours(3));

        assertEquals(1, first.size());
        assertTrue(first.getFirst().contains("新闻｜"));
        assertTrue(first.getFirst().contains("网页｜"));
        assertTrue(first.getFirst().contains("视频｜"));
        assertTrue(tooSoon.isEmpty());
        assertEquals(1, due.size());
        assertTrue(due.getFirst().contains("v2"));
    }

    @Test
    void digestContainsAtMostOneVideo() throws Exception {
        InterestRadarStore store = InterestRadarStore.inMemory();
        InterestRadarService service = new InterestRadarService(store,
                (query, limit) -> List.of(),
                (query, limit) -> List.of(
                        new SearchResult(query + " 视频一", "简介", "哔哩哔哩", "",
                                "https://www.bilibili.com/video/BV1"),
                        new SearchResult(query + " 视频二", "简介", "哔哩哔哩", "",
                                "https://www.bilibili.com/video/BV2"),
                        new SearchResult(query + " 视频三", "简介", "哔哩哔哩", "",
                                "https://www.bilibili.com/video/BV3")),
                (userId, instruction, material) -> "无明确影响", 60,
                userId -> List.of("完成 Java Agent 项目"));
        store.savePreferences("u1", RadarPreferences.defaults()
                .withBreakingEnabled(false)
                .withContentTypes(List.of(RadarContentType.VIDEO)));

        String digest = service.collectScheduledUpdates("u1", LocalDate.now().atTime(10, 0)).getFirst();

        assertEquals(1, occurrences(digest, "视频｜"));
    }

    @Test
    void quietHoursDelayDigestUntilMorning() throws Exception {
        InterestRadarStore store = InterestRadarStore.inMemory();
        AtomicReference<String> version = new AtomicReference<>("night");
        InterestRadarService service = digestService(store, version);
        store.savePreferences("u1", RadarPreferences.defaults().withBreakingEnabled(false));
        LocalDateTime late = LocalDate.now().atTime(23, 30);

        List<String> quiet = service.collectScheduledUpdates("u1", late);
        List<String> morning = service.collectScheduledUpdates("u1", late.toLocalDate()
                .plusDays(1).atTime(LocalTime.of(8, 0)));

        assertTrue(quiet.isEmpty());
        assertEquals(1, morning.size());
        assertTrue(morning.getFirst().contains("night"));
    }

    @Test
    void dailyBudgetStopsAdditionalBatches() throws Exception {
        InterestRadarStore store = InterestRadarStore.inMemory();
        AtomicReference<String> version = new AtomicReference<>("one");
        InterestRadarService service = digestService(store, version);
        RadarPreferences onePerDay = new RadarPreferences(true, 1, "23:00", "08:00", 3, 1,
                List.of(RadarContentType.NEWS, RadarContentType.WEB_PAGE, RadarContentType.VIDEO),
                false, false);
        store.savePreferences("u1", onePerDay);
        LocalDateTime start = LocalDate.now().atTime(10, 0);

        assertEquals(1, service.collectScheduledUpdates("u1", start).size());
        version.set("two");
        assertTrue(service.collectScheduledUpdates("u1", start.plusHours(2)).isEmpty());
    }

    @Test
    void breakingOfficialNewsCanPushBeforeDigestInterval() throws Exception {
        InterestRadarStore store = InterestRadarStore.inMemory();
        AtomicReference<String> version = new AtomicReference<>("版本一");
        InterestRadarService service = new InterestRadarService(store,
                (query, limit) -> List.of(new SearchResult(query + " 官方发布 " + version.get(),
                        query + " 新版本", "项目官方", LocalDate.now().toString(),
                        "https://official.example/" + version.get())),
                (query, limit) -> List.of(),
                (userId, instruction, material) -> "无明确影响", 60,
                userId -> List.of("完成 Java Agent 项目"));
        LocalDateTime start = LocalDate.now().atTime(10, 0);

        List<String> first = service.collectScheduledUpdates("u1", start);
        version.set("版本二");
        List<String> immediate = service.collectScheduledUpdates("u1", start.plusMinutes(30));

        assertTrue(first.getFirst().startsWith("重大动态提醒"));
        assertTrue(immediate.getFirst().contains("版本二"));
    }

    @Test
    void userCanChangeDigestFrequencyAndContentPolicyByMessage() throws Exception {
        InterestRadarService service = service();

        String interval = service.handleCommand("u1", "每3小时推荐一次");
        String videos = service.handleCommand("u1", "只看视频");
        String settings = service.handleCommand("u1", "查看推送设置");

        assertTrue(interval.contains("每 3 小时"));
        assertTrue(videos.contains("只推送视频"));
        assertTrue(settings.contains("内容类型：视频"));
    }

    @Test
    void deletedAutomaticTopicStaysExcludedAfterPlanChanges() throws Exception {
        InterestRadarStore store = InterestRadarStore.inMemory();
        AtomicReference<List<String>> plan = new AtomicReference<>(List.of("完成 Java Agent 项目"));
        InterestRadarService service = new InterestRadarService(store,
                (query, limit) -> List.of(), (query, limit) -> List.of(),
                (userId, instruction, material) -> "无明确影响", 60, userId -> plan.get());
        service.handleCommand("u1", "同步我的计划");

        String removed = service.handleCommand("u1", "删除关注 Java Agent");
        plan.set(List.of("完成 Java Agent 项目", "学习 Spring AI"));
        service.handleCommand("u1", "同步我的计划");

        assertTrue(removed.contains("Java Agent"));
        assertFalse(store.topics("u1").stream().anyMatch(topic -> topic.name().equals("Java Agent")));
        assertTrue(store.topics("u1").stream().anyMatch(topic -> topic.name().equals("Spring AI")));
    }

    @Test
    void pausedAutomaticTopicStaysPausedAfterPlanChanges() throws Exception {
        InterestRadarStore store = InterestRadarStore.inMemory();
        AtomicReference<List<String>> plan = new AtomicReference<>(List.of("完成 Java Agent 项目"));
        InterestRadarService service = new InterestRadarService(store,
                (query, limit) -> List.of(), (query, limit) -> List.of(),
                (userId, instruction, material) -> "无明确影响", 60, userId -> plan.get());
        service.handleCommand("u1", "同步我的计划");
        service.handleCommand("u1", "暂停关注 Java Agent");

        plan.set(List.of("完成 Java Agent 项目", "学习 Spring AI"));
        service.handleCommand("u1", "同步我的计划");

        assertTrue(store.topics("u1").stream()
                .filter(topic -> topic.name().equals("Java Agent"))
                .noneMatch(InterestTopic::enabled));
    }

    @Test
    void unifiedStoreMergesSameEventAcrossContentTypes() {
        InterestRadarStore store = InterestRadarStore.inMemory();
        LocalDateTime now = LocalDateTime.now();
        String key = "event|springai发布新版本";

        store.saveCandidates("u1", List.of(
                new RadarContentItem("Spring AI", RadarContentType.NEWS, "Spring AI 发布新版本",
                        "新闻", "媒体", "", "https://news.example/1", key, 70, "新闻", false, now),
                new RadarContentItem("Spring AI", RadarContentType.WEB_PAGE, "Spring AI 发布新版本",
                        "官方文档", "官方", "", "https://docs.example/1", key, 90, "官方", true, now)));

        assertEquals(1, store.pendingCandidates("u1").size());
        assertEquals(RadarContentType.WEB_PAGE, store.pendingCandidates("u1").getFirst().type());
    }

    @Test
    void discoveryTopicCursorEventuallyCoversEveryTopic() {
        InterestRadarStore store = InterestRadarStore.inMemory();
        store.addTopics("u1", List.of("A", "B", "C", "D", "E", "F", "G", "H"));
        List<InterestTopic> topics = store.topics("u1");

        List<String> names = new ArrayList<>();
        store.nextDiscoveryTopics("u1", topics, 6).forEach(topic -> names.add(topic.name()));
        store.nextDiscoveryTopics("u1", topics, 6).forEach(topic -> names.add(topic.name()));

        assertEquals(8, names.stream().distinct().count());
    }

    private InterestRadarService service() {
        return service(InterestRadarStore.inMemory());
    }

    private InterestRadarService service(InterestRadarStore store) {
        return new InterestRadarService(store,
                (query, limit) -> List.of(new SearchResult(
                        query + " 新闻", "官方发布了新的任务运行时。", "官方", "2026-07-30",
                        "https://example.org/news/1")),
                (query, limit) -> videos(limit),
                (userId, instruction, material) -> "公开描述亮点：任务状态需要持久化。\n需要打开原视频核实细节。 ");
    }

    private InterestRadarService digestService(InterestRadarStore store,
                                               AtomicReference<String> version) {
        return new InterestRadarService(store,
                (query, limit) -> List.of(new SearchResult(query + " 新闻 " + version.get(),
                        query + " 新闻摘要", "普通媒体", "",
                        "https://news.example/" + version.get() + "/" + Math.abs(query.hashCode()))),
                (query, limit) -> List.of(new SearchResult(query + " 视频 " + version.get(),
                        query + " 视频简介", "哔哩哔哩", "",
                        "https://www.bilibili.com/video/BV" + Math.abs((query + version.get()).hashCode()))),
                (userId, instruction, material) -> "无明确影响", 60,
                userId -> List.of("完成 Java Agent 项目"),
                video -> new InterestRadarService.VideoMaterial(
                        video.summary(), "public_description", "公开视频简介"),
                (query, limit) -> List.of(new SearchResult("Java Agent 官方文档 " + version.get(),
                        "Java Agent 文档更新", "docs.example", "",
                        "https://docs.example/" + version.get())));
    }

    private List<SearchResult> videos(int limit) {
        List<SearchResult> values = new ArrayList<>();
        for (int index = 1; index <= Math.min(8, limit); index++) {
            values.add(new SearchResult("视频 " + index, "公开描述 " + index,
                    "哔哩哔哩", "", "https://www.bilibili.com/video/BV" + index));
        }
        return values;
    }

    private int occurrences(String value, String expected) {
        return value.split(java.util.regex.Pattern.quote(expected), -1).length - 1;
    }
}
