package com.wechat.link.llm.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * LLM 模块自动配置类
 * <p>
 * 注册 WebClient Bean，用于与 LLM 服务进行 HTTP 通信。
 * </p>
 *
 * @author wechat-link
 */
@Configuration
public class LLMConfig {

    @Bean("llmWebClient")
    public WebClient llmWebClient(LLMProperties properties) {
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofSeconds(properties.getTimeout()));

        return WebClient.builder()
                .baseUrl(properties.getBaseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader("Content-Type", "application/json")
                .build();
    }
}
