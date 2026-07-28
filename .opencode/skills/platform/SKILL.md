---
name: platform
description: "Use when working on database, HTTP client, SDK integration, bootstrap, testing infrastructure. Triggers: 数据库, 配置, 启动, 部署, 测试, 构建, 平台, database, bootstrap, platform, http, sdk, test, build, deploy."
---

# Platform Skill

## 本模块职责

处理基础设施、数据库、网络、SDK、启动流程，属于 `platform`、`bootstrap`、`dashboard` 目录。

## 负责人

E 成员

## 可用能力

- MySQL 数据库操作
- HTTP 客户端封装
- 第三方 SDK 集成
- 应用启动引导
- 仪表盘界面

## 代码位置

```
src/main/java/com/example/ilink/platform/
src/main/java/com/example/ilink/bootstrap/
src/main/java/com/example/ilink/capabilities/dashboard/
src/test/java/com/example/ilink/platform/
```

## 依赖关系

```
adapter → application → capabilities → platform
                                       ↓
                                  bootstrap
                                       ↓
                               MySqlStore (数据库)
                               HttpClient (网络)
                               Config (配置)
```

## 数据库配置

```properties
# config.properties
database.enabled=true
database.username=root
database.password=123456
database.bot.id=LLL
```

## 共享热点文件

修改以下文件前必须先沟通：
- `MySqlStore.java`
- `ApplicationBootstrap.java`
- `pom.xml`

## 开发规范

1. 数据库操作要有连接池管理
2. HTTP 请求要有超时和重试
3. 配置变更要向后兼容
4. 新增业务逻辑必须增加对应测试
5. 测试不得依赖真实数据库或网络

## 测试要求

- 测试名称应体现行为，如 `connectToDatabaseSucceeds()`
- 覆盖正常流程、失败流程和关键边界条件
- 合并前必须通过 `.\mvnw.cmd test`

## 构建与部署

```powershell
# 构建
.\mvnw.cmd clean package

# 运行测试
.\mvnw.cmd test

# 打包后运行
java -jar target/ilink-bot-1.0.0.jar
```
