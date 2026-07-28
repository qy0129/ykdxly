---
name: planning
description: "Use when working on task planning, schedules, to-do lists, project management. Triggers: 计划, 安排, 任务, 规划, 待办清单, 项目, 日程, planning, task, schedule."
---

# Planning Skill

## 本模块职责

处理任务计划、待办清单、日程安排，属于 `capabilities/planning` 目录。

## 负责人

B 成员

## 可用能力

- 创建/修改/删除任务
- 任务优先级设置
- 任务进度跟踪
- 定期任务生成
- 任务提醒关联

## 代码位置

```
src/main/java/com/example/ilink/capabilities/planning/
src/test/java/com/example/ilink/capabilities/planning/
```

## 依赖关系

```
adapter → application → planning → platform
                          ↓
                    MySqlStore (数据库)
                    calendar (提醒功能)
```

## 开发规范

1. 新增功能优先新增独立类
2. 时间统一使用 `java.time`，禁止 `Date` 或 `Calendar`
3. 不可变数据优先使用 `record`
4. 新增业务逻辑必须增加对应测试
5. 测试不得依赖真实网络或数据库

## 测试要求

- 测试名称应体现行为，如 `createTaskSetsReminder()`
- 覆盖正常流程、失败流程和关键边界条件
- 合并前必须通过 `.\mvnw.cmd test`

## 共享热点文件

修改以下文件前必须先沟通：
- `UserRequestHandler.java`
- `IntentRecognizer.java`
- `MySqlStore.java`
