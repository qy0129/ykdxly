package com.example.ilink.capabilities.radar;

import com.example.ilink.bootstrap.Config;

import java.time.LocalTime;
import java.util.List;

/** 每个用户独立的主动推送节奏和内容范围。 */
public record RadarPreferences(
        boolean enabled,
        int digestHours,
        String quietStart,
        String quietEnd,
        int maxItems,
        int dailyMaxPushes,
        List<RadarContentType> contentTypes,
        boolean officialOnly,
        boolean breakingEnabled) {

    public RadarPreferences {
        digestHours = Math.max(1, Math.min(24, digestHours));
        quietStart = validTime(quietStart, "23:00");
        quietEnd = validTime(quietEnd, "08:00");
        maxItems = Math.max(1, Math.min(5, maxItems));
        dailyMaxPushes = Math.max(1, Math.min(24, dailyMaxPushes));
        contentTypes = contentTypes == null || contentTypes.isEmpty()
                ? List.of(RadarContentType.NEWS, RadarContentType.WEB_PAGE, RadarContentType.VIDEO)
                : List.copyOf(contentTypes);
    }

    public static RadarPreferences defaults() {
        return new RadarPreferences(true, Config.INTEREST_RADAR_DIGEST_HOURS,
                Config.INTEREST_RADAR_QUIET_START, Config.INTEREST_RADAR_QUIET_END,
                Config.INTEREST_RADAR_DIGEST_MAX_ITEMS, Config.INTEREST_RADAR_DAILY_MAX_PUSHES,
                List.of(RadarContentType.NEWS, RadarContentType.WEB_PAGE, RadarContentType.VIDEO),
                false, true);
    }

    public boolean quietAt(LocalTime time) {
        LocalTime start = LocalTime.parse(quietStart);
        LocalTime end = LocalTime.parse(quietEnd);
        if (start.equals(end)) return false;
        return start.isBefore(end)
                ? !time.isBefore(start) && time.isBefore(end)
                : !time.isBefore(start) || time.isBefore(end);
    }

    public RadarPreferences withEnabled(boolean value) {
        return new RadarPreferences(value, digestHours, quietStart, quietEnd, maxItems,
                dailyMaxPushes, contentTypes, officialOnly, breakingEnabled);
    }

    public RadarPreferences withDigestHours(int value) {
        return new RadarPreferences(enabled, value, quietStart, quietEnd, maxItems,
                dailyMaxPushes, contentTypes, officialOnly, breakingEnabled);
    }

    public RadarPreferences withMaxItems(int value) {
        return new RadarPreferences(enabled, digestHours, quietStart, quietEnd, value,
                dailyMaxPushes, contentTypes, officialOnly, breakingEnabled);
    }

    public RadarPreferences withContentTypes(List<RadarContentType> values) {
        return new RadarPreferences(enabled, digestHours, quietStart, quietEnd, maxItems,
                dailyMaxPushes, values, officialOnly, breakingEnabled);
    }

    public RadarPreferences withOfficialOnly(boolean value) {
        return new RadarPreferences(enabled, digestHours, quietStart, quietEnd, maxItems,
                dailyMaxPushes, contentTypes, value, breakingEnabled);
    }

    public RadarPreferences withBreakingEnabled(boolean value) {
        return new RadarPreferences(enabled, digestHours, quietStart, quietEnd, maxItems,
                dailyMaxPushes, contentTypes, officialOnly, value);
    }

    private static String validTime(String value, String fallback) {
        try {
            return LocalTime.parse(value).toString();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }
}
