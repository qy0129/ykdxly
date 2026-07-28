---
name: travel
description: "Use when working on travel planning, navigation, routes, transportation. Triggers: 出行, 导航, 路线, 打车, 公交, 地铁, 交通, 旅行, 酒店, 景点, travel, navigation, route, transport."
---

# Travel Skill

## 本模块职责

处理出行规划、导航路线、交通查询，属于 `capabilities/travel` 目录。

## 负责人

C 成员

## 可用能力

- 路线规划（步行/驾车/公交）
- 实时交通查询
- 打车服务调用
- 酒店/景点推荐
- 出行预算估算

## 代码位置

```
src/main/java/com/example/ilink/capabilities/travel/
src/test/java/com/example/ilink/capabilities/travel/
```

## 依赖关系

```
adapter → application → travel → platform
                          ↓
                    高德地图 API
                    百度地图 API
                    DIDI API
```

## API 配置

```properties
# config.properties
amap.api.key=xxx
baidu.map.ak=xxx
DIDI_MCP_KEY=xxx
```

## 开发规范

1. 新增功能优先新增独立类
2. API 调用失败要有降级处理
3. 网络请求设置合理超时
4. 新增业务逻辑必须增加对应测试
5. 测试使用 mock 模拟外部 API

## 测试要求

- 测试名称应体现行为，如 `planRouteReturnsMultipleOptions()`
- 覆盖正常流程、失败流程和关键边界条件
- 合并前必须通过 `.\mvnw.cmd test`

## 共享热点文件

修改以下文件前必须先沟通：
- `UserRequestHandler.java`
- `IntentRecognizer.java`
