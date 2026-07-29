package com.example.ilink.capabilities.inbox;

import com.example.ilink.capabilities.inbox.config.InboxConfig;
import com.example.ilink.capabilities.inbox.model.ExtractionResult;
import com.example.ilink.capabilities.inbox.model.MessageResult;
import com.example.ilink.capabilities.inbox.model.MessageSummary;
import com.example.ilink.capabilities.inbox.model.ProcessedMessage;
import com.example.ilink.capabilities.inbox.model.RawMessage;
import com.example.ilink.capabilities.inbox.service.DedupeService;
import com.example.ilink.capabilities.inbox.service.ExtractorService;
import com.example.ilink.capabilities.inbox.service.MessagePreprocessor;
import com.example.ilink.capabilities.inbox.service.ResultAssembler;
import com.example.ilink.capabilities.inbox.service.SummaryService;

import java.util.ArrayList;
import java.util.List;

/**
 * Inbox模块 - 消息处理核心模块
 *
 * <p>负责将微信接收到的原始消息转化为可执行的结构化任务。</p>
 *
 * <p>处理流程：</p>
 * <pre>
 * 原始消息 → 预处理 → 去重 → 摘要 → 提取 → 组装 → 结果
 * </pre>
 *
 * <p>使用方式：</p>
 * <pre>{@code
 * // 初始化模块
 * InboxConfig config = InboxConfig.defaultConfig();
 * InboxModule module = new InboxModule(config);
 *
 * // 处理消息
 * RawMessage message = RawMessage.fromWechat(
 *     "msg_001", "user_123", "张三", "明天下午3点开会"
 * );
 * MessageResult result = module.process(message);
 *
 * // 使用结果
 * if (result.isSuccess()) {
 *     System.out.println("摘要: " + result.summary().summary());
 *     System.out.println("任务数: " + result.taskCount());
 * }
 * }</pre>
 */
public class InboxModule {

    private final InboxConfig config;
    private final MessagePreprocessor preprocessor;
    private final DedupeService dedupeService;
    private final SummaryService summaryService;
    private final ExtractorService extractorService;
    private final ResultAssembler resultAssembler;

    // 统计计数器
    private long totalProcessed = 0;
    private long totalDuplicates = 0;
    private long totalFailed = 0;

    /**
     * 创建Inbox模块
     *
     * @param config 模块配置
     */
    public InboxModule(InboxConfig config) {
        this.config = config;
        this.preprocessor = new MessagePreprocessor(config);
        this.dedupeService = new DedupeService(config, null);
        this.summaryService = new SummaryService(config);
        this.extractorService = new ExtractorService();
        this.resultAssembler = new ResultAssembler();
    }

    /**
     * 创建带自定义存储的Inbox模块
     *
     * @param config 模块配置
     * @param dedupeStore 去重存储
     */
    public InboxModule(InboxConfig config, DedupeService.DedupeStore dedupeStore) {
        this.config = config;
        this.preprocessor = new MessagePreprocessor(config);
        this.dedupeService = new DedupeService(config, dedupeStore);
        this.summaryService = new SummaryService(config);
        this.extractorService = new ExtractorService();
        this.resultAssembler = new ResultAssembler();
    }

    /**
     * 处理单条消息
     *
     * @param message 原始消息
     * @return 处理结果
     */
    public MessageResult process(RawMessage message) {
        try {
            totalProcessed++;

            // Step 1: 预处理
            ProcessedMessage processed = preprocessor.process(message);

            // Step 2: 去重检查
            var dedupeResult = dedupeService.check(
                processed.messageId(),
                processed.cleanedContent(),
                processed.senderId()
            );

            if (dedupeResult.isDuplicate()) {
                totalDuplicates++;
                return resultAssembler.assembleDuplicate(
                    processed.messageId(),
                    dedupeResult.reason()
                );
            }

            // Step 3: 摘要
            MessageSummary summary = summaryService.summarize(processed);

            // Step 4: 提取
            ExtractionResult extraction = extractorService.extract(summary, processed);

            // Step 5: 组装结果
            return resultAssembler.assemble(processed, summary, extraction);

        } catch (Exception e) {
            totalFailed++;
            System.err.println("[Inbox] 消息处理失败: " + e.getMessage());
            return MessageResult.failed(message.msgId(), e.getMessage());
        }
    }

    /**
     * 批量处理消息
     *
     * @param messages 原始消息列表
     * @return 处理结果列表
     */
    public List<MessageResult> processBatch(List<RawMessage> messages) {
        List<MessageResult> results = new ArrayList<>();
        for (RawMessage message : messages) {
            results.add(process(message));
        }
        return results;
    }

    /**
     * 获取模块配置
     *
     * @return 配置
     */
    public InboxConfig getConfig() {
        return config;
    }

    /**
     * 获取处理统计
     *
     * @return 统计信息
     */
    public InboxStats getStats() {
        return new InboxStats(
            totalProcessed,
            totalDuplicates,
            totalFailed,
            dedupeService.getStats(),
            summaryService.getStats(),
            extractorService.getStats(),
            resultAssembler.getStats()
        );
    }

    /**
     * 重置统计计数器
     */
    public void resetStats() {
        totalProcessed = 0;
        totalDuplicates = 0;
        totalFailed = 0;
    }

    /**
     * Inbox模块统计
     */
    public record InboxStats(
        long totalProcessed,
        long totalDuplicates,
        long totalFailed,
        DedupeService.DedupeStats dedupeStats,
        SummaryService.SummaryStats summaryStats,
        ExtractorService.ExtractorStats extractorStats,
        ResultAssembler.AssemblerStats assemblerStats
    ) {
        /**
         * 获取成功率
         */
        public double successRate() {
            if (totalProcessed == 0) return 0.0;
            return (double) (totalProcessed - totalDuplicates - totalFailed) / totalProcessed;
        }

        /**
         * 获取去重率
         */
        public double duplicateRate() {
            if (totalProcessed == 0) return 0.0;
            return (double) totalDuplicates / totalProcessed;
        }
    }
}
