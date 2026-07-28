package com.example.ilink.application.routing;

import java.util.Locale;

/** 解析绘图流程中封闭的尺寸回复，不调用语义模型。 */
public final class DrawSizeParser {

    private DrawSizeParser() {
    }

    public static String parse(String text) {
        if (text == null) return "none";
        String value = text.toLowerCase(Locale.ROOT)
                .replaceAll("[，,。.!！?？\\s]", "");
        if (value.matches("^(方形|正方形)(1[:：]1|1比1|1024[x×]1024)?$")
                || value.matches("^(1[:：]1|1比1|1024[x×]1024)$")) {
            return "1024x1024";
        }
        if (value.matches("^(竖屏|竖版)(3[:：]4|3比4|768[x×]1024)?$")
                || value.matches("^(3[:：]4|3比4|768[x×]1024)$")) {
            return "768x1024";
        }
        if (value.matches("^(横屏|横版)(16[:：]9|16比9|1024[x×]576)?$")
                || value.matches("^(16[:：]9|16比9|1024[x×]576)$")) {
            return "1024x576";
        }
        return "none";
    }

    /** 从完整绘图请求中提取用户明确写出的尺寸。 */
    public static String parseMention(String text) {
        if (text == null) return "none";
        String value = text.toLowerCase(Locale.ROOT).replaceAll("\\s", "");
        if (value.matches(".*(方形|正方形|1[:：]1|1比1|1024[x×]1024).*")) return "1024x1024";
        if (value.matches(".*(竖屏|竖版|3[:：]4|3比4|768[x×]1024).*")) return "768x1024";
        if (value.matches(".*(横屏|横版|16[:：]9|16比9|1024[x×]576).*")) return "1024x576";
        return "none";
    }

    public static boolean isCancel(String text) {
        if (text == null) return false;
        return text.trim().matches("^(取消|算了|不画了|停止)$");
    }
}
