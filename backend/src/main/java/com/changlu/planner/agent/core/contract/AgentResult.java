package com.changlu.planner.agent.core.contract;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.ArrayList;

/** Standard result plus a compatibility projection for the current web and WeChat clients. */
public record AgentResult(
    String schemaVersion,
    AgentStatus status,
    String message,
    JsonObject data,
    List<AgentError> errors,
    String traceId,
    boolean requiresConfirmation,
    String draftId
) {
  public AgentResult {
    schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? "1.0" : schemaVersion;
    status = status == null ? AgentStatus.FAILED : status;
    message = message == null ? "" : message;
    data = data == null ? new JsonObject() : data.deepCopy();
    errors = errors == null ? List.of() : List.copyOf(errors);
    traceId = traceId == null ? "" : traceId;
  }

  public static AgentResult completed(String message, JsonObject data, String traceId) {
    return new AgentResult("1.0", AgentStatus.COMPLETED, message, data, List.of(), traceId, false, null);
  }

  public static AgentResult waitingUser(String message, JsonObject data, String traceId) {
    return new AgentResult("1.0", AgentStatus.WAITING_USER, message, data, List.of(), traceId, false, null);
  }

  public static AgentResult failed(String code, String message, boolean retryable, String traceId) {
    return new AgentResult("1.0", AgentStatus.FAILED, message, new JsonObject(),
        List.of(new AgentError(code, message, retryable, new JsonObject())), traceId, false, null);
  }

  public static AgentResult fromLegacy(JsonObject legacy, String traceId) {
    JsonObject value = legacy == null ? new JsonObject() : legacy.deepCopy();
    JsonArray questions = value.has("questions") && value.get("questions").isJsonArray()
        ? value.getAsJsonArray("questions") : new JsonArray();
    boolean confirmation = value.has("draft") && value.get("draft").isJsonObject();
    AgentStatus status = confirmation ? AgentStatus.WAITING_CONFIRMATION
        : questions.isEmpty() ? AgentStatus.COMPLETED : AgentStatus.WAITING_USER;
    String message = value.has("reply") && !value.get("reply").isJsonNull()
        ? value.get("reply").getAsString() : "已完成。";
    String draftId = confirmation && value.getAsJsonObject("draft").has("id")
        ? value.getAsJsonObject("draft").get("id").getAsString() : null;
    return new AgentResult("1.0", status, message, value, List.of(), traceId, confirmation, draftId);
  }

  public static AgentResult fromJson(JsonObject value) {
    String rawStatus = value.has("status") ? value.get("status").getAsString() : "failed";
    AgentStatus status = AgentStatus.valueOf(rawStatus.toUpperCase());
    JsonObject data = value.has("data") && value.get("data").isJsonObject()
        ? value.getAsJsonObject("data") : new JsonObject();
    List<AgentError> errors = new ArrayList<>();
    if (value.has("errors") && value.get("errors").isJsonArray()) {
      for (JsonElement element : value.getAsJsonArray("errors")) {
        JsonObject row = element.getAsJsonObject();
        errors.add(new AgentError(row.get("code").getAsString(), row.get("message").getAsString(),
            row.has("retryable") && row.get("retryable").getAsBoolean(),
            row.has("details") && row.get("details").isJsonObject()
                ? row.getAsJsonObject("details") : new JsonObject()));
      }
    }
    return new AgentResult(value.has("schemaVersion") ? value.get("schemaVersion").getAsString() : "1.0",
        status, value.has("message") ? value.get("message").getAsString() : "", data, errors,
        value.has("traceId") ? value.get("traceId").getAsString() : "",
        value.has("requiresConfirmation") && value.get("requiresConfirmation").getAsBoolean(),
        value.has("draftId") && !value.get("draftId").isJsonNull()
            ? value.get("draftId").getAsString() : null);
  }

  public JsonObject toJson() {
    JsonObject value = new JsonObject();
    value.addProperty("schemaVersion", schemaVersion);
    value.addProperty("status", status.jsonValue());
    value.addProperty("message", message);
    value.add("data", data.deepCopy());
    JsonArray errorRows = new JsonArray();
    for (AgentError error : errors) {
      JsonObject row = new JsonObject();
      row.addProperty("code", error.code());
      row.addProperty("message", error.message());
      row.addProperty("retryable", error.retryable());
      row.add("details", error.details().deepCopy());
      errorRows.add(row);
    }
    value.add("errors", errorRows);
    value.addProperty("traceId", traceId);
    value.addProperty("requiresConfirmation", requiresConfirmation);
    if (draftId == null) value.add("draftId", com.google.gson.JsonNull.INSTANCE);
    else value.addProperty("draftId", draftId);

    // Existing clients still read reply/questions/actions/draft at the top level.
    for (String key : data.keySet()) {
      if (!value.has(key)) value.add(key, data.get(key).deepCopy());
    }
    if (!value.has("reply")) value.addProperty("reply", message);
    if (!value.has("questions")) value.add("questions", new JsonArray());
    if (!value.has("actions")) value.add("actions", new JsonArray());
    return value;
  }
}
