package com.example.ilink.feature.document;

import java.util.List;

/**
 * DOCX 编辑计划。
 *
 * <p>模型只负责提出一组“查找目标文本并替换”的结构化指令，实际替换由
 * {@link DocxEditor} 执行。</p>
 */
public record DocumentEditPlan(List<DocumentService.TextEdit> edits) {
}
