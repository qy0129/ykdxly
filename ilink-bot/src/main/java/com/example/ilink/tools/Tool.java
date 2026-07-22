package com.example.ilink.tools;

import java.util.Map;

/**
 * 所有工具类共同遵守的公共接口。
 *
 * <p>工具类需要向意图路由或 Function Calling 流程提供自己的名称、用途、
 * 参数说明和执行入口。具体工具只负责实现业务逻辑，不改变这组公共方法的含义。</p>
 */
public interface Tool {

    /**
     * 返回工具的唯一名称。
     *
     * <p>该名称会作为模型识别工具和调用工具时的标识，建议使用英文小写和下划线。</p>
     */
    String getName();

    /** 返回工具用途说明，供模型和小组成员阅读。 */
    String getDescription();

    /**
     * 返回工具参数定义。
     *
     * <p>Map 的内容可以在后续转换为 Function Calling 所需的 JSON Schema。</p>
     */
    Map<String, Object> getParameterSchema();

    /**
     * 执行工具。
     *
     * @param arguments 模型或调用方传入的参数
     * @return 工具执行结果，具体格式由工具约定
     * @throws Exception 工具执行过程中发生异常
     */
    String execute(Map<String, Object> arguments) throws Exception;
}
