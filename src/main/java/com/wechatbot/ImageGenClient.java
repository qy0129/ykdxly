package com.wechatbot;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public class ImageGenClient {
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();

    public byte[] generate(String prompt) throws Exception {
        var url = "https://image.pollinations.ai/prompt/"
                + URLEncoder.encode(prompt + "，高质量，详细", StandardCharsets.UTF_8);
        var req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET().build();
        var res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() != 200) throw new RuntimeException("Pollinations HTTP " + res.statusCode());
        return res.body();
    }
}
