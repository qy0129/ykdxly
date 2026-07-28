---
name: weather
description: "Use when working on weather queries, forecasts, temperature, clothing suggestions. Triggers: 天气, 气温, 下雨, 穿什么, 温度, 气候, 预报, weather, temperature, forecast, rain."
---

# Weather Skill

## 本模块职责

处理天气查询、天气预报、穿衣建议，属于 `capabilities/weather` 目录。

## 负责人

C 成员

## 可用能力

- 实时天气查询
- 多日天气预报
- 穿衣建议生成
- 出行建议（是否适合户外）
- 空气质量查询

## 代码位置

```
src/main/java/com/example/ilink/capabilities/weather/
src/test/java/com/example/ilink/capabilities/weather/
```

## 依赖关系

```
adapter → application → weather → platform
                          ↓
                    高德天气 API
                    第三方天气服务
```

## 开发规范

1. 新增功能优先新增独立类
2. 天气数据要有缓存机制
3. API 调用失败要有降级处理
4. 新增业务逻辑必须增加对应测试
5. 测试使用 mock 模拟外部 API

## 测试要求

- 测试名称应体现行为，如 `getWeatherReturnsTemperature()`
- 覆盖正常流程、失败流程和关键边界条件
- 合并前必须通过 `.\mvnw.cmd test`

## 共享热点文件

修改以下文件前必须先沟通：
- `UserRequestHandler.java`
- `IntentRecognizer.java`
