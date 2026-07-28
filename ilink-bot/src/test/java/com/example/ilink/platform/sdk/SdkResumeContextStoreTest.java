package com.example.ilink.platform.sdk;

import com.github.wechat.ilink.sdk.core.context.ContextKey;
import com.github.wechat.ilink.sdk.core.context.ConversationContext;
import com.github.wechat.ilink.sdk.core.context.ResumeContext;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SdkResumeContextStoreTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void savesAndRestoresLoginAndConversationContext() {
        LoginContext login = new LoginContext("bot-token", "owner", "bot-id", "https://example.com");
        ConversationContext conversation = new ConversationContext(new ContextKey("bot-id", "user-id"));
        conversation.updateContextToken("context-token", 12L, 34L);
        ResumeContext original = ResumeContext.builder(login)
                .updatesCursor("cursor")
                .conversationContexts(Map.of("user-id", conversation))
                .build();

        SdkResumeContextStore store = new SdkResumeContextStore(temporaryDirectory.resolve("resume.json"));
        store.save(original);
        ResumeContext restored = store.load();

        assertNotNull(restored);
        assertEquals("bot-token", restored.getLoginContext().getBotToken());
        assertEquals("cursor", restored.getUpdatesCursor());
        assertTrue(restored.getConversationContextMap().get("user-id").hasContextToken());
        assertEquals("context-token", restored.getConversationContextMap().get("user-id").getLatestContextToken());
    }
}
