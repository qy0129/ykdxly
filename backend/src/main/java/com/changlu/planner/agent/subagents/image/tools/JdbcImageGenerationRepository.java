package com.changlu.planner.agent.subagents.image.tools;

import com.changlu.planner.shared.database.Database;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/** ai_images 表的 JDBC 仓储，按用户与工作区隔离。 */
public final class JdbcImageGenerationRepository implements ImageGenerationRepository {
  private final Database database;

  public JdbcImageGenerationRepository(Database database) { this.database = database; }

  @Override public Optional<ImageRecord> findByIdempotencyKey(String idempotencyKey, Database.Context identity) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) return Optional.empty();
    try (Connection c = database.connection();
         PreparedStatement p = c.prepareStatement(
             "SELECT request_id,idempotency_key,prompt,size,style,status,image_url,asset_path,provider,error_message,trace_id,created_at "
                 + "FROM ai_images WHERE workspace_id=? AND user_id=? AND idempotency_key=? LIMIT 1")) {
      p.setBytes(1, Database.uuidBytes(identity.workspaceId()));
      p.setBytes(2, Database.uuidBytes(identity.userId()));
      p.setString(3, idempotencyKey);
      try (ResultSet rs = p.executeQuery()) {
        if (!rs.next()) return Optional.empty();
        return Optional.of(row(rs));
      }
    } catch (SQLException error) {
      throw new IllegalStateException("IMAGE_REPOSITORY_READ_FAILED", error);
    }
  }

  @Override public void save(ImageRecord record, Database.Context identity) {
    try (Connection c = database.connection();
         PreparedStatement p = c.prepareStatement(
             "INSERT INTO ai_images (id,workspace_id,user_id,request_id,idempotency_key,prompt,size,style,status,"
                 + "image_url,asset_path,provider,error_message,trace_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?) "
                 + "ON DUPLICATE KEY UPDATE status=VALUES(status),image_url=VALUES(image_url),asset_path=VALUES(asset_path),"
                 + "error_message=VALUES(error_message),completed_at=NOW()")) {
      p.setBytes(1, Database.uuidBytes(UUID.randomUUID()));
      p.setBytes(2, Database.uuidBytes(identity.workspaceId()));
      p.setBytes(3, Database.uuidBytes(identity.userId()));
      p.setString(4, record.requestId());
      p.setString(5, record.idempotencyKey());
      p.setString(6, record.prompt());
      p.setString(7, record.size());
      p.setString(8, record.style());
      p.setString(9, record.status());
      p.setString(10, record.imageUrl());
      p.setString(11, record.assetPath());
      p.setString(12, record.provider());
      p.setString(13, record.errorMessage());
      p.setString(14, record.traceId());
      p.executeUpdate();
    } catch (SQLException error) {
      throw new IllegalStateException("IMAGE_REPOSITORY_WRITE_FAILED", error);
    }
  }

  private ImageRecord row(ResultSet rs) throws SQLException {
    return new ImageRecord(rs.getString("request_id"), rs.getString("idempotency_key"),
        rs.getString("prompt"), rs.getString("size"), rs.getString("style"), rs.getString("status"),
        rs.getString("image_url"), rs.getString("asset_path"), rs.getString("provider"),
        rs.getString("error_message"), rs.getString("trace_id"), rs.getTimestamp("created_at").getTime());
  }
}
