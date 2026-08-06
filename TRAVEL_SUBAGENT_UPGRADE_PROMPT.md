# 旅游 Subagent 全功能升级实现提示词 V2

按本文顺序分阶段实施。每个阶段完成后必须执行该阶段的编译和测试门禁，门禁失败时停止，不得带着失败进入下一阶段。

## 总体目标

将当前旅游 Subagent 从“行程草案生成器”升级为一站式智能旅行规划 Agent：

```text
一句话需求
-> 提取日期、预算、兴趣、节奏和同行人约束
-> 在用户授权后获取设备位置和时区
-> 获取目的地地理信息、天气、候选景点、开放信息和本地路线
-> 生成逐日主方案、备用方案、分类预算和待核实项
-> 生成 App 写入草案
-> 用户确认草案后写入计划、任务、日历和提醒
-> 出发前按计划刷新天气与景区状态，有变化时生成变更草案
```

安全红线：

- 不代订票、不代付款、不代预订酒店或门票。
- 所有外部交易只生成待确认任务或官方跳转链接。
- 设备定位必须经过浏览器授权。拒绝授权时正常降级，不得绕过授权。
- API Key 只能由后端配置或环境变量读取，不得发送到浏览器、写入日志或提交到 Git。
- 继续保留“方案预览 -> 用户确认方案 -> 生成写入草案 -> 用户确认草案后落库”的安全流程。

## 外部 API 选型

第一版只接入以下服务，不同时混用多套地图坐标体系：

1. 浏览器 Geolocation API
   - 用途：获取用户授权后的当前位置和精度。
   - API Key：不需要。
   - 同时由浏览器提供 `Intl.DateTimeFormat().resolvedOptions().timeZone`。

2. 高德地图 Web 服务 API
   - 用途：地理编码、逆地理编码、POI、行政区和本地步行/驾车/公交路线。
   - API Key：`AMAP_API_KEY` / `amap.api.key`。
   - 坐标系：高德数据统一按 `GCJ02` 使用和保存，不得伪装成 WGS84。
   - 高铁和航班真实班次不属于高德本地路径规划能力；没有专门供应商时只能生成查询/预订任务，不能编造班次和价格。

3. 和风天气 API
   - 用途：逐日天气、降水概率和天气预警。
   - API Key：`QWEATHER_API_KEY` / `weather.api.key`。
   - API Host 必须可配置，以供应商控制台给出的 Host 为准。
   - 天气预报范围以账户实际套餐为准；超出范围的日期字段留空并标记低可信度，不得用模型伪造。

4. 攻略与公开资料搜索
   - 默认继续使用现有 Bing RSS 搜索，无 API Key，但只作为低保证的公开搜索结果。
   - 可选接入 Tavily：`TAVILY_API_KEY` / `travel.search.api-key`。
   - 不无差别爬取需要登录、明确禁止抓取或受版权限制的网站。
   - 搜索摘要不能直接当作票价、开放时间等事实；关键事实必须带来源、抓取时间和可信度。

## 通用工程规则

- 开始前执行 `git status --short`，保留用户已有改动，不覆盖与本任务无关的文件。
- 所有外部服务先定义接口，再提供生产适配器和测试 Fake。
- 单元测试和常规集成测试不得访问真实外部 API。
- 真实 API 只用于手工冒烟测试，缺少 Key 时必须跳过并明确报告。
- 所有 HTTP 调用设置连接超时、请求超时、有限重试、限流和缓存。
- 日志只能记录供应商、状态码、耗时和 traceId，严禁记录 Key、精确设备位置或完整响应正文。
- 外部数据必须记录 `provider`、`sourceUrl`、`fetchedAt`、`confidence`；未知值用空值表示，不得由模型补造。
- 模型只负责理解、选择、解释和语言组织。天气、坐标、开放时间、路线、价格来源等事实由工具提供并由后端合并。

---

## 阶段 0：建立可编译基线

1. 在任何旅游功能修改前运行后端编译和现有测试。
2. 若现有代码已经无法编译，先记录基线错误，只做最小修复，不进行无关重构。
3. 确认 `config.properties` 未被提交，检查 `.gitignore`。
4. 检查项目实际可用的 Maven 命令；没有全局 `mvn` 时使用 IDE 内置 Maven 或项目 Maven Wrapper。

门禁：

```text
mvn -q -DskipTests compile
mvn -q test
```

验收：修改旅游功能前，后端能够编译；若测试存在与本任务无关的历史失败，必须列出并获得明确处理结论。

---

## 阶段 1：输入、输出和领域数据契约

### 1.1 扩展 input.schema.json

在 `arguments` 中增加：

```json
{
  "deviceLocation": {
    "type": "object",
    "properties": {
      "lat": { "type": "number" },
      "lng": { "type": "number" },
      "accuracyMeters": { "type": "number", "minimum": 0 },
      "timezone": { "type": "string" },
      "capturedAt": { "type": "string" },
      "permission": { "type": "string", "enum": ["granted", "denied", "unavailable"] }
    }
  },
  "preferredTransport": {
    "type": "string",
    "enum": ["highSpeedRail", "flight", "selfDrive", "publicTransit", "any"]
  },
  "hotelStarRating": { "type": "integer", "minimum": 1, "maximum": 5 },
  "avoidEarlyMorning": { "type": "boolean" },
  "elderlyTravel": { "type": "boolean" },
  "beachPreference": { "type": "boolean" }
}
```

`deviceLocation` 不是必填。权限被拒绝或位置超时后，继续处理请求并追问出发城市。

### 1.2 扩展 TravelRequest

新增与输入 Schema 同名的字段。`hotelStarRating` 使用 `Integer`，不要使用字符串。同步更新：

- `TravelRequest.from`
- `TravelRequest.toJson`
- 测试 Fixture
- Travel Prompt 中的 request JSON 契约

设备经纬度仅作为当前请求上下文使用，不写入长期记忆。

### 1.3 扩展输出数据

保留现有 `budgetEstimate`，不要再创建含义重复的 `budgetBreakdown` 顶层字段。新增：

- `locationContext: object`
- `weather: array`
- `attractions: array`
- `transitMatrix: array`
- `alternativePlans: array`

这些顶层字段始终存在，外部服务失败时允许为空对象或空数组，以支持优雅降级。不要把每个外部字段都设为非空 required。

位置数据至少包含：

```json
{
  "originName": "",
  "originLat": null,
  "originLng": null,
  "originInferred": false,
  "destinationName": "",
  "destinationLat": null,
  "destinationLng": null,
  "destinationCity": "",
  "destinationAdminArea": "",
  "destinationCountry": "",
  "coordinateSystem": "GCJ02",
  "timezone": "Asia/Shanghai",
  "provider": "amap",
  "fetchedAt": ""
}
```

天气项至少包含：

```json
{
  "date": "yyyy-MM-dd",
  "condition": "",
  "tempHigh": null,
  "tempLow": null,
  "precipitationMm": null,
  "precipitationProbability": null,
  "humidityPercent": null,
  "windKmh": null,
  "warnings": [],
  "forecastConfidence": "high|medium|low",
  "provider": "qweather",
  "fetchedAt": ""
}
```

景点项必须有稳定的 `attractionId`，并包含坐标系、来源和抓取时间。未知票价、开放时间、预约要求必须为空，不得填 `0` 或 `false` 冒充已知事实。

每个活动增加：

```json
{
  "attractionId": "",
  "attractionName": "",
  "startTime": "HH:mm",
  "durationMinutes": 90,
  "lat": null,
  "lng": null,
  "coordinateSystem": "GCJ02",
  "indoor": false,
  "requiresReservation": null,
  "openingHours": "",
  "transitFromPrevious": {},
  "backupActivity": {}
}
```

### 1.4 同步 Java 契约

更新 `TravelResult.fromGenerated`、`toData` 和所有构造调用。模型输出中只解析模型负责的字段；工具产生的天气、景点和路线数据由 `TravelSubagent` 在后端合并，不能要求模型重新生成这些事实。

门禁：编译通过，并增加 Schema/序列化往返测试。

---

## 阶段 2：配置和通用外部 HTTP 基础设施

只更新 `backend/config.properties.example`，不得用占位值覆盖用户根目录中的真实配置。

```properties
# 高德地图：地理编码、POI、本地路径规划
amap.api.key=
amap.api.base-url=https://restapi.amap.com/v3

# 和风天气：Host 以供应商控制台分配值为准
weather.api.key=
weather.api.base-url=https://devapi.qweather.com/v7
weather.geo-base-url=https://geoapi.qweather.com/v2

# 攻略搜索：bing-rss 不需要 Key，tavily 为可选增强
travel.search.provider=bing-rss
travel.search.api-key=
travel.search.base-url=https://api.tavily.com

travel.location.ip-fallback-enabled=false
travel.geocoding.cache-hours=24
travel.weather.cache-hours=6
travel.routing.cache-hours=1
```

每项同时支持环境变量覆盖：

- `AMAP_API_KEY`
- `QWEATHER_API_KEY`
- `TRAVEL_SEARCH_PROVIDER`
- `TAVILY_API_KEY`

创建旅游 API 共用的 HTTP 客户端封装，负责：

- URI 编码
- 连接与请求超时
- 429/5xx 有限重试
- 响应状态检查
- JSON 解析
- 日志脱敏
- 每个供应商独立限流

门禁：使用本地 Fake HTTP 响应完成成功、超时、429、无效 JSON 和缺 Key 测试。

---

## 阶段 3：定位、天气、景区和路线工具

所有新工具实现 `ToolHandler`，均为 `READ_ONLY`、`ToolSideEffect.NONE`，并设置明确超时。

### 3.1 LocationContextTool

工具名：`travel.location.context`。

优先级：

1. 请求显式提供的 `origin`。
2. 用户授权且十分钟内采集的 `deviceLocation`。
3. 可选的城市级 IP 兜底。
4. 都不可用时返回目的地结果，并在 `questions` 中追问出发城市。

创建 `GeocodingService` 接口和高德实现。设备坐标来自浏览器 WGS84 时，在进入高德 API 前明确转换为 GCJ-02，并记录转换前后的坐标系。缓存地名地理编码结果 24 小时。

不要默认把服务端 IP 当成用户 IP。只有在可信反向代理配置下才读取转发 IP；默认关闭 IP 定位。

### 3.2 WeatherForecastTool

工具名：`travel.weather.forecast`。

输入目的地坐标、目的地 locationId 和日期范围。创建 `WeatherForecastService` 接口和和风天气实现。

- 同时请求逐日预报和官方预警接口。
- 3 天内 `high`，4-7 天 `medium`，8 天以后 `low`。
- 供应商无法覆盖的日期不生成虚假天气项，而是在 risks 中记录覆盖范围不足。
- 当前日期缓存 2 小时，未来预报缓存 6 小时。

### 3.3 AttractionResearchTool

工具名：`travel.attraction.research`。

输入目的地、兴趣、节奏、同行人限制和旅行日期，先通过高德 POI 获取候选景点，再用公开搜索补充官方开放信息。

返回候选景点时必须生成稳定 `attractionId`。来源质量不能只依赖搜索标题，应保存：

- `provider`
- `sourceUrl`
- `sourceDomain`
- `fetchedAt`
- `sourceQuality`
- `evidenceText`

`gov.cn`、当地文旅局和可确认的景区官方域名可标为 `official`；OTA 只能标为 `verified` 或更低。正则抽取票价和开放时间失败时留空。

### 3.4 MapRoutingTool

工具名：`travel.map.routing`。

只计算模型已选择景点之间的必要路线，避免对所有候选 POI 做完整 N×N 请求。支持 walking、driving、transit；cycling 只有供应商明确支持时才启用。

不得假设固定 10×10 限制，按照高德当前接口文档和账户配额实现分批。缓存键必须包含起点、终点、方式和坐标系。

### 3.5 TravelModule 注册

注册以上工具和生产服务实现，并同步 `SubagentDefinition.toolNames`。创建服务接口对应的 Fake，测试中禁止访问真实网络。

门禁：每个工具独立完成定义测试、权限测试、成功解析测试、缺 Key 降级测试、超时测试和缓存测试。

---

## 阶段 4：确定性 NLU 与相对日期解析

扩展 `TravelPolicy.normalizeRequest`，但不要继续把所有逻辑堆进一个方法。拆出：

- `RelativeDateParser`
- `ChineseDurationParser`
- `ChineseMoneyParser`
- `TravelPreferenceParser`

必须支持：

- 今天、明天、后天、下周一
- 3 天、十天、两周
- 1 万、10 万、两万五、10000 元
- 海边/沙滩偏好
- 带父母、老人、儿童
- 不早起、睡到自然醒、不要太累
- 高铁、飞机、自驾偏好
- 一至五星酒店偏好

相对日期以请求中的浏览器时区为准；没有时区时使用项目默认时区。引入 `Clock`，测试中固定时间，禁止直接依赖不可控的 `LocalDate.now()`。

交通偏好不是事实。比如从某些出发地到三亚不存在可行直达高铁时，Agent 必须提出替代建议，不能为了迎合偏好编造路线。

门禁：固定 Clock 的参数化解析测试全部通过。

---

## 阶段 5：旅游数据收集与模型调用流程

新增 `TravelDataCollector`，使用 `CompletableFuture` 或虚拟线程并行调用独立只读工具，同时受 `AgentContext.deadline` 约束。

执行顺序：

```text
1. normalizeRequest + validateInput
2. 缺少目的地或日期时直接补问，不调用外部 API
3. 解析出发地、目的地、坐标、时区
4. 并行获取天气、候选景点、目的地公开资料
5. 模型从候选 attractionId 中选择并生成初步逐日方案
6. 只对已选择活动计算路线
7. 确定性优化器修正天气、闭园、路线、体力和时间冲突
8. 预算引擎计算真实估算区间并判断是否超预算
9. 后端合并工具事实、模型方案、风险和来源
10. 进入现有预览与确认流程
```

同步修改：

- `TravelPlannerModel`
- `ModelTravelPlanner`
- `TravelPrompt.messages`
- `TravelSubagent`
- 所有测试 Lambda 和 Fixture

模型 Prompt 规则：

- 只能选择工具返回的 `attractionId`；无法匹配的自定义活动必须标记 `unverified`。
- 不得修改工具返回的天气、坐标、票价、开放时间和来源。
- 不得生成不存在的交通班次、酒店价格或门票库存。
- 候选景点不足时安排休息、自由活动或提出补问，不能为了凑满天数而虚构景点。

独立降级策略：

- 定位失败：追问出发城市。
- 天气失败：继续生成，并加入 `WEATHER_UNAVAILABLE` 风险。
- 景区详情失败：使用 POI 基础信息和现有公开搜索，事实标记低可信度。
- 路线失败：保留模型估算，但明确 `trafficAdjusted=false` 和 `verificationRequired=true`。
- 模型失败：使用规则降级方案。
- 优化器失败：保留通过基础校验的模型原始方案并记录风险。

门禁：使用 Fake 工具验证并行、超时、部分失败、全失败和成功合并场景。

---

## 阶段 6：ItineraryOptimizer 和 BudgetEngine

### 6.1 ItineraryOptimizer

使用类型明确的约束对象，不要使用与现有 `JsonArray constraints` 不一致的 `JsonObject constraints` 参数。

优化优先级：

1. 无效日期、重复日期和日期越界校验。
2. 景区闭园日期或闭园星期检查。
3. 天气冲突：依据 `precipitationProbability`，不是 `precipitationMm`。
4. 开放时间与活动开始/结束时间冲突。
5. 同日路线优化，减少折返。
6. 每日活动时长 + 交通时长 + 休息缓冲总量限制。
7. relaxed 不超过 4 小时活动，balanced 不超过 6 小时，intensive 不超过 8 小时；交通时间另行展示并计入体力判断。
8. `avoidEarlyMorning=true` 时第一项不早于 09:00。
9. 老人同行时增加休息、减少步行和连续活动。
10. 预约项目生成提前办理任务。
11. 坐标和其他工具事实按 `attractionId` 回填。

优化器不能凭空创建景点。无法满足全部约束时返回冲突列表，让 Agent 展示给用户。

### 6.2 BudgetEngine

固定比例只能用于用户资金分配建议，不能当作实际花费估算。预算引擎输出：

```json
{
  "amount": 0,
  "minimum": 0,
  "maximum": 0,
  "currency": "CNY",
  "estimated": true,
  "confidence": "low|medium|high",
  "breakdown": [
    { "category": "accommodation", "amount": 0, "minimum": 0, "maximum": 0, "source": "" }
  ],
  "overBudget": false,
  "overBudgetWarning": ""
}
```

根据实际估算合计与用户预算比较，而不是先按 100% 比例分配再判断超支。缺少真实交通或住宿价格时给出区间并降低可信度。

门禁：纯 Java 单元测试覆盖天气换日、闭园、体力、老人、不早起、路线不可用、预算充足和预算不足。

---

## 阶段 7：可执行日历与数据库迁移

当前 `schedule_items` 没有地点、坐标和时区字段。新增下一序号数据库迁移，不修改历史迁移：

```sql
ALTER TABLE schedule_items
  ADD COLUMN location_name VARCHAR(240) NULL,
  ADD COLUMN latitude DECIMAL(10,7) NULL,
  ADD COLUMN longitude DECIMAL(10,7) NULL,
  ADD COLUMN coordinate_system VARCHAR(20) NULL,
  ADD COLUMN timezone_id VARCHAR(80) NULL,
  ADD COLUMN source_url TEXT NULL,
  ADD COLUMN reservation_required BOOLEAN NULL;
```

同步更新数据库读写、`AiCommandService`、HTTP API、前端类型和日程详情展示。

时间规则：旅行活动使用目的地当地 `yyyy-MM-ddTHH:mm:ss`，同时保存 `timezone_id`。当前数据库使用 DATETIME，不能把带 `Z` 的 UTC 字符串直接传给 `Timestamp.valueOf(LocalDateTime)`。

改造 `TravelDraftTool`：

- 每个 activity 创建独立 Task 和 Schedule。
- 使用真实 `startTime` 和 `durationMinutes`。
- 写入地点名、经纬度、坐标系和时区。
- 预约要求和备用方案写入任务描述。
- 缺少开始时间时按活动时段推断，并标记为估算。
- 写入前检查同一天日程冲突。
- 草案转换保持确定性和幂等性，不再调用第二次模型解释结构化结果。

门禁：数据库迁移测试、草案快照测试和确认后读回测试通过。

---

## 阶段 8：前端设备定位与旅行方案展示

在 Agent 页面提交旅行请求时：

1. 检测到旅游意图后请求浏览器定位，超时 5 秒。
2. 显示定位中的加载状态；不得让页面无反馈等待。
3. 用户拒绝后继续发送请求，传 `permission=denied`。
4. 将坐标精度、采集时间和浏览器时区放入 `arguments.deviceLocation`。
5. 不在聊天消息正文中展示精确经纬度。

旅行方案展示至少包含：

- 每日时间线
- 活动时长与交通耗时
- 天气与雨天备选
- 预约提示
- 分类预算和估算可信度
- 数据来源及抓取时间
- 待核实风险
- 方案确认按钮

保持现有页面视觉体系，按钮和定位状态具备可访问名称，移动端触控区域不小于 44px。

门禁：前端 TypeScript 构建、Lint、定位允许/拒绝/超时测试和移动端布局检查通过。

---

## 阶段 9：出发前刷新和变更草案

新增迁移创建旅行资料快照和刷新配置，至少保存：

- `plan_id`
- `provider`
- `data_type`
- `payload_json`
- `content_hash`
- `fetched_at`
- `expires_at`
- `last_error`

实现定时刷新服务：

- 仅刷新已确认且尚未结束的旅行计划。
- 出发 7 天前每天刷新一次，出发 48 小时内每 6 小时刷新一次。
- 检测天气预警、闭园、开放时间或路线重大变化。
- 有变化时生成待确认变更草案和通知，不直接修改已确认计划。
- 外部 API 失败只记录状态并按退避策略重试。

门禁：使用固定 Clock 和 Fake Provider 验证刷新窗口、变化检测、去重、退避和变更草案。

---

## 阶段 10：完整测试与验收

### 单元测试

- 海边偏好解析。
- 老人同行自动放慢节奏。
- 不早起解析。
- 高铁/飞机/自驾偏好解析。
- 酒店星级解析。
- 中文金额解析：一万、10 万、两万五。
- 固定时区下的明天、十天、下周一。
- GCJ-02 坐标契约。
- 天气降级、景区降级和路线降级。
- 优化器和预算引擎。
- 每活动独立日程映射。

### 集成测试

所有外部 API 使用 Fake：

1. “明天去青岛玩十天，预算 10 万，喜欢海边，不要太累。”
   - 从固定 Clock 推导开始与结束日期。
   - 解析预算 100000、海边偏好和 relaxed 节奏。
   - 生成 10 天方案；候选不足时允许休息日，不虚构景点。
   - 每个已核实景点活动引用有效 attractionId。
   - 天气覆盖不足的日期带风险提示。

2. “带父母去三亚五天，四星级酒店，高铁优先，预算一万。”
   - 解析老人、四星、交通偏好和预算。
   - 如果出发地到三亚没有可靠高铁数据，明确提出替代交通建议，不编造班次。
   - relaxed 节奏满足体力限制。

3. “下周一去北京三天，不要早起。”
   - 固定 Clock 下正确解析下周一。
   - 每天第一项活动不早于 09:00。

### 手工冒烟测试

只有配置了真实 Key 时执行高德、和风天气和可选搜索 API 冒烟测试。输出只展示供应商、结果数量和耗时，不展示 Key 或完整位置。

最终门禁：

```text
mvn -q test
npm run lint
npm run build
git diff --check
```

最终交付必须说明：

- 修改文件清单。
- 新增数据库迁移。
- 需要配置的 API 及申请位置。
- 未配置某个 API 时的降级行为。
- 自动化测试结果。
- 真实 API 冒烟测试是否执行。
- 已知限制，例如航班/高铁实时班次和酒店实时价格未接入。

