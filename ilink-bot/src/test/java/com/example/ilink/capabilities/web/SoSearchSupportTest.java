package com.example.ilink.capabilities.web;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoSearchSupportTest {

    @Test
    void parsesJobResultWithDirectUrl() {
        String html = """
                <li class="res-list">
                  <h3 class="res-title"><a href="https://www.so.com/link?m=x"
                    data-mdurl="https://m.zhipin.com/job_detail/example.html">「<em>Java后端实习</em>生招聘信息」</a></h3>
                  <p class="res-desc"><span>2026年3月14日 - </span>杭州 本科 150-200元/天 6个月</p>
                </li>
                """;

        List<SearchResult> results = SoSearchSupport.parse(html, 5);

        assertEquals(1, results.size());
        assertEquals("「Java后端实习生招聘信息」", results.get(0).title());
        assertTrue(results.get(0).summary().contains("150-200元/天"));
        assertEquals("https://m.zhipin.com/job_detail/example.html", results.get(0).url());
        assertEquals("m.zhipin.com", results.get(0).source());
    }
}
