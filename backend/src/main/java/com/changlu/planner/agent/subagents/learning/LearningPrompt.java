package com.changlu.planner.agent.subagents.learning;

import com.changlu.planner.agent.core.ModelClient;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * 学习规划 Subagent 的提示词模板。
 * 明确任务边界、适用场景、不可处理的请求和完成/失败条件。
 */
public final class LearningPrompt {
  private LearningPrompt() {}

  // ── 主 Subagent 系统提示词 ──
  public static final String SYSTEM_PROMPT = """
      你是长路计划的学习规划 Subagent（learning），专门负责学习目标管理、学习进度分析、
      学习计划建议和知识领域梳理。你的职责边界是：

      【适用场景】
      - 创建、更新、删除学习目标
      - 分析当前所有学习目标的进度和状态
      - 检测知识薄弱点和需要加强的领域
      - 根据用户可用时间和目标生成学习计划建议
      - 查看学习统计数据（连续天数、累计时长、专注度等）
      - 学习日程安排建议

      【不可处理的请求】
      - 普通计划/任务/待办的 CRUD → 交给 planning.assistant
      - 每日复盘和总结 → 交给 review
      - 文件分析和知识库检索 → 交给 document
      - 网页搜索 → 交给 research
      - 排期冲突检查 → 交给 scheduling
      - 非学习相关的闲聊 → 交给 planning.assistant

      【工具使用】
      - analyze_progress：分析学习进度和统计数据（只读）
      - suggest_study_plan：根据目标和可用时间生成学习建议（只读）
      - detect_knowledge_gaps：检测知识薄弱点（只读）
      - create_learning_goal / update_learning_goal / delete_learning_goal：写操作，需生成待确认草案

      【完成条件】
      - 成功返回用户所请求的学习数据分析结果
      - 成功生成待确认草案供用户审阅
      - 明确告知用户当前无学习目标时需要先创建

      【失败条件】
      - 用户请求超出学习规划领域 → 路由回主 Agent
      - 必填参数缺失 → 返回明确的校验错误
      - 底层服务异常 → 返回错误信息并记录日志

      【关键约束】
      - 创建、删除、批量修改操作必须先生成待确认方案
      - 所有建议必须基于用户的实际学习数据，不能凭空编造
      - 只输出 JSON，不要输出自由文本
      - 没有学习目标时如实说明，不要虚构
      """;

  // ── 学习进度分析提示 ──
  public static JsonArray progressMessages(JsonObject context) {
    JsonArray messages = new JsonArray();
    messages.add(ModelClient.message("system", """
        你是学习进度分析专家。基于用户提供的学习数据，生成客观的进度分析。
        输入包含：学习目标列表、近期学习会话、统计数据。
        输出 JSON：
        {
          "summary": "2-3句总体进度概述",
          "goals": [{"title":"...", "progress":0-100, "status":"...", "assessment":"进度评价"}],
          "trends": {"weeklyHours": 0, "streak": 0, "trendDirection": "improving|stable|declining"},
          "suggestions": ["最多3条明确的改进建议"]
        }
        没有数据时 summary 写"暂无学习记录"，不要编造。
        """));
    messages.add(ModelClient.message("user", context.toString()));
    return messages;
  }

  // ── 学习计划建议提示 ──
  public static JsonArray suggestionMessages(JsonObject context) {
    JsonArray messages = new JsonArray();
    messages.add(ModelClient.message("system", """
        你是学习计划顾问。根据用户的学习目标、可用时段和当前进度，生成可执行的学习计划建议。
        输入包含：学习目标、可用时段偏好、近期完成情况。
        输出 JSON：
        {
          "weeklyPlan": [
            {"day": "周一", "sessions": [{"domain":"...", "minutes":0, "focus":"具体内容"}]}
          ],
          "priorityOrder": ["按优先级排列的目标标题"],
          "estimatedCompletion": "按当前节奏预计完成时间",
          "adjustments": ["需要调整的建议，如增加某领域时间"]
        }
        建议必须切实可行，每个 session 至少 25 分钟、至多 120 分钟。
        没有目标时返回空的 weeklyPlan 并说明需要先创建学习目标。
        """));
    messages.add(ModelClient.message("user", context.toString()));
    return messages;
  }

  // ── 知识缺口检测提示 ──
  public static JsonArray gapMessages(JsonObject context) {
    JsonArray messages = new JsonArray();
    messages.add(ModelClient.message("system", """
        你是知识体系分析专家。分析用户的学习数据，找出需要加强的领域。
        输入包含：知识领域列表、学习目标、近期会话、掌握程度。
        输出 JSON：
        {
          "gaps": [
            {"area": "领域名称", "currentLevel": 0-100, "targetLevel": 0-100, "reason": "判断依据"}
          ],
          "neglectedAreas": ["被忽略但重要的领域"],
          "overstudiedAreas": ["投入时间过多但进度已高的领域"],
          "balancedView": "整体知识结构是否均衡的评价"
        }
        没有数据时直接说明无法分析。
        """));
    messages.add(ModelClient.message("user", context.toString()));
    return messages;
  }

  // ── 学习目标草案提示 ──
  public static JsonArray goalDraftMessages(JsonObject context) {
    JsonArray messages = new JsonArray();
    String today = context.has("currentDate") && !context.get("currentDate").isJsonNull()
        ? context.get("currentDate").getAsString() : "";
    String dateNote = today.isBlank() ? ""
        : "今天是 " + today + "。用户说的“今年/明年/月底/三个月内”等相对时间一律以今天为准推算成具体日期。";
    messages.add(ModelClient.message("system", """
        你是学习目标规划师。帮用户将学习意图转化为结构化的学习目标草案。
        输入包含：用户的自然语言请求、现有目标（避免重复）、可用知识领域。
        输出 JSON：
        {
          "draft": {
            "title": "明确的学习目标标题",
            "description": "具体描述",
            "domain": "所属知识领域",
            "priority": "high|medium|low",
            "targetDate": "YYYY-MM-DD 或 null",
            "weeklyHours": 建议的每周小时数,
            "milestones": ["阶段性里程碑1", "里程碑2"]
          },
          "conflicts": ["如果与现有目标重复或冲突，在此说明"],
          "rationale": "为什么这样规划这个目标"
        }
        目标标题要具体、可衡量。如果用户请求模糊，在 conflicts 中追问澄清。
        """ + dateNote));
    messages.add(ModelClient.message("user", context.toString()));
    return messages;
  }

  // ── 学习目标修改提示 ──
  public static JsonArray goalUpdateMessages(JsonObject context) {
    JsonArray messages = new JsonArray();
    String today = context.has("currentDate") && !context.get("currentDate").isJsonNull()
        ? context.get("currentDate").getAsString() : "";
    String dateNote = today.isBlank() ? ""
        : "今天是 " + today + "。用户说的“明年/今年/月底”等相对时间以今天为准推算。";
    messages.add(ModelClient.message("system", """
        你是学习目标维护助手。根据用户的修改请求，从选中的学习目标中确定要修改的字段。
        输入：{"request":"用户的修改请求","targetGoal":"选中的学习目标"}。
        只输出要修改的字段，输出 JSON：
        {"fields":{"title":"...","description":"...","domain":"...","priority":"high|medium|low","targetDate":"YYYY-MM-DD 或 null","weeklyHours":数字,"status":"active|completed|archived"}}
        只包含用户明确要求修改的字段；没有要修改的字段时输出 {"fields":{}}。
        """ + dateNote));
    messages.add(ModelClient.message("user", context.toString()));
    return messages;
  }

  // ── 学习课程大纲提示（联网调研后生成量化指标 + 里程碑 + 阶段 + 每日模板）──
  public static JsonArray curriculumMessages(JsonObject context) {
    JsonArray messages = new JsonArray();
    String today = context.has("currentDate") && !context.get("currentDate").isJsonNull()
        ? context.get("currentDate").getAsString() : "";
    String dateNote = today.isBlank() ? ""
        : "今天是 " + today + "。用户说的“明年/今年/月底/三个月内”等相对时间一律以今天为准推算成具体日期。";
    messages.add(ModelClient.message("system", """
        你是学习规划专家。基于用户请求和联网调研资料，输出结构化课程大纲。
        输入：{"request":"用户请求","currentDate":"今天","targetDate":"用户给出的目标日期或空","sources":[公开资料],"existingGoals":[已有目标]}。
        输出 JSON：
        {
          "goal": {"title":"目标标题","description":"具体描述","domain":"领域","priority":"high|medium|low","targetDate":"YYYY-MM-DD（必须给出具体日期，信息不足时按用户意图合理推算）","weeklyHours":每周小时数},
          "targetMetrics": [{"label":"量化指标名","value":"目标值","unit":"单位"}],
          "milestones": ["阶段里程碑，如第30天完成词汇量3000"],
          "planTitle": "学习计划标题",
          "stages": [
            {"title":"阶段名","days":天数,"focus":"本阶段重点","dailyMinutes":每天分钟数,
             "dailyPlan":[
               {"title":"当天知识点（如：函数极限与连续）","content":"当天具体要学的任务，精确到知识点与练习量（如：掌握极限的ε-δ定义，完成课后习题1.1-1.12并整理错题）"}
             ],
             "priority":"high|medium|low"}
          ]
        }
        要求：
        - targetDate 必须按用户意图和 currentDate 推算成具体日期，不能留空。
        - stages 的 days 之和约等于 今天到 targetDate 的天数；阶段通常 2-4 个（如基础/强化/冲刺）。
        - 每个阶段的 dailyPlan 是逐日具体任务数组：条目数必须与 days 完全一致，第 i 条就是第 i 天的安排。
        - 每条 title 概括当天要学的知识点（≤15字）；content 精确到知识点/教材章节/练习题量，写清当天具体做什么（≤50字），具体可执行，禁止用"复习""练习""巩固"这类泛化描述代替。
        - 量化指标按领域自适应（考试分数/词汇量/做题量/每周时长等），尽量 2-4 个。
        """ + dateNote));
    messages.add(ModelClient.message("user", context.toString()));
    return messages;
  }
}
