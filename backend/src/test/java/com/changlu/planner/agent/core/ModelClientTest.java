package com.changlu.planner.agent.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class ModelClientTest {
  @Test void extractsStructuredProviderError() {
    String detail = ModelClient.safeProviderErrorDetail("""
        {"error":{"code":"invalid_request","type":"invalid_request_error","param":"max_tokens",
        "message":"max_tokens 5000 exceeds limit 4096"}}
        """);

    assertEquals("code=invalid_request, type=invalid_request_error, param=max_tokens, "
        + "message=max_tokens 5000 exceeds limit 4096", detail);
  }

  @Test void redactsCredentialsAndLimitsErrorDetail() {
    String detail = ModelClient.safeProviderErrorDetail("""
        {"error":{"message":"Authorization: Bearer sk-secret api_key=visible-secret token=token-secret %s"}}
        """.formatted("x".repeat(600)));

    assertFalse(detail.contains("sk-secret"));
    assertFalse(detail.contains("visible-secret"));
    assertFalse(detail.contains("token-secret"));
    assertTrue(detail.contains("[REDACTED]"));
    assertTrue(detail.length() <= 403);
  }

  @Test void doesNotLogUnstructuredResponseBody() {
    assertEquals("non_json_error_response",
        ModelClient.safeProviderErrorDetail("upstream failure containing private request data"));
  }
}
