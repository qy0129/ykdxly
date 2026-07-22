package com.example.ilink.feature.persona;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 角色人设定义。
 *
 * 每个人设对应 src/main/resources/personas/ 下的一个 .txt 文件，
 * 文件名即触发词（如"小甜妹.txt"），内容为详细人设 prompt。
 *
 * 如需新增人设：
 * 1. 在 src/main/resources/personas/ 下创建 {触发词}.txt 文件
 * 2. 在同目录的 index.txt 中增加该文件名
 * 3. 写入详细的人设描述并重启机器人
 *
 * 使用示例：
 *   history.setPersona(userId, "小甜妹");
 *   或
 *   history.setPersona(userId, "毒舌");
 */
/**
 * 人设资源加载器。
 *
 * <p>从 classpath 的 {@code personas/index.txt} 读取人设名称，再加载对应的
 * 文本提示词。业务层只通过名称获取提示词，不直接操作资源文件。</p>
 */
public class Personas {

    private static final Map<String, String> ALL = new LinkedHashMap<>();

    static {
        loadFromResources();
    }

    /** 从 index.txt 加载全部人设名称和对应提示词。 */
    private static void loadFromResources() {
        try {
            String index = loadResource("/personas/index.txt");
            if (index == null) {
                System.err.println("[Personas] 未找到人格索引文件: /personas/index.txt");
                return;
            }

            for (String line : index.split("\\R")) {
                String fileName = line.trim();
                if (fileName.isEmpty() || fileName.startsWith("#")) {
                    continue;
                }

                String key = fileName.replaceFirst("\\.txt$", "");
                String content = loadResource("/personas/" + fileName);
                if (content != null && !content.isBlank()) {
                    ALL.put(key, content);
                } else {
                    System.err.println("[Personas] 未找到人格文件: " + fileName);
                }
            }
        } catch (Exception e) {
            System.err.println("[Personas] 加载人设文件失败: " + e.getMessage());
        }
    }

    /** 默认人设触发词，用户未设置人设时生效。设为 null 则不启用默认人设。 */
    public static final String DEFAULT = "鲁迅";   // ← 改成 "小甜妹" 等即启用默认

    /** 以 UTF-8 读取 classpath 中的人设文本文件。 */
    private static String loadResource(String path) {
        try (InputStream is = Personas.class.getResourceAsStream(path)) {
            if (is == null) return null;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** 按人设名称获取提示词，不存在时返回 null。 */
    public static String get(String name) {
        return ALL.get(name);
    }

    /** 返回全部人设的只读视图，供提示和校验使用。 */
    public static Map<String, String> getAll() {
        return ALL;
    }
}
