package com.example.ilink.application.routing;

import com.example.ilink.capabilities.documents.DocumentFileType;

import java.util.regex.Pattern;

/** 对高风险输出意图做确定性约束，避免模型误判直接触发文件发送。 */
public final class IntentPolicy {

    private static final Pattern IMAGE_CREATION = Pattern.compile(
            "(?:(?:画|绘制|生成|创建|制作|设计|做|来|给我)[^，。！？]{0,18}"
                    + "(?:图片|图像|插画|海报|头像|壁纸|封面|logo|照片|一张图)"
                    + "|调用[^，。！？]{0,10}(?:图片生成|绘图)(?:模型|功能)?)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern IMAGE_EDIT = Pattern.compile(
            "(?:(?:修改|编辑|改成|变成|换成|换上|去掉|删除|移除|添加|增加|加上|再加|戴上|戴个|戴一个|戴副|调整)"
                    + "[^，。！？]{0,28}(?:图片|图像|这张图|上一张图|照片|海报|头像|刚才(?:生成)?(?:的)?|刚刚(?:生成)?(?:的)?|上面(?:生成)?(?:的)?|这张|那张|图里|图中|图片上|画面(?:中|里|上))"
                    + "|(?:图片|图像|这张图|上一张图|照片|海报|头像|刚才(?:生成)?(?:的)?|刚刚(?:生成)?(?:的)?|上面(?:生成)?(?:的)?|这张|那张|图里|图中|图片上|画面(?:中|里|上))"
                    + "[^，。！？]{0,28}(?:修改|编辑|改成|变成|换成|换上|去掉|删除|移除|添加|增加|加上|再加|戴上|戴个|戴一个|戴副|调整))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DOCUMENT_EDIT = Pattern.compile(
            "(?:(?:修改|编辑|改写|重写|删除|移除|添加|插入|补充|替换|纠正|修正|调整)"
                    + "[^，。！？]{0,40}(?:文档|文件|正文|内容|标题|段落|第.+段|格式|字体|错别字|页|表格|单元格|sheet|幻灯片|这份|里面)"
                    + "|(?:文档|文件|正文|内容|标题|段落|第.+段|格式|字体|错别字|页|表格|单元格|sheet|幻灯片|这份|里面)"
                    + "[^，。！？]{0,40}(?:修改|编辑|改写|重写|删除|移除|添加|插入|补充|替换|纠正|修正|调整))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DOCUMENT_IMAGE_INSERT = Pattern.compile(
            "(?:(?:插入|添加到|放到|放入|放在|加入)[^，。！？]{0,36}(?:文档|文件|报告|合同|word|pdf|第.+页|第.+段)"
                    + "|(?:文档|文件|报告|合同|word|pdf|第.+页|第.+段)[^，。！？]{0,36}(?:插入|添加|放入|放上|加入))"
                    + ".*(?:图片|图像|照片|截图|这张图|上一张图|刚才的图)"
                    + "|(?:图片|图像|照片|截图|这张图|上一张图|刚才的图).*"
                    + "(?:插入|添加到|放到|放入|放在|加入)[^，。！？]{0,36}(?:文档|文件|报告|合同|word|pdf|第.+页|第.+段)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DOCUMENT_SUMMARY = Pattern.compile(
            "(?:.*(?:总结|概括|摘要|提炼|归纳|梳理)(?:一下)?(?:这份|当前|刚才|上面)?(?:文档|文件|报告|合同|正文|文件内容).*)"
                    + "|^(?:总结|概括|提炼|归纳|梳理)(?:一下)?(?:这份|这个)?(?:文档|文件)?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DOCUMENT_QUESTION = Pattern.compile(
            ".*(?:这份|当前|刚才|上面|文档|文件|报告|合同|正文|里面|其中|第.+(?:页|段|条)).*(?:什么|多少|哪|是否|有没有|吗|？|[?]).*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern GENERIC_FILE_REQUEST = Pattern.compile(
            "(?:生成|导出|整理|制作|保存|转成|转换成)[^，。！？]{0,16}(?:文件|文档)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DOCUMENT_FORMAT_CONVERSION = Pattern.compile(
            ".*(?:转换(?:成|为)?|转成|转为|改成|导出(?:为|成)?|另存为).*"
                    + "(?:pdf|word|docx|excel|xlsx|ppt|pptx|txt|markdown|md|csv).*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TODO_CREATION = Pattern.compile(
            "^(?:(?:请|麻烦)?(?:帮我|给我)?\\s*)?"
                    + "(?:新建|创建|新增|添加|设置|安排|记录|记下)"
                    + "(?:以下|下面|这些|一下|一个|一条|几条)?\\s*(?:待办事项|待办|任务清单)"
                    + "(?:[：:，,。\\s]|$)"
                    + "|^.+(?:加入|添加到|记到|放进|放入)\\s*(?:我的)?(?:待办事项|待办|任务清单)"
                    + "[。！!\\s]*$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private IntentPolicy() {
    }

    /** “重新发一遍”属于会话控制命令，不应交给邮箱或通用语义路由。 */
    public static boolean isRepeatRequest(String text) {
        if (text == null) return false;
        String value = text.replaceAll("[，,。.!！?？\\s]", "");
        return value.matches("^(你)?(请)?(帮我)?(把)?(刚才的|上一条|上一个回复)?"
                + "(重新发|重发|再发|再说)(一遍|一次|一下)?(给我)?$");
    }

    /** 简短寒暄不需要经过模型路由或聊天模型。 */
    public static boolean isCasualGreeting(String text) {
        if (text == null) return false;
        String value = text.replaceAll("[，,。.!！?？\\s]", "");
        return value.matches("^(你好|嗨|哈喽|hello|hi|在吗|早上好|上午好|中午好|下午好|晚上好)$");
    }

    /** 路由不可用时，只对高置信度的待办创建命令启用本地兜底。 */
    public static boolean isExplicitTodoCreation(String text) {
        if (text == null || text.isBlank()) return false;
        String value = text.trim();
        if (value.endsWith("?") || value.endsWith("？")
                || value.matches("^(如何|怎么|怎样|为什么).*")) return false;
        return TODO_CREATION.matcher(value).find();
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
        return text != null && (DOCUMENT_EDIT.matcher(text).find() || isDocumentImageInsertion(text));
    }

    public static boolean isDocumentImageInsertion(String text) {
        return text != null && DOCUMENT_IMAGE_INSERT.matcher(text).find();
    }

    public static boolean isExplicitDocumentSummary(String text) {
        return text != null && DOCUMENT_SUMMARY.matcher(text).find();
    }

    public static boolean isExplicitDocumentQuestion(String text) {
        return text != null && DOCUMENT_QUESTION.matcher(text).find();
    }

    public static boolean isDocumentFormatConversion(String text) {
        return text != null && DOCUMENT_FORMAT_CONVERSION.matcher(text).find();
    }

    /** 用户是否明确表达“在当前位置寻找餐饮”。 */
    public static boolean isNearbyDiningRequest(String text) {
        if (text == null || text.isBlank()) return false;
        boolean hasLocation = text.matches(".*(我现在在|我在|当前位置|这附近|附近).*" );
        return hasLocation && !isExplicitFoodOrderRequest(text)
                && text.matches(".*(想吃|想喝|找|有没有|哪里有|附近有|有什么好吃|有啥好吃|吃什么|推荐.*(?:餐厅|美食|吃的)).*" );
    }

    /** 用户是否只是明确提供当前位置，供后续位置能力使用。 */
    public static boolean isExplicitLocationRememberRequest(String text) {
        if (text == null || text.isBlank()) return false;
        String value = text.trim();
        return value.matches("^(我现在在|我在|当前位置是|我的位置是|我位于).+")
                && !value.matches(".*(想吃|想喝|附近|餐厅|美食|天气|导航|路线|打车|外卖|下单).*" );
    }

    /** 用户是否明确要求点餐、下单或获取外卖入口。 */
    public static boolean isExplicitFoodOrderRequest(String text) {
        return text != null && text.matches(".*(点外卖|外卖下单|下单|点餐|美团|饿了么|外卖链接).*" );
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
        String type = DocumentFileType.fromUserText(text);
        if (!"none".equals(type) || text == null) return type;
        return text.matches(".*(?:生成|制作|导出|整理成|做成).*(?:电子)?表格.*") ? "xlsx" : "none";
    }

    /** 用户是否只是在回答上一轮的文件格式选择。 */
    public static boolean isFileTypeAnswer(String text) {
        if (text == null) return false;
        String normalized = text.trim().toLowerCase(java.util.Locale.ROOT)
                .replace("格式", "")
                .replace("文件", "")
                .replace("版", "")
                .trim();
        return !"none".equals(DocumentFileType.fromUserText(normalized));
    }
}
