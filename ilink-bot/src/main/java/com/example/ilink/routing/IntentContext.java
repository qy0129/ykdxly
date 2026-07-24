package com.example.ilink.routing;

/**
 * 意图识别所需的会话上下文。
 *
 * <p>这些布尔值告诉模型当前用户是否有待处理图片、绘图请求或文档，
 * 从而区分“继续上一个操作”和“开始新操作”。</p>
 */
public record IntentContext(
        boolean pendingImage,
        boolean hasLastImage,
        boolean pendingDraw,
        boolean hasDocument,
        boolean pendingCalendar) {
}
