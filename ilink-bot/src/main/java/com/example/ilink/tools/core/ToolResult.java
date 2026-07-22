package com.example.ilink.tools.core;

/**
 * 工具统一执行结果。
 *
 * @param success 是否执行成功
 * @param output 可直接返回给模型或用户的文本结果
 * @param data 应用内部需要继续处理的数据，例如图片字节、文件字节或候选地点
 */
public record ToolResult(boolean success, String output, Object data) {

    /** 创建只包含文本的成功结果。 */
    public static ToolResult success(String output) {
        return new ToolResult(true, output, null);
    }

    /** 创建同时包含文本和内部数据的成功结果。 */
    public static ToolResult success(String output, Object data) {
        return new ToolResult(true, output, data);
    }

    /** 创建失败结果。 */
    public static ToolResult failure(String output) {
        return new ToolResult(false, output, null);
    }

    /** 将内部数据转换成调用方期望的类型。 */
    public <T> T dataAs(Class<T> type) {
        return data == null ? null : type.cast(data);
    }
}
