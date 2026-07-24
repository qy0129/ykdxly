package com.example.ilink.feature.express;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpressServiceTest {

    @Test
    void extractsCommonTrackingNumbersWithoutTreatingPhoneAsTracking() {
        assertEquals("SF1234567890", ExpressService.extractTrackingNo("帮我查 SF1234567890 到哪了"));
        assertEquals("773123456789", ExpressService.extractTrackingNo("物流单号：773123456789"));
        assertEquals("", ExpressService.extractTrackingNo("手机号 13800138000"));
    }

    @Test
    void guessesCourierFromPrefix() {
        assertEquals("shunfeng", ExpressService.guessCouriers("SF1234567890").getFirst().code());
        assertEquals("jingdong", ExpressService.guessCouriers("JDVA123456789").getFirst().code());
    }

    @Test
    void parsesAndFormatsTrackingResponseInApiOrder() {
        String response = """
                {"status":"200","state":"5","nu":"SF1234567890","com":"shunfeng","data":[
                  {"ftime":"2026-07-23 15:00:00","context":"正在派送"},
                  {"ftime":"2026-07-23 08:00:00","context":"到达网点"}
                ]}
                """;
        ExpressService.ExpressResult result = ExpressService.parseResponse(
                response, "fallback", "shunfeng");

        assertTrue(result.success());
        assertEquals("正在派送", result.items().getFirst().context());
        assertTrue(ExpressService.format(result).contains("状态：派送中"));
        assertFalse(ExpressService.format(result).contains("fallback"));
    }

    @Test
    void treatsNoResultPlaceholderAsFailure() {
        ExpressService.ExpressResult result = ExpressService.parseResponse(
                "{\"status\":\"200\",\"message\":\"ok\",\"data\":[{\"context\":\"查无结果\"}]}",
                "SF1234567890", "shunfeng");
        assertFalse(result.success());
        assertTrue(result.message().contains("没有查到"));
    }
}
