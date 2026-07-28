package com.example.ilink.capabilities.media;

import com.example.ilink.capabilities.web.WebSearchService;

import java.util.List;

/** 按内容类型选择 Bangumi、MusicBrainz 或 LRCLIB，并生成统一回复。 */
public final class MediaKnowledgeService {

    private final BangumiService bangumiService;
    private final MusicBrainzService musicBrainzService;
    private final LrcLibService lrcLibService;
    private final WebSearchService webSearchService;

    public MediaKnowledgeService(BangumiService bangumiService,
                                 MusicBrainzService musicBrainzService,
                                 LrcLibService lrcLibService,
                                 WebSearchService webSearchService) {
        this.bangumiService = bangumiService;
        this.musicBrainzService = musicBrainzService;
        this.lrcLibService = lrcLibService;
        this.webSearchService = webSearchService;
    }

    public MediaKnowledgeResponse lookup(String query, String category, String requestText) {
        String resolvedCategory = resolveCategory(category, requestText);
        try {
            List<MediaKnowledgeItem> items = switch (resolvedCategory) {
                case "anime" -> searchAnime(query);
                case "lyrics" -> lrcLibService.search(query, 2);
                default -> musicBrainzService.search(query, requestText, 3);
            };
            if (items.isEmpty()) {
                return new MediaKnowledgeResponse("暂时没有查到“" + query + "”的可靠资料。",
                        query, "anime".equals(resolvedCategory) ? "video" : "music");
            }
            return new MediaKnowledgeResponse(format(items), items.getFirst().bilibiliQuery(),
                    "anime".equals(resolvedCategory) ? "video" : "music");
        } catch (Exception e) {
            System.err.println("[媒体资料] 查询失败 category=" + resolvedCategory + ": " + e.getMessage());
            return new MediaKnowledgeResponse("这次资料查询没有成功，我先继续帮你查找哔哩哔哩内容。",
                    query, "anime".equals(resolvedCategory) ? "video" : "music");
        }
    }

    private List<MediaKnowledgeItem> searchAnime(String query) throws Exception {
        try {
            List<MediaKnowledgeItem> results = bangumiService.search(query, 3);
            if (!results.isEmpty()) return results;
        } catch (Exception e) {
            System.err.println("[Bangumi] 官方接口失败，使用公共搜索回退: " + e.getMessage());
        }
        return webSearchService.search("site:bgm.tv/subject " + query, 3).stream()
                .map(result -> new MediaKnowledgeItem(
                        result.title(), "", result.summary(), "Bangumi网页", query + " 动漫"))
                .toList();
    }

    static String resolveCategory(String category, String text) {
        if ("anime".equals(category) || "music".equals(category) || "lyrics".equals(category)) {
            return category;
        }
        if (text != null && text.contains("歌词")) return "lyrics";
        if (text != null && text.matches(".*(动漫|动画|番剧|漫画).*")) return "anime";
        return "music";
    }

    private String format(List<MediaKnowledgeItem> items) {
        StringBuilder text = new StringBuilder("我查到这些资料：\n");
        for (int index = 0; index < items.size(); index++) {
            MediaKnowledgeItem item = items.get(index);
            text.append(index + 1).append(". ").append(item.title());
            if (!item.detail().isBlank()) text.append("（").append(item.detail()).append("）");
            if (!item.summary().isBlank()) text.append('\n').append(item.summary());
            text.append("\n来源：").append(item.source());
            if (index < items.size() - 1) text.append("\n\n");
        }
        return text.toString();
    }
}
