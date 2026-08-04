package com.changlu.planner.agent.subagents.document.rag;

import com.changlu.planner.agent.subagents.document.DocumentResult;
import com.changlu.planner.shared.database.Database;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** RAG 文档与向量片段的数据库边界。 */
final class RagRepository {
  private final Database database;
  private final Gson gson = new Gson();

  RagRepository(Database database) { this.database = database; }

  Optional<DocumentResult> find(Database.Context context, String contentHash) throws Exception {
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
        "SELECT id,file_name,extension,extracted_chars,vector_indexed FROM agent_documents "
            + "WHERE workspace_id=? AND user_id=? AND content_hash=?")) {
      owner(p, context);
      p.setString(3, contentHash);
      try (ResultSet rs = p.executeQuery()) {
        if (!rs.next()) return Optional.empty();
        UUID id = Database.bytesUuid(rs.getBytes("id"));
        int chunks = chunkCount(c, id);
        return Optional.of(new DocumentResult(id, rs.getString("file_name"), rs.getString("extension"),
            rs.getInt("extracted_chars"), chunks, rs.getBoolean("vector_indexed"), true, ""));
      }
    }
  }

  DocumentResult save(Database.Context context, UUID id, String fileName, String mediaType,
                      String extension, String hash, String text, List<StoredChunk> chunks,
                      boolean vectorIndexed) throws Exception {
    try (Connection c = database.connection()) {
      c.setAutoCommit(false);
      try (PreparedStatement document = c.prepareStatement(
          "INSERT INTO agent_documents (id,workspace_id,user_id,file_name,media_type,extension,content_hash,"
              + "extracted_chars,vector_indexed) VALUES (?,?,?,?,?,?,?,?,?)");
           PreparedStatement chunk = c.prepareStatement(
               "INSERT INTO agent_document_chunks (id,document_id,workspace_id,user_id,chunk_index,content,embedding) "
                   + "VALUES (?,?,?,?,?,?,?)")) {
        document.setBytes(1, Database.uuidBytes(id));
        document.setBytes(2, Database.uuidBytes(context.workspaceId()));
        document.setBytes(3, Database.uuidBytes(context.userId()));
        document.setString(4, fileName);
        document.setString(5, mediaType == null ? "" : mediaType);
        document.setString(6, extension);
        document.setString(7, hash);
        document.setInt(8, text.length());
        document.setBoolean(9, vectorIndexed);
        document.executeUpdate();
        for (int index = 0; index < chunks.size(); index++) {
          StoredChunk item = chunks.get(index);
          chunk.setBytes(1, Database.uuidBytes(UUID.randomUUID()));
          chunk.setBytes(2, Database.uuidBytes(id));
          chunk.setBytes(3, Database.uuidBytes(context.workspaceId()));
          chunk.setBytes(4, Database.uuidBytes(context.userId()));
          chunk.setInt(5, index);
          chunk.setString(6, item.content());
          chunk.setString(7, item.embedding() == null ? null : gson.toJson(item.embedding()));
          chunk.addBatch();
        }
        chunk.executeBatch();
        c.commit();
        return new DocumentResult(id, fileName, extension, text.length(), chunks.size(), vectorIndexed,
            false, preview(text));
      } catch (Exception error) {
        c.rollback();
        throw error;
      } finally {
        c.setAutoCommit(true);
      }
    }
  }

  String contextForDocuments(Database.Context context, List<UUID> ids, int maxChars) throws Exception {
    StringBuilder value = new StringBuilder();
    for (UUID id : ids) {
      try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
          "SELECT d.file_name,c.chunk_index,c.content FROM agent_document_chunks c "
              + "JOIN agent_documents d ON d.id=c.document_id "
              + "WHERE d.id=? AND d.workspace_id=? AND d.user_id=? ORDER BY c.chunk_index")) {
        p.setBytes(1, Database.uuidBytes(id));
        p.setBytes(2, Database.uuidBytes(context.workspaceId()));
        p.setBytes(3, Database.uuidBytes(context.userId()));
        try (ResultSet rs = p.executeQuery()) {
          while (rs.next() && value.length() < maxChars) {
            value.append("[来源：").append(rs.getString("file_name")).append("，片段 ")
                .append(rs.getInt("chunk_index") + 1).append("]\n")
                .append(rs.getString("content")).append("\n\n");
          }
        }
      }
      if (value.length() >= maxChars) break;
    }
    return limit(value.toString(), maxChars);
  }

  List<VectorChunk> vectorChunks(Database.Context context) throws Exception {
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
        "SELECT d.file_name,c.chunk_index,c.content,c.embedding FROM agent_document_chunks c "
            + "JOIN agent_documents d ON d.id=c.document_id "
            + "WHERE c.workspace_id=? AND c.user_id=? AND c.embedding IS NOT NULL "
            + "ORDER BY d.created_at DESC,c.chunk_index LIMIT 2000")) {
      owner(p, context);
      try (ResultSet rs = p.executeQuery()) {
        List<VectorChunk> result = new ArrayList<>();
        while (rs.next()) result.add(new VectorChunk(rs.getString("file_name"), rs.getInt("chunk_index"),
            rs.getString("content"), vector(rs.getString("embedding"))));
        return List.copyOf(result);
      }
    }
  }

  String latestContext(Database.Context context, int maxChars) throws Exception {
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
        "SELECT d.file_name,c.chunk_index,c.content FROM agent_document_chunks c "
            + "JOIN agent_documents d ON d.id=c.document_id WHERE c.workspace_id=? AND c.user_id=? "
            + "ORDER BY d.created_at DESC,c.chunk_index LIMIT 30")) {
      owner(p, context);
      StringBuilder value = new StringBuilder();
      try (ResultSet rs = p.executeQuery()) {
        while (rs.next() && value.length() < maxChars) value.append("[来源：")
            .append(rs.getString("file_name")).append("，片段 ").append(rs.getInt("chunk_index") + 1)
            .append("]\n").append(rs.getString("content")).append("\n\n");
      }
      return limit(value.toString(), maxChars);
    }
  }

  void delete(Database.Context context, UUID documentId) throws Exception {
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
        "DELETE FROM agent_documents WHERE id=? AND workspace_id=? AND user_id=?")) {
      p.setBytes(1, Database.uuidBytes(documentId));
      p.setBytes(2, Database.uuidBytes(context.workspaceId()));
      p.setBytes(3, Database.uuidBytes(context.userId()));
      if (p.executeUpdate() == 0) throw new IllegalArgumentException("document_not_found");
    }
  }

  private int chunkCount(Connection c, UUID documentId) throws Exception {
    try (PreparedStatement p = c.prepareStatement(
        "SELECT COUNT(*) FROM agent_document_chunks WHERE document_id=?")) {
      p.setBytes(1, Database.uuidBytes(documentId));
      try (ResultSet rs = p.executeQuery()) { rs.next(); return rs.getInt(1); }
    }
  }

  private List<Float> vector(String json) {
    JsonArray values = JsonParser.parseString(json).getAsJsonArray();
    List<Float> result = new ArrayList<>(values.size());
    values.forEach(value -> result.add(value.getAsFloat()));
    return List.copyOf(result);
  }

  private void owner(PreparedStatement p, Database.Context context) throws Exception {
    p.setBytes(1, Database.uuidBytes(context.workspaceId()));
    p.setBytes(2, Database.uuidBytes(context.userId()));
  }

  private String preview(String text) { return limit(text.replaceAll("\\s+", " "), 180); }
  private String limit(String text, int maxChars) {
    return text.length() <= maxChars ? text : text.substring(0, maxChars);
  }

  record StoredChunk(String content, List<Float> embedding) {}
  record VectorChunk(String fileName, int index, String content, List<Float> embedding) {}
}
