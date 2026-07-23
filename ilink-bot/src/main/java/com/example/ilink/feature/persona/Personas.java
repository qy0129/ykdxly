package com.example.ilink.feature.persona;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

/**
 * 角色人设定义。
 *
 * 每个人设对应 src/main/resources/personas/ 下的一个 .txt 文件，
 * 文件名即触发词（如"小甜妹.txt"），内容为详细人设 prompt。
 *
 * 如需新增人设：在 src/main/resources/personas/ 下创建 {触发词}.txt 文件，
 * 写入详细人设描述后重启机器人即可。
 *
 * 使用示例：
 *   history.setPersona(userId, "小甜妹");
 *   或
 *   history.setPersona(userId, "毒舌");
 */
/**
 * 人设资源加载器。
 *
 * <p>扫描 classpath 的 {@code personas/} 目录并加载全部文本提示词，
 * 不依赖额外的索引文件。</p>
 */
public class Personas {

    private static final Map<String, String> ALL = new LinkedHashMap<>();
    private static final Map<String, String> VOICE_STYLES = Map.of(
            "温柔", "warm",
            "成熟男性", "male",
            "成熟女性", "female",
            "阳光男孩", "boy",
            "活泼女孩", "girl",
            "活泼伙伴", "lively");

    static {
        loadFromResources();
    }

    /** 从资源目录或打包 JAR 中扫描全部 .txt 人设文件。 */
    private static void loadFromResources() {
        try {
            Enumeration<URL> directories = Personas.class.getClassLoader().getResources("personas");
            while (directories.hasMoreElements()) loadDirectory(directories.nextElement());
        } catch (Exception e) {
            System.err.println("[Personas] 加载人设文件失败: " + e.getMessage());
        }
    }

    /** 分别处理开发环境的文件目录和生产环境的 JAR 资源目录。 */
    private static void loadDirectory(URL directory) throws Exception {
        if ("file".equals(directory.getProtocol())) {
            File[] files = new File(directory.toURI()).listFiles((dir, name) -> name.endsWith(".txt"));
            if (files != null) {
                for (File file : files) loadPersona(file.getName());
            }
            return;
        }
        if ("jar".equals(directory.getProtocol())) {
            JarURLConnection connection = (JarURLConnection) directory.openConnection();
            try (JarFile jar = connection.getJarFile()) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (name.startsWith("personas/") && name.endsWith(".txt")) {
                        loadPersona(name.substring("personas/".length()));
                    }
                }
            }
        }
    }

    /** 统一读取一个发现到的人设文件，文件名即用户可见的人设名称。 */
    private static void loadPersona(String fileName) {
        String content = loadResource("/personas/" + fileName);
        if (content != null && !content.isBlank()) {
            ALL.put(fileName.replaceFirst("\\.txt$", ""), content);
        }
    }

    /** 默认人设触发词，用户未设置人设时生效。设为 null 则不启用默认人设。 */
    public static final String DEFAULT = "温柔";

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

    /** 返回人格绑定的默认音色；没有单独绑定时使用系统默认音色。 */
    public static String voiceStyle(String name) {
        return VOICE_STYLES.getOrDefault(name, "default");
    }

    /** 返回全部人设的只读视图，供提示和校验使用。 */
    public static Map<String, String> getAll() {
        return ALL;
    }
}
