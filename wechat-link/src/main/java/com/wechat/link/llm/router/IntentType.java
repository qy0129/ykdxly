package com.wechat.link.llm.router;

/**
 * 意图类型枚举
 * <p>
 * 细分用户消息的真实意图，用于路由到对应的专精模型/工作流：
 * - PURE_TEXT：纯文本对话 → 极速文本模型
 * - TEXT_TO_IMAGE：文生图 → DALL-E
 * - IMAGE_EDIT：指令P图（如"把猫换成狗""调亮一点"）→ 图片编辑 API + 激活图缓存
 * - IMAGE_CHAT：对图片进行提问聊天（如"这张图片是什么"）→ 多模态视觉模型
 * - VOICE_ASR_FALLBACK：微信原生 ASR 返回 null，需启动远端高级 ASR 兜底
 * - NEED_VOICE_REPLY：用户需要语音回复（触发 TTS 管道）
 * - DOCUMENT_READ：用户上传 Word/PDF/TXT 文件，Tika 提取后走文本对话
 * - DOCUMENT_GENERATE：用户要求将内容导出为 Word/PDF 文件
 * </p>
 */
public enum IntentType {
    /** 纯文本对话 */
    PURE_TEXT,
    /** 文生图（从零生成） */
    TEXT_TO_IMAGE,
    /** 图片编辑（基于已有图片进行修改） */
    IMAGE_EDIT,
    /** 图片聊天（对图片进行提问/描述） */
    IMAGE_CHAT,
    /** 微信原生 ASR 失败，需要远端 ASR 兜底 */
    VOICE_ASR_FALLBACK,
    /** 用户需要语音回复（触发 TTS） */
    NEED_VOICE_REPLY,
    /** 文档读取：上传文件后 Tika 提取文本分析 */
    DOCUMENT_READ,
    /** 文档生成：将内容导出为 Word/PDF 文件 */
    DOCUMENT_GENERATE,
    /** 文生视频（暂不实现） */
    TEXT_TO_VIDEO,
    /** 文生文档（已废弃，由 DOCUMENT_GENERATE 替代） */
    @Deprecated
    TEXT_TO_DOC
}
