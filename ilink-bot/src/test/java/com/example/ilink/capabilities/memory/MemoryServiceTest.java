package com.example.ilink.capabilities.memory;

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

    @Test
    void automaticallyKeepsStableFactsButNotTemporaryLocation() {
        MemoryService service = new MemoryService();

        service.observe("automatic-memory-user", "我叫李雷");
        service.observe("automatic-memory-user", "我住在杭州市西湖区");
        service.observe("automatic-memory-user", "我现在在西湖边");

        assertEquals("李雷", service.value("automatic-memory-user", "user_name"));
        assertEquals("杭州市西湖区", service.value("automatic-memory-user", "home_location"));
        assertTrue(service.prompt("automatic-memory-user").contains("李雷"));
    }
}
