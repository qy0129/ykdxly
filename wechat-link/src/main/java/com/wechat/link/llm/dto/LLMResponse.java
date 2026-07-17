package com.wechat.link.llm.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * LLM 统一响应 DTO
 * <p>
 * 封装 LLM 模块的统一输出结构，供微信端统一回复使用。
 * </p>
 *
 * @author wechat-link
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LLMResponse {

    /** 响应状态：SUCCESS / FAIL */
    private String status;

    /** 响应内容（LLM 生成的文本） */
    private String content;

    /** 错误信息（仅失败时有值） */
    private String errorMsg;

    /** 快捷构造成功响应 */
    public static LLMResponse success(String content) {
        return LLMResponse.builder()
                .status("SUCCESS")
                .content(content)
                .build();
    }

    /** 快捷构造失败响应 */
    public static LLMResponse fail(String errorMsg) {
        return LLMResponse.builder()
                .status("FAIL")
                .errorMsg(errorMsg)
                .build();
    }
}
