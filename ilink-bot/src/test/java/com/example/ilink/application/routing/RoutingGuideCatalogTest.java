package com.example.ilink.application.routing;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutingGuideCatalogTest {

    @Test
    void everyRegisteredCapabilityHasACompleteRoutingGuide() {
        List<CapabilityDefinition> capabilities = CapabilityRegistry.defaults().all();

        assertTrue(capabilities.size() >= 45);
        for (CapabilityDefinition capability : capabilities) {
            assertTrue(capability.routingGuide().length() >= 500,
                    () -> capability.name() + " 的路由说明不足 500 字");
            assertFalse(capability.routingDomain().isBlank());
            assertFalse(capability.routingHint().isBlank());
        }
    }

    @Test
    void promptIncludesCompactCatalogAndRelevantDomainGuideOnly() {
        RoutePromptBuilder builder = new RoutePromptBuilder(CapabilityRegistry.defaults());
        String prompt = builder.buildUnifiedPrompt(
                RoutingContext.minimal(new IntentContext(false, false, false, false, false)),
                "你现在帮我复盘一下，并查看今天有哪些待办");

        assertTrue(prompt.contains("daily_reflection: 复盘今天完成、延期、未完成和逾期情况并给出明日建议"));
        assertTrue(prompt.contains("本轮高歧义领域判别"));
        assertTrue(prompt.contains("planning："));
        assertFalse(prompt.contains(RoutingGuideCatalog.fullGuide("daily_reflection")));
        assertTrue(prompt.length() < 14_000, "运行时提示词不应注入全部 45 份完整说明");
    }

    @Test
    void domainSelectionOnlyChoosesPromptHintsAndKeepsMultipleDomainsForCompoundRequests() {
        List<String> domains = RoutingGuideCatalog.selectedDomains(
                "明天杭州天气怎么样，随后打车去西湖并点外卖");

        assertTrue(domains.contains("travel_food"));
        assertTrue(domains.size() <= 2);
    }

    @Test
    void customCapabilityReceivesAUsableGenericGuide() {
        CapabilityDefinition custom = new CapabilityDefinition(
                "plugin_capability", "插件扩展能力", "action_text", false);

        assertTrue(custom.routingGuide().length() >= 500);
        assertFalse(custom.routingGuide().contains("null"));
    }
}
