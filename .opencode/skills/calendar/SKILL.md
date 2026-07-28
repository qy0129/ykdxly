---
name: calendar
description: "Use when working on calendar, reminders, events, schedules, todos. Triggers: 日历, 提醒, 待办, 事件, 取消, 安排, 闹钟, 日程, schedule, reminder, todo, calendar, event."
---

# Calendar Skill

## 本模块职责

处理日历事件、提醒、待办事项，属于 `capabilities/calendar` 目录。

## 负责人

B 成员

## 可用能力

- 创建/取消/修改日历事件
- 设置/取消提醒
- 查询今日/本周安排
- 重复事件处理
- 事件冲突检测

## 代码位置

```
src/main/java/com/example/ilink/capabilities/calendar/
src/test/java/com/example/ilink/capabilities/calendar/
```

## 依赖关系

```
adapter → application → calendar → platform
                          ↓
                    MySqlStore (数据库)
```

## 开发规范

1. 新增功能优先新增独立类
2. 时间统一使用 `java.time`，禁止 `Date` 或 `Calendar`
3. 不可变数据优先使用 `record`
4. 新增业务逻辑必须增加对应测试
5. 测试不得依赖真实网络或数据库

## 测试要求

- 测试名称应体现行为，如 `cancelEventStopsReminder()`
- 覆盖正常流程、失败流程和关键边界条件
- 合并前必须通过 `.\mvnw.cmd test`

## 共享热点文件

修改以下文件前必须先沟通：
- `UserRequestHandler.java`
- `IntentRecognizer.java`
- `MySqlStore.java`
