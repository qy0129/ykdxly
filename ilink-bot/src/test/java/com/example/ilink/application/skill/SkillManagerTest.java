package com.example.ilink.application.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SkillManagerTest {

    @TempDir
    Path root;

    @Test
    void discoversSkillDirectoriesWithoutJavaRegistration() throws Exception {
        writeSkill("alpha", "capability_a");
        writeSkill("beta", "capability_b");

        SkillManager manager = SkillManager.load(root, null);

        assertEquals(2, manager.all().size());
        assertNotNull(manager.findByCapability("capability_a"));
        assertNotNull(manager.findByCapability("capability_b"));
        assertEquals(2, manager.capabilityRegistry().all().size());
    }

    @Test
    void rejectsDuplicateCapabilities() throws Exception {
        writeSkill("alpha", "same_capability");
        writeSkill("beta", "same_capability");

        assertThrows(IllegalArgumentException.class, () -> SkillManager.load(root, null));
    }

    private void writeSkill(String directory, String capability) throws Exception {
        Path skillDirectory = Files.createDirectories(root.resolve(directory));
        Files.writeString(skillDirectory.resolve("skill.json"), """
                {
                  "name":"%s",
                  "description":"test",
                  "version":"1.0.0",
                  "enabled":true,
                  "requiresApproval":false,
                  "toolNames":[],
                  "capabilities":[{
                    "name":"%s",
                    "description":"test capability",
                    "parameterHint":"",
                    "interactive":false
                  }]
                }
                """.formatted(directory, capability));
    }
}
