package com.example.ilink.platform.sdk;

import com.example.ilink.bootstrap.Config;
import com.github.wechat.ilink.sdk.core.context.ContextKey;
import com.github.wechat.ilink.sdk.core.context.ConversationContext;
import com.github.wechat.ilink.sdk.core.context.ResumeContext;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 将 SDK 登录凭证、更新游标和会话 token 保存到本地，供进程重启后恢复。 */
public final class SdkResumeContextStore {

    private final Path file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public SdkResumeContextStore() {
        this(Config.SDK_RESUME_CONTEXT_FILE);
    }

    public SdkResumeContextStore(Path file) {
        this.file = file;
    }

    public synchronized void save(ResumeContext context) {
        if (context == null || context.getLoginContext() == null) return;
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            StoredResume stored = toStored(context);
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, gson.toJson(stored), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException unsupportedAtomicMove) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            System.err.println("[SDK 上下文] 保存失败: " + e.getMessage());
        }
    }

    public synchronized ResumeContext load() {
        if (!Files.exists(file)) return null;
        try {
            StoredResume stored = gson.fromJson(Files.readString(file, StandardCharsets.UTF_8), StoredResume.class);
            if (stored == null || blank(stored.botToken()) || blank(stored.botId()) || blank(stored.baseUrl())) {
                return null;
            }
            LoginContext login = new LoginContext(stored.botToken(), stored.userId(),
                    stored.botId(), stored.baseUrl());
            Map<String, ConversationContext> contexts = new LinkedHashMap<>();
            if (stored.conversations() != null) {
                for (StoredConversation item : stored.conversations()) {
                    if (item == null || blank(item.userId()) || blank(item.contextToken())) continue;
                    ConversationContext conversation = new ConversationContext(
                            new ContextKey(stored.botId(), item.userId()));
                    conversation.updateContextToken(item.contextToken(), item.sourceMessageId(), item.sourceMessageTime());
                    contexts.put(item.userId(), conversation);
                }
            }
            return ResumeContext.builder(login)
                    .updatesCursor(stored.updatesCursor())
                    .conversationContexts(contexts)
                    .build();
        } catch (Exception e) {
            System.err.println("[SDK 上下文] 读取失败，将重新扫码登录: " + e.getMessage());
            return null;
        }
    }

    public synchronized void clear() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            System.err.println("[SDK 上下文] 清理失败: " + e.getMessage());
        }
    }

    private StoredResume toStored(ResumeContext context) {
        LoginContext login = context.getLoginContext();
        List<StoredConversation> conversations = context.getConversationContextMap().entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue().hasContextToken())
                .map(entry -> new StoredConversation(entry.getKey(), entry.getValue().getLatestContextToken(),
                        entry.getValue().getSourceMessageId(), entry.getValue().getSourceMessageTime()))
                .toList();
        return new StoredResume(login.getBotToken(), login.getUserId(), login.getBotId(), login.getBaseUrl(),
                context.getUpdatesCursor(), conversations);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record StoredResume(String botToken, String userId, String botId, String baseUrl,
                                String updatesCursor, List<StoredConversation> conversations) {
    }

    private record StoredConversation(String userId, String contextToken,
                                      Long sourceMessageId, Long sourceMessageTime) {
    }
}
