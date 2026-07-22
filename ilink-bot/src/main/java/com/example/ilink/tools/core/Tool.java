package com.example.ilink.tools.core;

import com.google.gson.JsonObject;

/**
 * 所有 Function Calling 工具共同遵守的接口。
 *
 * <p>工具定义负责告诉模型“何时调用、需要哪些参数”；execute 负责执行
 * 本地 Java 代码。模型只能提出调用请求，真正的业务操作始终由应用完成。</p>
 */
public interface Tool {

    /** 返回工具名称、用途和 JSON Schema 参数定义。 */
    ToolDefinition definition();

    /**
     * 执行工具。
     *
     * @param context 由应用提供的用户上下文，不要求模型生成
     * @param arguments 模型按照 JSON Schema 生成的参数
     * @return 统一工具结果
     * @throws Exception 底层业务服务执行失败
     */
    ToolResult execute(ToolContext context, JsonObject arguments) throws Exception;
}
