package com.example.ilink.feature.food;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** 生成外卖平台的移动端餐厅搜索链接。 */
public final class LinkShortener {

    private LinkShortener() { }

    public static String meituanUrl(String keyword) {
        return "https://h5.waimai.meituan.com/waimai/msearch/search?key=" + encode(keyword);
    }

    public static String elemeUrl(String keyword) {
        return "https://h5.ele.me/search?keyword=" + encode(keyword);
    }

    public static String meituanStoreUrl(String storeId) {
        return "https://h5.waimai.meituan.com/waimai/mshop/" + encode(storeId);
    }

    public static String elemeStoreUrl(String storeId) {
        return "https://h5.ele.me/shop/#id=" + encode(storeId);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
