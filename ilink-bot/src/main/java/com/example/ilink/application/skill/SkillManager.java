package com.example.ilink.application.skill;

import com.example.ilink.application.routing.CapabilityDefinition;
import com.example.ilink.application.routing.CapabilityRegistry;
import com.example.ilink.application.tooling.ToolManager;
import com.example.ilink.application.messaging.ConsoleLog;
import com.google.gson.Gson;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** 扫描 src/skills 或 JAR 内 skills 目录并自动注册，新增 Skill 无需修改 Java 代码。 */
public final class SkillManager {
    private final Map<String, Skill> skills;
    private final Map<String, Skill> capabilities;

    private SkillManager(List<SkillDefinition> definitions, ToolManager toolManager) {
        Map<String, Skill> loadedSkills = new LinkedHashMap<>();
        Map<String, Skill> loadedCapabilities = new LinkedHashMap<>();
        for (SkillDefinition definition : definitions) {
            if (!definition.enabled()) continue;
            ConfiguredSkill skill = new ConfiguredSkill(definition, toolManager);
            if (loadedSkills.putIfAbsent(definition.name(), skill) != null) {
                throw new IllegalArgumentException("Skill 名称重复：" + definition.name());
            }
            for (SkillCapability capability : definition.capabilities()) {
                if (capability.name().isBlank()) throw new IllegalArgumentException("Skill 能力名称不能为空");
                if (loadedCapabilities.putIfAbsent(capability.name(), skill) != null) {
                    throw new IllegalArgumentException("Skill 能力重复：" + capability.name());
                }
            }
        }
        if (loadedSkills.isEmpty()) throw new IllegalStateException("没有发现可用的 Skill");
        skills = Map.copyOf(loadedSkills);
        capabilities = Map.copyOf(loadedCapabilities);
        for (Skill skill : skills.values()) {
            SkillDefinition definition = skill.definition();
            ConsoleLog.info("技能管理", "已加载 Skill：" + definition.name() + "，版本=" + definition.version()
                    + "，能力=" + definition.capabilities().stream().map(SkillCapability::name).toList()
                    + "，可调用工具=" + definition.toolNames());
        }
    }

    public static SkillManager loadDefault(ToolManager toolManager) {
        Path sourceRoot = Path.of("src", "skills");
        if (Files.isDirectory(sourceRoot)) return load(sourceRoot, toolManager);
        return new SkillManager(loadClasspathDefinitions(), toolManager);
    }

    public static SkillManager load(Path root, ToolManager toolManager) {
        Gson gson = new Gson();
        List<SkillDefinition> definitions = new ArrayList<>();
        try (var paths = Files.walk(root, 2)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().equals("skill.json")).sorted().toList()) {
                definitions.add(gson.fromJson(Files.readString(path, StandardCharsets.UTF_8), SkillDefinition.class));
            }
        } catch (IOException error) {
            throw new IllegalStateException("扫描 Skill 失败：" + root, error);
        }
        return new SkillManager(definitions, toolManager);
    }

    public List<Skill> all() {
        return List.copyOf(skills.values());
    }

    public Skill findByCapability(String capability) {
        return capabilities.get(capability);
    }

    public CapabilityRegistry capabilityRegistry() {
        List<CapabilityDefinition> definitions = new ArrayList<>();
        for (Skill skill : skills.values()) {
            for (SkillCapability capability : skill.definition().capabilities()) {
                definitions.add(new CapabilityDefinition(capability.name(), capability.description(),
                        capability.parameterHint(), capability.interactive()));
            }
        }
        return new CapabilityRegistry(definitions);
    }

    public SkillResult execute(SkillRequest request, SkillContext context) {
        Skill skill = findByCapability(request.capability());
        if (skill == null) {
            ConsoleLog.warn("技能调用", "未找到能力，能力编号=" + request.capability());
            return SkillResult.failure("未找到能力：" + request.capability());
        }
        long startedAt = System.nanoTime();
        SkillDefinition definition = skill.definition();
        ConsoleLog.info("技能调用", "开始执行 Skill：" + definition.name() + "，能力=" + request.capability()
                + "，工具=" + request.toolName() + "，参数摘要=" + ConsoleLog.summary(request.arguments().toString()));
        try {
            SkillResult result = skill.execute(request, context);
            ConsoleLog.info("技能结果", "Skill=" + definition.name() + "，执行状态="
                    + (result.success() ? "成功" : "失败") + "，耗时=" + elapsedMillis(startedAt)
                    + "毫秒，结果摘要=" + ConsoleLog.summary(result.output()));
            return result;
        } catch (RuntimeException error) {
            ConsoleLog.error("技能结果", "Skill=" + definition.name() + "，执行状态=失败，耗时="
                    + elapsedMillis(startedAt) + "毫秒，" + ConsoleLog.errorSummary(error));
            throw error;
        }
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private static List<SkillDefinition> loadClasspathDefinitions() {
        Gson gson = new Gson();
        List<SkillDefinition> definitions = new ArrayList<>();
        try {
            Enumeration<URL> roots = SkillManager.class.getClassLoader().getResources("skills");
            while (roots.hasMoreElements()) {
                URL root = roots.nextElement();
                if ("file".equals(root.getProtocol())) {
                    try (var paths = Files.walk(Path.of(root.toURI()), 2)) {
                        for (Path path : paths.filter(Files::isRegularFile)
                                .filter(file -> file.getFileName().toString().equals("skill.json")).sorted().toList()) {
                            definitions.add(gson.fromJson(Files.readString(path), SkillDefinition.class));
                        }
                    }
                } else if ("jar".equals(root.getProtocol())) {
                    JarURLConnection connection = (JarURLConnection) root.openConnection();
                    try (JarFile jar = connection.getJarFile()) {
                        Enumeration<JarEntry> entries = jar.entries();
                        while (entries.hasMoreElements()) {
                            JarEntry entry = entries.nextElement();
                            if (!entry.isDirectory() && entry.getName().matches("skills/[^/]+/skill\\.json")) {
                                try (InputStream input = jar.getInputStream(entry)) {
                                    definitions.add(gson.fromJson(
                                            new String(input.readAllBytes(), StandardCharsets.UTF_8),
                                            SkillDefinition.class));
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception error) {
            throw new IllegalStateException("扫描 classpath Skill 失败", error);
        }
        return definitions;
    }
}
