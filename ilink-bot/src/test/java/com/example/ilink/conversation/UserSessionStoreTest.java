package com.example.ilink.conversation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UserSessionStoreTest {

    @Test
    void extractsWeatherCityFromPreciseChineseLocation() {
        assertEquals("杭州", UserSessionStore.extractCity("杭州市阿里高桥园区"));
        assertEquals("杭州", UserSessionStore.extractCity("浙江省杭州市余杭区万和路"));
        assertEquals("北京", UserSessionStore.extractCity("北京市朝阳区"));
        assertEquals("", UserSessionStore.extractCity("阿里高桥园区"));
    }
}
