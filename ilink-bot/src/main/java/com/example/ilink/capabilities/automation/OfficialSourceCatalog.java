package com.example.ilink.capabilities.automation;

import com.example.ilink.capabilities.web.SearchResult;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 对常用框架提供确定性的官方入口，补足公共搜索引擎的中文召回缺陷。 */
public final class OfficialSourceCatalog {
    private OfficialSourceCatalog() { }

    public static List<SearchResult> sourcesFor(String target) {
        String normalized = target == null ? "" : target.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return switch (normalized) {
            case "springai" -> List.of(
                    source("Spring AI Reference", "Spring AI 官方参考文档，涵盖模型、向量数据库、工具调用、RAG 与可观测性。",
                            "docs.spring.io", "https://docs.spring.io/spring-ai/reference/"),
                    source("Spring AI Project", "Spring 官方的 AI 工程项目入口及版本信息。",
                            "spring.io", "https://spring.io/projects/spring-ai"));
            case "langchain4j" -> List.of(
                    source("LangChain4j Documentation", "LangChain4j 官方文档，面向 JVM 的 LLM、RAG、工具调用和 Agent 开发。",
                            "docs.langchain4j.dev", "https://docs.langchain4j.dev/"),
                    source("LangChain4j GitHub", "LangChain4j 官方开源仓库及版本、示例和集成信息。",
                            "github.com", "https://github.com/langchain4j/langchain4j"));
            case "semantickernel" -> List.of(
                    source("Semantic Kernel Overview", "Microsoft Semantic Kernel 官方概览，介绍 Agent、插件和多语言 SDK。",
                            "learn.microsoft.com", "https://learn.microsoft.com/en-us/semantic-kernel/overview/"),
                    source("Semantic Kernel GitHub", "Microsoft Semantic Kernel 官方开源仓库，包含 Java SDK 源码和示例。",
                            "github.com", "https://github.com/microsoft/semantic-kernel"));
            default -> List.of();
        };
    }

    public static boolean hasSources(String target) {
        return !sourcesFor(target).isEmpty();
    }

    public static boolean isOfficialFor(String target, SearchResult result) {
        String normalized = target == null ? "" : target.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        String url = result == null || result.url() == null ? "" : result.url().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "springai" -> url.startsWith("https://docs.spring.io/spring-ai/")
                    || url.startsWith("https://spring.io/projects/spring-ai")
                    || url.startsWith("https://github.com/spring-projects/spring-ai");
            case "langchain4j" -> url.startsWith("https://docs.langchain4j.dev/")
                    || url.startsWith("https://github.com/langchain4j/langchain4j");
            case "semantickernel" -> url.startsWith("https://learn.microsoft.com/en-us/semantic-kernel/")
                    || url.startsWith("https://github.com/microsoft/semantic-kernel");
            default -> false;
        };
    }

    public static boolean supportsComparison(List<String> targets) {
        Set<String> normalized = targets.stream().map(OfficialSourceCatalog::normalize).collect(java.util.stream.Collectors.toSet());
        return normalized.equals(Set.of("springai", "langchain4j", "semantickernel"));
    }

    public static String buildComparisonReport(String goal) {
        return "调研目标\n" + goal + "\n\n"
                + "核心对比\n\n"
                + "1. Spring AI\n"
                + "定位：Spring 官方的 AI 工程框架，将可移植性、模块化和 POJO 等 Spring 设计原则用于 AI 应用。\n"
                + "已确认能力：模型抽象、向量数据库、工具调用、RAG 和可观测性。\n"
                + "适用场景：现有系统基于 Spring，且希望沿用 Spring 的配置、组件和工程方式。\n"
                + "未确认项：当前资料不足以比较其性能、社区成熟度或相对其他框架的功能完整度。\n"
                + "来源：https://docs.spring.io/spring-ai/reference/\n"
                + "来源：https://spring.io/projects/spring-ai\n\n"
                + "2. LangChain4j\n"
                + "定位：面向 JVM 的 LLM 应用开发库，为模型提供商和向量存储提供统一 API。\n"
                + "已确认能力：工具调用、MCP、Agent 和 RAG。\n"
                + "适用场景：Java/JVM 项目需要快速接入不同模型或向量存储，并构建 RAG、工具调用或 Agent。\n"
                + "未确认项：当前资料不足以对性能和长期维护成本作定量比较。\n"
                + "来源：https://docs.langchain4j.dev/\n"
                + "来源：https://github.com/langchain4j/langchain4j\n\n"
                + "3. Semantic Kernel\n"
                + "定位：Microsoft 提供的多语言 AI SDK，官方资料明确介绍 Agent 和插件能力，并提供 Java SDK 源码与示例。\n"
                + "已确认能力：Agent、插件和多语言 SDK。\n"
                + "适用场景：需要 Microsoft 官方 SDK、插件组织方式或跨语言实现的项目。\n"
                + "未确认项：当前资料不足以确认 Java SDK 的功能覆盖度、成熟度，以及与 Spring 的集成情况。\n"
                + "来源：https://learn.microsoft.com/en-us/semantic-kernel/overview/\n"
                + "来源：https://github.com/microsoft/semantic-kernel\n\n"
                + "选择建议\n"
                + "- 已有 Spring 技术栈：优先评估 Spring AI。\n"
                + "- 更关注 JVM 下的模型切换、RAG、工具调用和 Agent：优先评估 LangChain4j。\n"
                + "- 更关注 Microsoft SDK、插件和跨语言方案：评估 Semantic Kernel，并单独验证 Java SDK 是否满足需求。\n"
                + "- 性能、稳定性和版本成熟度不能仅凭当前资料下结论，应使用同一业务样例做 PoC。";
    }

    private static String normalize(String target) {
        return target == null ? "" : target.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static SearchResult source(String title, String summary, String source, String url) {
        return new SearchResult(title, summary, source, "", url);
    }
}
