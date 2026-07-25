package com.example.ilink.feature.media;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaKnowledgeServicesTest {

    @Test
    void parsesBangumiSubject() {
        String json = """
                {"data":[{"name":"Sousou no Frieren","name_cn":"葬送的芙莉莲",
                "summary":"勇者一行击败魔王后的故事","date":"2023-09-29","score":9.0}]}
                """;

        var results = new BangumiService(null).parseResponse(json, 3);

        assertEquals("葬送的芙莉莲", results.getFirst().title());
        assertTrue(results.getFirst().detail().contains("评分 9.0"));
    }

    @Test
    void parsesMusicBrainzRecording() {
        String json = """
                {"recordings":[{"title":"晴天","first-release-date":"2003",
                "artist-credit":[{"name":"周杰伦"}]}]}
                """;

        var results = new MusicBrainzService(null).parseResponse(json, "recording", 3);

        assertEquals("晴天", results.getFirst().title());
        assertEquals("周杰伦 晴天", results.getFirst().bilibiliQuery());
    }

    @Test
    void parsesLrcLibLyrics() {
        String json = """
                [{"trackName":"晴天","artistName":"周杰伦","albumName":"叶惠美",
                "plainLyrics":"故事的小黄花，从出生那年就飘着。"}]
                """;

        var results = new LrcLibService(null).parseResponse(json, 2);

        assertEquals("晴天", results.getFirst().title());
        assertTrue(results.getFirst().summary().contains("小黄花"));
    }

    @Test
    void choosesMediaProviderFromRequest() {
        assertEquals("anime", MediaKnowledgeService.resolveCategory("auto", "查一下海贼王动漫资料"));
        assertEquals("lyrics", MediaKnowledgeService.resolveCategory("auto", "找晴天的歌词"));
        assertEquals("music", MediaKnowledgeService.resolveCategory("auto", "查周杰伦的专辑"));
    }
}
