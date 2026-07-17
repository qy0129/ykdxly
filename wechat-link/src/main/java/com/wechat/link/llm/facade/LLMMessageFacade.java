package com.wechat.link.llm.facade;

import com.wechat.link.llm.client.LLMClient;
import com.wechat.link.llm.dto.LLMRequest;
import com.wechat.link.llm.dto.LLMResponse;
import com.wechat.link.llm.exception.LLMException;
import com.wechat.link.llm.multimodal.MultiModalParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * LLM 消息调度门面
 * <p>
 * 作为微信消息流转到 LLM 的唯一入口。
 * 根据消息类型自动分发给 LLMClient（文本对话）或对应的 MultiModalParser（多模态解析）。
 * 同时预留 Agent 调用的扩展点。
 * </p>
 *
 * @author wechat-link
 */
@Slf4j
@Service
public class LLMMessageFacade {

    private final LLMClient llmClient;
    private final List<MultiModalParser<String>> multiModalParsers;

    public LLMMessageFacade(LLMClient llmClient,
                            List<MultiModalParser<String>> multiModalParsers) {
        this.llmClient = llmClient;
        this.multiModalParsers = multiModalParsers;
    }

    /**
     * 处理消息的统一入口
     *
     * @param request LLM 请求
     * @return 统一响应
     */
    public LLMResponse handleMessage(LLMRequest request) {
        log.info("收到消息分发请求 - 用户: {}, 类型: {}", request.getUserId(), request.getMessageType());

        try {
            String messageType = request.getMessageType();

            // 文本消息 -> 直接走 LLM 对话
            if ("TEXT".equalsIgnoreCase(messageType)) {
                return handleTextMessage(request);
            }

            // 语音消息（已转文字） -> 走 LLM 对话
            if ("VOICE".equalsIgnoreCase(messageType) && request.getContent() != null && !request.getContent().isBlank()) {
                return handleTextMessage(request);
            }

            // 其他媒体类型 -> 走多模态解析器
            return handleMultiModalMessage(request);

        } catch (LLMException e) {
            log.error("LLM 处理异常 - 用户: {}, 错误码: {}, 信息: {}",
                    request.getUserId(), e.getErrorCode(), e.getMessage());
            return LLMResponse.fail("抱歉，AI 处理出现问题：" + e.getMessage());
        } catch (Exception e) {
            log.error("消息处理未知异常 - 用户: {}", request.getUserId(), e);
            return LLMResponse.fail("系统繁忙，请稍后再试。");
        }
    }

    /**
     * 处理文本类消息（包括语音转文字）
     */
    private LLMResponse handleTextMessage(LLMRequest request) {
        // TODO: 此处预留 Agent 路由判断
        // 未来可在此判断用户意图，决定是否走 Agent 流程
        // if (shouldRouteToAgent(request)) {
        //     return agentService.execute(buildAgentContext(request));
        // }

        return llmClient.chat(request);
    }

    /**
     * 处理多模态消息（图片/视频/文件）
     * 通过策略模式自动匹配对应的解析器
     */
    private LLMResponse handleMultiModalMessage(LLMRequest request) {
        String messageType = request.getMessageType();
        String mediaUrl = request.getMediaUrl();

        // 查找支持该类型的解析器
        for (MultiModalParser<String> parser : multiModalParsers) {
            if (parser.supports(messageType)) {
                log.info("找到匹配的解析器: {} -> {}", messageType, parser.getClass().getSimpleName());
                return parser.parse(mediaUrl);
            }
        }

        // 无匹配解析器，返回默认提示
        log.warn("未找到媒体类型 [{}] 的解析器", messageType);
        return LLMResponse.success("已收到你的消息，该类型暂不支持智能解析。");
    }
}
