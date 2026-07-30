package com.example.ilink.capabilities.radar;

import com.example.ilink.capabilities.web.SearchResult;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BilibiliVideoContentServiceTest {

    @Test
    void loadsPublicSubtitleWithSourceTimestamps() throws Exception {
        BilibiliVideoContentService service = new BilibiliVideoContentService(uri -> {
            if (uri.getPath().endsWith("/view")) return json("""
                    {"code":0,"data":{"desc":"简介","pages":[{"cid":123}]}}
                    """);
            if (uri.getPath().endsWith("/v2")) return json("""
                    {"code":0,"data":{"subtitle":{"subtitles":[
                      {"subtitle_url":"https://aisubtitle.hdslb.com/subtitle.json"}
                    ]}}}
                    """);
            return json("""
                    {"body":[
                      {"from":10.2,"to":12.0,"content":"第一个亮点"},
                      {"from":65.0,"to":68.0,"content":"第二个亮点"}
                    ]}
                    """);
        });

        InterestRadarService.VideoMaterial material = service.load(video());

        assertEquals("public_subtitle", material.evidenceLevel());
        assertTrue(material.content().contains("[00:00:10] 第一个亮点"));
        assertTrue(material.content().contains("[00:01:05] 第二个亮点"));
    }

    @Test
    void fallsBackToOfficialDescriptionWhenSubtitleIsUnavailable() throws Exception {
        BilibiliVideoContentService service = new BilibiliVideoContentService(uri -> {
            if (uri.getPath().endsWith("/view")) return json("""
                    {"code":0,"data":{"desc":"视频官方简介","pages":[{"cid":123}]}}
                    """);
            return json("{" + "\"code\":0,\"data\":{\"subtitle\":{\"subtitles\":[]}}}");
        });

        InterestRadarService.VideoMaterial material = service.load(video());

        assertEquals("public_description", material.evidenceLevel());
        assertEquals("视频官方简介", material.content());
    }

    @Test
    void extractsOnlyValidBvidShape() {
        assertEquals("BV1AB411C7mD", BilibiliVideoContentService.extractBvid(
                "https://www.bilibili.com/video/BV1AB411C7mD?p=1"));
        assertEquals("", BilibiliVideoContentService.extractBvid("https://example.org/video/123"));
    }

    private SearchResult video() {
        return new SearchResult("测试视频", "搜索描述", "哔哩哔哩", "",
                "https://www.bilibili.com/video/BV1AB411C7mD");
    }

    private JsonObject json(String value) {
        return JsonParser.parseString(value).getAsJsonObject();
    }
}
