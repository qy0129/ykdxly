package com.example.ilink.capabilities.web;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SogouSearchSupportTest {

    @Test
    void parsesJobTitleSummaryAndDirectSourceUrl() {
        String html = """
                <div class="vrwrap">
                  <h3 class="vr-title"><a href="/link?url=redirect">「<em>Java开发实习生招聘</em>」_上海示例公司</a></h3>
                  <div class="fz-mid space-txt">招聘中 上海 5天/周 6个月 本科 Java 后端岗位职责</div>
                  <span class="cite-date">2026-05-08</span>
                  <div data-url="https://www.zhipin.com/job_detail/example.html"></div>
                </div>
                <div class="vrwrap">
                  <h3><a href="/link?url=second">杭州 Java 后端实习</a></h3>
                  <div class="space-txt">杭州招聘 Java 实习生</div>
                  <div data-url="https://www.nowcoder.com/jobs/2"></div>
                </div>
                """;

        List<SearchResult> results = SogouSearchSupport.parse(html, 10);

        assertEquals(2, results.size());
        assertEquals("「Java开发实习生招聘」_上海示例公司", results.get(0).title());
        assertTrue(results.get(0).summary().contains("6个月"));
        assertEquals("https://www.zhipin.com/job_detail/example.html", results.get(0).url());
        assertEquals("www.zhipin.com", results.get(0).source());
        assertEquals("2026-05-08", results.get(0).publishedAt());
    }
}
