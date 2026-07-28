package com.example.ilink.application.tooling;

/**
 * 工具统一执行结果。
 *
 * @param success 是否执行成功
 * @param output 可直接返回给模型或用户的文本结果
 * @param data 应用内部需要继续处理的数据，例如图片字节、文件字节或候选地点
 */
public record ToolResult(boolean success, String output, Object data, Kind kind) {

    public enum Kind { TEXT, DATA, MEDIA, FAILURE }

    /** 创建只包含文本的成功结果。 */
    public static ToolResult success(String output) {
        return new ToolResult(true, output, null, Kind.TEXT);
    }

    /** 创建同时包含文本和内部数据的成功结果。 */
    public static ToolResult success(String output, Object data) {
        return new ToolResult(true, output, data, Kind.DATA);
    }

    /** 创建包含真实媒体二进制产物的成功结果。 */
    public static ToolResult media(String output, Object data) {
        return new ToolResult(true, output, data, Kind.MEDIA);
    }

    /** 创建失败结果。 */
    public static ToolResult failure(String output) {
        return new ToolResult(false, output, null, Kind.FAILURE);
    }

    /** 将内部数据转换成调用方期望的类型。 */
    public <T> T dataAs(Class<T> type) {
        return data == null ? null : type.cast(data);
    }

    public boolean hasMedia(Class<?> type) {
        return success && kind == Kind.MEDIA && data != null && type.isInstance(data);
    }
}
