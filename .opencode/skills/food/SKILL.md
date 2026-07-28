---
name: food
description: "Use when working on food recommendations, restaurants, recipes, meal planning. Triggers: 美食, 餐厅, 吃什么, 外卖, 点餐, 做饭, 菜谱, 饮食, food, restaurant, recipe, meal."
---

# Food Skill

## 本模块职责

处理餐饮推荐、餐厅查询、菜谱推荐，属于 `capabilities/food` 目录。

## 负责人

C 成员

## 可用能力

- 餐厅推荐（按口味/位置/预算）
- 菜谱查询
- 外卖服务调用
- 饮食偏好记录
- 营养成分查询

## 代码位置

```
src/main/java/com/example/ilink/capabilities/food/
src/test/java/com/example/ilink/capabilities/food/
```

## 依赖关系

```
adapter → application → food → platform
                          ↓
                    外卖 API
                    餐厅数据库
```

## 开发规范

1. 新增功能优先新增独立类
2. 推荐结果要有个性化权重
3. 外部 API 调用失败要有降级
4. 新增业务逻辑必须增加对应测试
5. 测试使用 mock 模拟外部服务

## 测试要求

- 测试名称应体现行为，如 `recommendRestaurantByLocation()`
- 覆盖正常流程、失败流程和关键边界条件
- 合并前必须通过 `.\mvnw.cmd test`

## 共享热点文件

修改以下文件前必须先沟通：
- `UserRequestHandler.java`
- `IntentRecognizer.java`
