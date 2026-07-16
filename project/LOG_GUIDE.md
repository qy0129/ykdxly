# Spring Boot 日志使用指南

## 一、日志级别说明

Spring Boot 使用 SLF4J + Logback 作为默认日志框架。

### 日志级别（从低到高）
1. **TRACE**: 最详细的信息，通常只在开发时使用
2. **DEBUG**: 调试信息，开发环境使用
3. **INFO**: 一般信息，生产环境默认级别
4. **WARN**: 警告信息，潜在问题
5. **ERROR**: 错误信息，严重问题

## 二、使用方式

### 方法一：使用 Lombok @Slf4j（推荐）

```java
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
public class HelloController {

    @GetMapping("/test")
    public String test() {
        log.info("处理请求");
        log.debug("调试信息: param={}", "value");
        return "OK";
    }
}
```

### 方法二：使用 LoggerFactory

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HelloController {
    private static final Logger log = LoggerFactory.getLogger(HelloController.class);

    public void test() {
        log.info("处理请求");
    }
}
```

## 三、日志打印示例

### 1. 基本使用
```java
log.trace("最详细的信息");
log.debug("调试信息");
log.info("一般信息");
log.warn("警告信息");
log.error("错误信息");
```

### 2. 带参数的日志
```java
String username = "张三";
log.info("用户登录: {}", username);
log.info("用户信息: name={}, age={}", "张三", 25);
```

### 3. 异常日志
```java
try {
    // 可能出错的代码
    int result = 10 / 0;
} catch (Exception e) {
    // 打印异常堆栈
    log.error("发生异常: ", e);
}
```

### 4. 条件日志（性能优化）
```java
// 不推荐：即使日志级别不够，也会执行字符串拼接
log.debug("处理数据: " + expensiveOperation());

// 推荐：使用参数化日志
log.debug("处理数据: {}", expensiveOperation());

// 更好：先判断日志级别
if (log.isDebugEnabled()) {
    log.debug("处理数据: {}", expensiveOperation());
}
```

## 四、日志配置

### application.properties 配置

```properties
# 全局日志级别
logging.level.root=INFO

# 指定包的日志级别
logging.level.com.example.demo=DEBUG

# 控制台日志格式
logging.pattern.console=%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n

# 输出到文件
logging.file.name=logs/application.log
logging.file.path=logs

# 文件最大大小
logging.logback.rollingpolicy.max-file-size=10MB

# 保留的历史文件数量
logging.logback.rollingpolicy.max-history=7
```

### application.yml 配置

```yaml
logging:
  level:
    root: INFO
    com.example.demo: DEBUG
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n"
  file:
    name: logs/application.log
```

## 五、日志格式说明

### 格式符号
- `%d{}` - 日期时间
- `%thread` - 线程名
- `%-5level` - 日志级别（左对齐，占5位）
- `%logger{36}` - 类名（最长36字符）
- `%msg` - 日志消息
- `%n` - 换行

### 示例输出
```
2024-07-15 10:30:45.123 [http-nio-8080-exec-1] INFO  c.e.demo.controller.HelloController - 用户登录: 张三
```

## 六、最佳实践

### 1. 使用合适的日志级别
- **TRACE**: 方法进入/退出，详细流程
- **DEBUG**: 详细的调试信息，变量值
- **INFO**: 重要的业务流程，如用户登录、订单创建
- **WARN**: 潜在问题，如配置缺失、性能下降
- **ERROR**: 错误异常，需要关注的问题

### 2. 日志内容要清晰
```java
// ❌ 不好的做法
log.info("处理");
log.info("" + data);

// ✅ 好的做法
log.info("开始处理用户登录请求");
log.info("用户登录成功: userId={}, username={}", userId, username);
```

### 3. 敏感信息不要记录
```java
// ❌ 不要记录密码、身份证等敏感信息
log.info("用户登录: password={}", password);

// ✅ 脱敏处理
log.info("用户登录: username={}", username);
```

### 4. 使用 MDC 记录上下文信息
```java
import org.slf4j.MDC;

// 记录请求ID
MDC.put("requestId", UUID.randomUUID().toString());
MDC.put("userId", "12345");

log.info("处理请求"); // 会自动带上 requestId

// 清理
MDC.clear();
```

## 七、测试运行

运行项目后，访问以下地址测试日志：

- http://localhost:8080/api/log/demo - 演示不同级别的日志
- http://localhost:8080/api/log/hello - 简单日志示例

查看控制台输出可以看到日志信息。

---

**提示**: 在 IDEA 中可以使用 Ctrl+F8 快捷键搜索日志内容。