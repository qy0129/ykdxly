package com.changlu.planner.integrations.wechat;

import com.changlu.planner.shared.database.Database;
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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 将微信 SDK 的完整恢复上下文保存到 MySQL，旧 JSON 仅用于迁移和故障兜底。 */
public final class ResumeContextStore {
  private final Database database;
  private final Path legacyFile;
  private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

  public ResumeContextStore(Database database) {
    this(database, Path.of(System.getenv().getOrDefault("PLANNER_SDK_RESUME_FILE", "data/sdk-resume-context.json")));
  }

  ResumeContextStore(Database database, Path legacyFile) {
    this.database = database;
    this.legacyFile = legacyFile;
  }

  public synchronized void save(ResumeContext context) {
    if (context == null || context.getLoginContext() == null) return;
    StoredResume stored = toStored(context);
    if (blank(stored.userId())) return;
    String sql = """
        INSERT INTO wechat_login_sessions
          (wechat_user_id, bot_token, bot_id, base_url, updates_cursor, conversations_json)
        VALUES (?, ?, ?, ?, ?, ?)
        ON DUPLICATE KEY UPDATE
          bot_token = VALUES(bot_token), bot_id = VALUES(bot_id), base_url = VALUES(base_url),
          updates_cursor = VALUES(updates_cursor), conversations_json = VALUES(conversations_json)
        """;
    try (Connection connection = database.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, stored.userId());
      statement.setString(2, stored.botToken());
      statement.setString(3, stored.botId());
      statement.setString(4, stored.baseUrl());
      statement.setString(5, stored.updatesCursor());
      statement.setString(6, gson.toJson(stored.conversations()));
      statement.executeUpdate();
    } catch (Exception error) {
      System.err.println("[微信登录态] 数据库保存失败，改存本地备用文件: " + error.getMessage());
      saveLegacy(stored);
    }
  }

  public synchronized ResumeContext load() {
    StoredResume stored = loadDatabase();
    if (stored != null) return toContext(stored);
    stored = loadLegacy();
    if (stored == null) return null;
    ResumeContext context = toContext(stored);
    save(context);
    System.out.println("[微信登录态] 已将旧登录态迁移到数据库");
    return context;
  }

  public synchronized void clear() {
    try (Connection connection = database.connection(); PreparedStatement statement = connection.prepareStatement("DELETE FROM wechat_login_sessions")) {
      statement.executeUpdate();
    } catch (Exception error) {
      System.err.println("[微信登录态] 数据库清理失败: " + error.getMessage());
    }
    try { Files.deleteIfExists(legacyFile); }
    catch (IOException error) { System.err.println("[微信登录态] 备用文件清理失败: " + error.getMessage()); }
  }

  private StoredResume loadDatabase() {
    String sql = "SELECT wechat_user_id, bot_token, bot_id, base_url, updates_cursor, conversations_json FROM wechat_login_sessions ORDER BY updated_at DESC LIMIT 1";
    try (Connection connection = database.connection(); PreparedStatement statement = connection.prepareStatement(sql); ResultSet result = statement.executeQuery()) {
      if (!result.next()) return null;
      StoredConversation[] conversations = gson.fromJson(result.getString("conversations_json"), StoredConversation[].class);
      return new StoredResume(
          result.getString("bot_token"), result.getString("wechat_user_id"), result.getString("bot_id"),
          result.getString("base_url"), result.getString("updates_cursor"),
          conversations == null ? List.of() : Arrays.asList(conversations));
    } catch (Exception error) {
      System.err.println("[微信登录态] 数据库读取失败: " + error.getMessage());
      return null;
    }
  }

  private StoredResume loadLegacy() {
    if (!Files.exists(legacyFile)) return null;
    try {
      StoredResume stored = gson.fromJson(Files.readString(legacyFile, StandardCharsets.UTF_8), StoredResume.class);
      return valid(stored) ? stored : null;
    } catch (Exception error) {
      System.err.println("[微信登录态] 备用文件读取失败: " + error.getMessage());
      return null;
    }
  }

  private void saveLegacy(StoredResume stored) {
    try {
      Path parent = legacyFile.toAbsolutePath().getParent();
      if (parent != null) Files.createDirectories(parent);
      Path temporary = legacyFile.resolveSibling(legacyFile.getFileName() + ".tmp");
      Files.writeString(temporary, gson.toJson(stored), StandardCharsets.UTF_8);
      try { Files.move(temporary, legacyFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
      catch (IOException unsupportedAtomicMove) { Files.move(temporary, legacyFile, StandardCopyOption.REPLACE_EXISTING); }
    } catch (Exception error) {
      System.err.println("[微信登录态] 备用文件保存失败: " + error.getMessage());
    }
  }

  private ResumeContext toContext(StoredResume stored) {
    if (!valid(stored)) return null;
    LoginContext login = new LoginContext(stored.botToken(), stored.userId(), stored.botId(), stored.baseUrl());
    Map<String, ConversationContext> contexts = new LinkedHashMap<>();
    if (stored.conversations() != null) {
      for (StoredConversation item : stored.conversations()) {
        if (item == null || blank(item.userId()) || blank(item.contextToken())) continue;
        ConversationContext conversation = new ConversationContext(new ContextKey(stored.botId(), item.userId()));
        conversation.updateContextToken(item.contextToken(), item.sourceMessageId(), item.sourceMessageTime());
        contexts.put(item.userId(), conversation);
      }
    }
    return ResumeContext.builder(login).updatesCursor(stored.updatesCursor()).conversationContexts(contexts).build();
  }

  private StoredResume toStored(ResumeContext context) {
    LoginContext login = context.getLoginContext();
    List<StoredConversation> conversations = context.getConversationContextMap().entrySet().stream()
        .filter(entry -> entry.getValue() != null && entry.getValue().hasContextToken())
        .map(entry -> new StoredConversation(entry.getKey(), entry.getValue().getLatestContextToken(), entry.getValue().getSourceMessageId(), entry.getValue().getSourceMessageTime()))
        .toList();
    return new StoredResume(login.getBotToken(), login.getUserId(), login.getBotId(), login.getBaseUrl(), context.getUpdatesCursor(), conversations);
  }

  private boolean valid(StoredResume stored) { return stored != null && !blank(stored.botToken()) && !blank(stored.userId()) && !blank(stored.botId()) && !blank(stored.baseUrl()); }
  private boolean blank(String value) { return value == null || value.isBlank(); }
  private record StoredResume(String botToken, String userId, String botId, String baseUrl, String updatesCursor, List<StoredConversation> conversations) {}
  private record StoredConversation(String userId, String contextToken, Long sourceMessageId, Long sourceMessageTime) {}
}
