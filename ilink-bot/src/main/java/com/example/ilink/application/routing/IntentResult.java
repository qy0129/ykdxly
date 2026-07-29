package com.example.ilink.application.routing;

import java.util.List;

/**
 * 意图识别结果。
 *
 * <p>除主意图外，还携带绘图尺寸、回复模式、音色、图片动作和文件类型等
 * 执行参数，避免业务层再次解析原始自然语言。</p>
 */
public record IntentResult(
        String intent,
        String enPrompt,
        String cnDescription,
        String imageSize,
        String replyMode,
        String voiceStyle,
        String persona,
        String imageAction,
        String imagePrompt,
        String audioSource,
        int audioIndex,
        String documentAction,
        String outputFileType,
        String weatherLocation,
        String weatherDay,
        String planGoal,
        String planDeadline,
        String planAvailableTime,
        String calculationOperation,
        String calculationLeft,
        String calculationRight,
        String calculationQuantity,
        String calculationUnitPrice,
        String calculationDiscountPercent,
        String travelOrigin,
        String travelDestination,
        List<String> travelStops,
        String originCity,
        String destinationCity,
        String travelDepartureTime,
        int timeBudgetMinutes,
        String mealKeyword,
        String dietGoal,
        String nearbyLocation,
        String nearbyAction,
        String calendarAction,
        String calendarTitle,
        String calendarTime,
        String calendarRecurrence,
        int calendarReminderMinutes,
        String calendarTimeType,
        long calendarTimeAmount,
        String calendarTimeUnit,
        int calendarLeadTimeSeconds,
        String bilibiliQuery,
        String bilibiliCategory,
        String mediaQuery,
        String mediaCategory,
        String emailAction,
        String emailKeyword,
        String foodOrderRestaurants) {

    public static IntentResult chat() {
        return new IntentResult("chat", "", "", "none", "keep", "default", "", "none", "", "none", 0,
                "none", "none", "", "today", "", "", "", "", "", "", "", "", "", "", "",
                List.of(), "", "", "", 0, "", "", "", "", "none", "", "", "none", 0, "auto", 0,
                "none", 0, "", "", "", "", "", "", "");
    }
}
