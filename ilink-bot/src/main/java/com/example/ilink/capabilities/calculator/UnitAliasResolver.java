package com.example.ilink.capabilities.calculator;

import java.util.Map;

/**
 * 在单位或币种别名表中查找用户输入的名称。
 *
 * <p>输入允许使用 {@code /} 分隔多个别名，例如 {@code m/meter}。
 * 查找时会依次尝试原始文本、小写和大写形式。</p>
 */
final class UnitAliasResolver {

    private UnitAliasResolver() {
    }

    /** 返回第一个可识别的别名对应值，所有别名都不存在时返回 {@code null}。 */
    static <T> T find(Map<String, T> values, String rawInput) {
        for (String part : rawInput.split("/")) {
            String alias = part.trim();
            if (alias.isEmpty()) {
                continue;
            }
            T value = values.get(alias);
            if (value == null) value = values.get(alias.toLowerCase());
            if (value == null) value = values.get(alias.toUpperCase());
            if (value != null) return value;
        }
        return null;
    }
}
