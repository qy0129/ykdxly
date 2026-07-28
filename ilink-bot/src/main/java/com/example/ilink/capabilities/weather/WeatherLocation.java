package com.example.ilink.capabilities.weather;

import java.util.ArrayList;
import java.util.List;

/**
 * 地理编码接口返回的地点。
 */
public record WeatherLocation(
        String name,
        String admin1,
        String admin2,
        String country,
        double latitude,
        double longitude,
        int featurePriority,
        int population) {

    public boolean isClearlyPrimary() {
        return featurePriority >= 70 && population > 100_000;
    }

    /** 用于让用户从同名地点中选择的完整地点名称。 */
    public String displayName() {
        List<String> details = new ArrayList<>();
        addIfUseful(details, admin1);
        addIfUseful(details, admin2);
        addIfUseful(details, country);
        return details.isEmpty() ? name : name + "（" + String.join("、", details) + "）";
    }

    private void addIfUseful(List<String> details, String value) {
        if (value != null && !value.isBlank() && !value.equals(name) && !details.contains(value)) {
            details.add(value);
        }
    }
}
