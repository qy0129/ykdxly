package com.example.ilink.feature.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MemoryServiceTest {

    @Test
    void refusesSensitiveMemory() {
        MemoryService service = new MemoryService();
        assertTrue(service.remember("user", "记住我的银行卡密码是123456").contains("不会"));
    }

    @Test
    void remembersAndForgetsHomeLocationWithoutDatabase() {
        MemoryService service = new MemoryService();
        service.remember("location-user", "记住我住在杭州");
        assertEquals("杭州", service.value("location-user", "home_location"));

        assertTrue(service.forget("location-user", "忘掉我的住址").contains("已经忘掉"));
        assertEquals("", service.value("location-user", "home_location"));
    }
}
