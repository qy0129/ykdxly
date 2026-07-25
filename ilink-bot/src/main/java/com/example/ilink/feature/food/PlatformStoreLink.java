package com.example.ilink.feature.food;

/** 外卖平台链接；direct 表示已经匹配到平台内部门店 ID。 */
public record PlatformStoreLink(String platform, String url, boolean direct) { }
