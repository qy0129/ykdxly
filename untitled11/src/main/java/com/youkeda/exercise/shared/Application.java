package com.youkeda.exercise.shared;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.youkeda.exercise.shared.config.AmapConfig;

@SpringBootApplication
@EnableConfigurationProperties(AmapConfig.class)
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
