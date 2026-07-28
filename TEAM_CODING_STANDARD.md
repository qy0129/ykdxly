# 五人小组编码规范

版本：1.0

本规范适用于 `ykdxly` 项目所有成员和所有项目文件变更，不限于 Java 源码。开始编码前必须阅读本文，并按照文末模板回复确认。

## 一、开发前置条件

Git 不能保证完全没有冲突。本规范通过固定分工、缩小提交范围和保持分支同步来降低冲突。

开始开发前必须确认：

1. 当前分支来自最新且干净的 `main`。
2. 工作区没有其他人的未提交修改。
3. 已明确本次任务涉及的目录和负责人。
4. 不修改与当前任务无关的文件。

项目当前正在使用新的包结构。新代码只能放入以下目录，不再使用旧的 `app`、`feature`、`model`、`storage`、`tools` 包：

```text
adapter       外部输入输出，例如微信和 HTTP
application   请求编排、路由、会话和工具调用
capabilities  具体业务能力
platform      数据库、网络、SDK 和文件等基础设施
bootstrap     程序启动和依赖组装
```

## 二、五人目录分工

每个人默认只修改自己的负责目录。跨目录修改前必须在群里说明原因。

| 成员 | 默认负责目录 | 主要职责 |
| --- | --- | --- |
| A | `adapter`、`application/messaging`、`application/routing` | 微信入口、请求分发、意图识别 |
| B | `capabilities/calendar`、`capabilities/planning` | 日历、提醒、待办、任务计划 |
| C | `capabilities/travel`、`capabilities/food`、`capabilities/weather` | 出行、餐饮、天气 |
| D | `capabilities/documents`、`audio`、`image`、`media`、`web` | 文档、音频、图片、媒体和搜索 |
| E | `platform`、`bootstrap`、`dashboard`、测试和构建配置 | 基础设施、启动流程、仪表盘和测试支持 |

新增非源码文件时，按文件类型归属负责人：

- 配置和环境文件：E 负责，功能负责人配合确认字段。
- 测试数据和测试资源：对应功能负责人负责。
- 页面模板、静态资源和消息模板：对应功能负责人负责，A 负责入口接入。
- 数据库脚本和持久化结构：E 负责，功能负责人提供业务字段说明。
- 构建、部署和运行脚本：E 负责。
- 项目级说明文件：由提出任务的人负责，避免重复创建说明文件。

以下文件为共享热点文件，默认由指定负责人维护，其他成员修改前必须先沟通：

- `UserRequestHandler.java`
- `IntentRecognizer.java`
- `MySqlStore.java`
- `ApplicationBootstrap.java`
- `pom.xml`

新增功能应优先新增独立类，再进行最小范围的注册。不要把大量业务逻辑继续堆入共享热点文件。

## 三、所有新增文件规范

新增东西不一定要修改源代码，但必须先确认文件类型、存放目录、负责人和使用方式。禁止把新文件随意放在项目根目录。

| 文件类型 | 推荐位置 | 规则 |
| --- | --- | --- |
| Java 源码 | `src/main/java` | 放入对应的新包，不恢复旧包结构 |
| Java 测试 | `src/test/java` | 与源码包路径对应 |
| 配置示例 | `config.properties.example` 或配置目录 | 只提交字段和示例值，不提交真实密钥 |
| 文档模板 | `src/main/resources/document_templates` | 使用清晰、稳定的文件名 |
| 页面模板 | `src/main/resources/templates` | 按功能建立子目录 |
| CSS、JavaScript、图片 | `src/main/resources/static` | 按资源类型或功能建立子目录 |
| 人设、提示词、文本模板 | `src/main/resources` 对应子目录 | 文件名必须能说明用途 |
| 数据库变更脚本 | `database/migrations` | 一个脚本只完成一个变更，并写明顺序 |
| 构建和部署脚本 | `scripts` 或 `deploy` | 必须说明执行环境和入口 |

新增文件必须满足：

1. 文件名能表达用途，禁止使用 `new`、`test2`、`临时`、`最终版` 等名称。
2. 文件放入仓库后，必须有实际引用、测试用途或明确的部署用途。
3. 二进制资源应控制大小，避免提交可由构建过程生成的文件。
4. 新增配置字段必须同步更新示例配置，并说明默认值和是否必填。
5. 新增数据库字段或表必须提供可重复执行或明确顺序的变更脚本。
6. 修改 `pom.xml`、`.gitignore`、部署文件或根目录配置前，必须在合并请求中单独说明影响。
7. 不因为新增文件而顺手重命名、移动或格式化无关文件。

新增非源码文件也必须经过校验。例如：

- 配置：使用示例配置启动或执行配置加载测试。
- 模板和静态资源：确认路径、文件名和运行时引用一致。
- 图片、音频和文档资源：确认文件可以正常打开，且未提交临时产物。
- 数据库脚本：在测试数据库执行，并确认升级顺序。
- 脚本：在声明的运行环境执行一次，并写明命令。

## 四、分支规范

禁止直接在 `main` 分支开发，禁止直接向 `main` 推送。

分支名称统一使用：

```text
feature/<功能名>
fix/<问题名>
refactor/<重构名>
test/<测试名>
```

示例：

```text
feature/calendar-cancel
fix/reminder-duplicate
refactor/package-migration
```

创建分支前执行：

```powershell
git fetch origin
git switch main
git pull --ff-only origin main
git switch -c feature/<功能名>
```

开发过程中保持分支同步：

```powershell
git fetch origin
git rebase origin/main
```

个人分支可以 `rebase`，共享分支禁止强制推送。遇到冲突时不要直接全部选择“当前版本”或“传入版本”，必须逐段确认业务逻辑。

## 五、提交规范

一次提交只完成一个目的，提交信息统一使用：

```text
feat(calendar): 支持取消日历事件
fix(reminder): 修复重复提醒
refactor(package): 完成包结构迁移
test(calendar): 增加取消场景测试
chore(build): 更新构建配置
```

提交前必须检查：

```powershell
git status --short
git diff --check
.\mvnw.cmd test
git diff --stat
```

提交中不得包含：

- 与任务无关的格式化或重命名。
- `target/`、`data/`、IDE 配置和生成文件。
- 真实 API Key、数据库密码、邮箱授权码或其他密钥。
- 个人调试代码和临时日志。

`config.properties` 只允许保留本地配置，提交时使用示例配置，不提交真实值。

## 六、Java 编码规范

- 使用 Java 21 和 UTF-8。
- 使用 4 个空格缩进，禁止 Tab。
- 类名使用 `UpperCamelCase`。
- 方法和变量使用 `lowerCamelCase`。
- 常量使用 `UPPER_SNAKE_CASE`。
- 禁止通配符导入。
- 服务类使用构造器注入依赖，并尽量声明为 `final`。
- 不可变数据优先使用 `record`。
- 时间统一使用 `java.time`，禁止新增 `Date` 或 `Calendar`。
- 新代码不要散落使用状态字符串，统一使用常量或枚举。
- 一个类只负责一个明确职责。
- 方法过长或嵌套过深时必须拆分，不要通过继续增加条件分支解决复杂度。
- 日志中不得输出密钥、密码、完整用户隐私数据。
- 保持现有文件编码，不要顺手转换无关文件的编码。

## 七、分层规范

依赖方向统一为：

```text
adapter -> application -> capabilities -> platform
```

- `adapter` 只负责协议适配，不写业务规则。
- `application` 只负责流程编排，不实现具体领域算法。
- `capabilities` 负责业务规则和业务服务。
- `platform` 负责数据库、网络、文件和第三方 SDK。
- `bootstrap` 负责创建对象和组装依赖。

禁止新增反向依赖和循环依赖。业务代码不要直接拼接数据库 SQL、处理微信底层协议或操作 HTTP 细节。

## 八、测试规范

测试目录必须与源码目录对应：

```text
src/main/java/com/example/ilink/capabilities/calendar
src/test/java/com/example/ilink/capabilities/calendar
```

要求：

- 新增业务逻辑必须增加对应测试。
- 每个修复至少增加一个能复现原问题的测试。
- 单元测试不得依赖真实网络、数据库或第三方 API。
- 测试名称应体现行为，例如 `cancelEventStopsReminder()`。
- 测试应覆盖正常流程、失败流程和关键边界条件。
- 合并前必须通过 `.\mvnw.cmd test`。

## 九、合并请求规范

提交合并请求前必须确认：

```text
[ ] 分支已从最新 main 创建
[ ] 没有修改其他成员负责的目录
[ ] 没有提交密钥、配置、target 或 data
[ ] 已增加或更新相关测试
[ ] .\mvnw.cmd test 已通过
[ ] git diff --check 已通过
[ ] 提交只包含当前任务
[ ] 已说明可能影响的模块
```

合并请求描述至少包含：

```text
功能：
修改文件：
测试结果：
是否修改共享热点文件：是/否
是否涉及数据库或配置：是/否
```

## 十、开工前确认模板

每个人开始编码前，将以下内容发到群里：

```text
我已阅读并同意《五人小组编码规范》1.0。

姓名：
任务：
分支：
负责目录：
预计修改文件：
新增文件类型及路径：
是否涉及共享热点文件：是/否
是否涉及数据库或配置：是/否
测试计划：
```

未完成上述确认，不开始修改代码。
