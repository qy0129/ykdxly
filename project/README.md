# Spring Boot Demo Project

这是一个使用 Spring Boot 3.3.1 和 JDK 21 构建的示例项目。

## 项目结构

```
class_project/
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/example/demo/
│   │   │       ├── DemoApplication.java          # 主应用类
│   │   │       └── controller/
│   │   │           └── HelloController.java      # 示例控制器
│   │   └── resources/
│   │       └── application.properties            # 应用配置
│   └── test/
│       └── java/
│           └── com/example/demo/
│               └── DemoApplicationTests.java     # 测试类
├── pom.xml                                       # Maven配置文件
└── README.md                                     # 项目说明
```

## 技术栈

- **Spring Boot**: 3.3.1
- **Java**: 21
- **构建工具**: Maven 3.8.4+

## 功能特性

- Spring Web (RESTful API)
- Spring Boot DevTools (热重载)
- 示例 REST 控制器

## 快速开始

### 1. 运行项目

在项目根目录下执行：

```bash
cd class_project
mvn spring-boot:run
```

或者先编译后运行：

```bash
mvn clean package
java -jar target/demo-0.0.1-SNAPSHOT.jar
```

### 2. 测试 API

项目启动后，访问以下地址：

- http://localhost:8080/api/ - 欢迎页面
- http://localhost:8080/api/hello - Hello接口

### 3. 运行测试

```bash
mvn test
```

## 开发建议

- 使用 Spring Boot DevTools，修改代码后会自动重启应用
- 建议使用 IDE (IntelliJ IDEA / Eclipse) 导入项目进行开发
- 确保使用 JDK 21 编译项目

## 环境配置

如果 Maven 默认使用的 Java 版本不是 21，可以设置 JAVA_HOME：

### Windows:
```cmd
set JAVA_HOME=C:\Program Files\Microsoft\jdk-21.0.11.10-hotspot
mvn spring-boot:run
```

或者在 Maven 配置文件 `settings.xml` 中指定 JDK 21 路径。

## 扩展功能

如需添加更多功能模块，可在 `pom.xml` 中添加相应依赖：

- **Spring Data JPA**: 数据库访问
- **Spring Security**: 安全认证
- **Spring Validation**: 参数验证
- **Spring Actuator**: 应用监控

---

**创建时间**: 2026-07-15
**作者**: Claude Code Assistant