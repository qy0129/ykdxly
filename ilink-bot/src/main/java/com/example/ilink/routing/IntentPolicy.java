package com.example.ilink.routing;

import java.util.Locale;
import java.util.regex.Pattern;

/** 对高风险输出意图做确定性约束，避免模型误判直接触发文件发送。 */
public final class IntentPolicy {

    private static final Pattern IMAGE_CREATION = Pattern.compile(
            "(?:(?:画|绘制|生成|创建|制作|设计|做|来|给我)[^，。！？]{0,18}"
                    + "(?:图片|图像|插画|海报|头像|壁纸|封面|logo|照片|一张图)"
                    + "|调用[^，。！？]{0,10}(?:图片生成|绘图)(?:模型|功能)?)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern IMAGE_EDIT = Pattern.compile(
            "(?:(?:修改|编辑|改成|变成|换成|去掉|删除|添加|加上|调整)[^，。！？]{0,18}"
                    + "(?:图片|图像|这张图|上一张图|照片|海报|头像)"
                    + "|(?:图片|图像|这张图|上一张图|照片|海报|头像)[^，。！？]{0,18}"
                    + "(?:修改|编辑|改成|变成|换成|去掉|删除|添加|加上|调整))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DOCUMENT_EDIT = Pattern.compile(
            "(?:(?:修改|编辑|改写|重写|删除|添加|补充|纠正|修正|调整)[^，。！？]{0,20}"
                    + "(?:文档|文件|正文|内容|标题|段落|格式|字体|错别字|第.+段|这份|里面)"
                    + "|(?:文档|文件|正文|内容|标题|段落|格式|字体|错别字|第.+段|这份|里面)"
                    + "[^，。！？]{0,20}(?:修改|编辑|改写|重写|删除|添加|补充|纠正|修正|调整))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern GENERIC_FILE_REQUEST = Pattern.compile(
            "(?:生成|导出|整理|制作|保存|转成|转换成)[^，。！？]{0,16}(?:文件|文档)",
            Pattern.CASE_INSENSITIVE);

    private IntentPolicy() {
    }

    /** 用户是否明确要求创建视觉内容。 */
    public static boolean isExplicitImageCreation(String text) {
        return text != null && IMAGE_CREATION.matcher(text).find();
    }

    /** 用户是否明确要求修改图片。 */
    public static boolean isExplicitImageEdit(String text) {
        return text != null && IMAGE_EDIT.matcher(text).find();
    }

    /** 用户是否明确要求修改当前文档内容。 */
    public static boolean isExplicitDocumentEdit(String text) {
        return text != null && DOCUMENT_EDIT.matcher(text).find();
    }

    /** 用户是否明确授权生成或导出文档文件。 */
    public static boolean hasExplicitFileRequest(String text) {
        if (text == null || text.isBlank()) return false;
        if (!"none".equals(explicitOutputFileType(text))) return true;
        // “生成图片文件”仍属于图片请求，不能因为出现“文件”就转成 DOCX。
        return !isExplicitImageCreation(text) && GENERIC_FILE_REQUEST.matcher(text).find();
    }

    /** 从用户原话中提取明确的文件类型，未说明时返回 none。 */
    public static String explicitOutputFileType(String text) {
        if (text == null) return "none";
        String normalized = text.toLowerCase(Locale.ROOT);
        if (normalized.contains("pdf")) return "pdf";
        if (normalized.contains("docx") || normalized.contains("word") || normalized.contains("doc 文件")) {
            return "docx";
        }
        return "none";
    }

    /** 用户是否只是在回答上一轮的文件格式选择。 */
    public static boolean isFileTypeAnswer(String text) {
        if (text == null) return false;
        String normalized = text.trim().toLowerCase(Locale.ROOT)
                .replace("格式", "")
                .replace("文件", "")
                .replace("版", "")
                .trim();
        return "pdf".equals(normalized) || "word".equals(normalized)
                || "docx".equals(normalized) || "doc".equals(normalized);
    }
}
