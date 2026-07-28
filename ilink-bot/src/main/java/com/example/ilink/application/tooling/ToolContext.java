package com.example.ilink.application.tooling;

import java.util.Objects;

/**
 * 工具执行时由应用传入的上下文。
 *
 * <p>用户 ID 已经由微信消息提供，因此不应该让模型再次填写。后续需要增加
 * 当前会话、权限等信息时，可以继续扩展本记录。</p>
 */
public record ToolContext(String userId) {

    /** 校验工具调用必须归属于一个用户。 */
    public ToolContext {
        Objects.requireNonNull(userId, "userId");
    }
}
