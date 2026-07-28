package com.example.ilink.platform.http;

import java.net.http.HttpClient;
import java.time.Duration;

/** 统一创建应用使用的 HTTP 客户端。 */
public final class HttpClientFactory {

    private HttpClientFactory() {
    }

    public static HttpClient create(Duration connectTimeout) {
        return HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();
    }
}
