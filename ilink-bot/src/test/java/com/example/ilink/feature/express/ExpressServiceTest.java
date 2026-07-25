package com.example.ilink.feature.express;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpressServiceTest {

    @Test
    void separatesOrderNumbersFromLabeledTrackingNumbers() {
        assertEquals("SF123456789012", ExpressService.extractLabeledTrackingNo(
                "快递单号：SF123456789012\n订单号：202607240001"));
        assertEquals("202607240001", ExpressService.extractOrderNo(
                "**订单号**：202607240001"));
        assertEquals("", ExpressService.extractLabeledTrackingNo("订单号：202607240001"));
        assertEquals("12345678", ExpressService.extractOrderNo("12345678"));
        assertEquals("", ExpressService.extractLabeledTrackingNo("快递单号：NOTFOUND"));
        assertEquals("", ExpressService.extractOrderNo("订单号：UNKNOWN"));
    }

    @Test
    void extractsCommonTrackingNumbersWithoutTreatingPhoneAsTracking() {
        assertEquals("611699506676973", ExpressService.extractTrackingNo("\u67e5\u8be2\u8ba2\u5355 611699506676973"));
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

    @Test
    void parsesPhoneOrdersAndRemovesDuplicateTrackingNumbers() {
        String response = """
                {"data":[
                  {"num":"SF1234567890","com":"shunfeng","state":"0","comName":"顺丰速运"},
                  {"trackingNo":"sf1234567890","courierCode":"shunfeng"},
                  {"expressNo":"JT1234567890","comCode":"jtexpress","companyName":"极兔速递"}
                ]}
                """;

        var orders = ExpressService.parsePhoneOrders(response);

        assertEquals(2, orders.size());
        assertEquals("SF1234567890", orders.getFirst().trackingNo());
        assertEquals("jtexpress", orders.get(1).courierCode());
    }

    @Test
    void parsesRealAreaAndEstimatedDeliveryFromResponse() {
        String response = """
                {"status":"200","state":"0","predictTime":"2026-07-25 18:00:00","data":[
                  {"ftime":"2026-07-24 12:00:00","context":"已到达转运中心", "areaName":"南京市", "areaCode":"320100"}
                ]}
                """;

        ExpressService.ExpressResult result = ExpressService.parseResponse(
                response, "SF1234567890", "shunfeng");

        assertEquals("南京市", result.items().getFirst().areaName());
        assertEquals("320100", result.items().getFirst().areaCode());
        assertEquals("2026-07-25 18:00:00", result.estimatedDeliveryAt());
    }
}
