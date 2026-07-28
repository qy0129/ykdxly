package com.example.ilink.application.routing;

import com.example.ilink.application.conversation.UserSessionStore;
import com.example.ilink.capabilities.memory.MemoryService;
import com.example.ilink.capabilities.persona.Personas;

/** 构造路由模型使用的系统提示词。 */
public final class RoutePromptBuilder {

    private final MemoryService memoryService;
    private final UserSessionStore sessions;

    public RoutePromptBuilder(MemoryService memoryService, UserSessionStore sessions) {
        this.memoryService = memoryService;
        this.sessions = sessions;
    }

    public String build(String userId, IntentContext context) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你只负责把用户请求拆成可执行动作，不回答用户问题。必须严格依据语义判断，禁止仅凭单个词触发功能。只输出一行JSON。\n");
        prompt.append("当前状态：pending_image=").append(context.pendingImage())
                .append(", has_last_image=").append(context.hasLastImage())
                .append(", pending_draw_size=").append(context.pendingDraw())
                .append(", has_document=").append(context.hasDocument())
                .append(", pending_calendar=").append(context.pendingCalendar()).append("。\n");
        if (sessions != null) {
            String currentLocation = sessions.getCurrentLocation(userId);
            if (currentLocation != null && !currentLocation.isBlank()) {
                prompt.append("用户最近确认的当前位置：").append(currentLocation).append("。\n");
            }
        }
        if (memoryService != null) {
            String memory = memoryService.prompt(userId);
            if (!memory.isBlank()) prompt.append(memory).append('\n');
        }
        prompt.append("可选人设名称：").append(String.join("、", Personas.getAll().keySet())).append("。\n\n");

        prompt.append("多动作拆分规则：\n");
        prompt.append("- 输出actions数组。一段话有几个相互独立、需要调用不同功能完成的明确要求，就输出几个动作；最多6个。\n");
        prompt.append("- 每个动作的action_text只保留该动作对应的原始要求，不能把整段话重复填给每个动作。\n");
        prompt.append("- 保持用户表达的逻辑顺序；需要前一步结果的动作放在后面。可能要求补充地点或时间的动作也可暂停，用户确认后会自动继续。\n");
        prompt.append("- 不要把同一任务的参数错误拆开。例如‘从A到B途中吃面’是一个travel_plan动作，meal_keyword=面；"
                + "‘查天气并从A到B途中吃面’则拆成weather和travel_plan两个动作。\n");
        prompt.append("- 例如‘查杭州今天下午天气，计算100加20，再从西湖去杭州西站途中吃面’必须输出三个动作："
                + "weather、calculator、travel_plan。\n");
        prompt.append("- 只有一个要求时actions数组只放一个动作，不能为了凑数量重复拆分。\n\n");

        prompt.append("意图规则：\n");
        prompt.append("1. chat：问答、聊天、讲笑话、写作、翻译、总结、建议以及所有不属于下述功能的请求。"
                + "语音输出和音色要求只是回复形式，不会把 chat 变成 draw。"
                + "例如‘用小男孩的音色给我讲个笑话’必须是 chat、reply_mode=voice、voice_style=boy。\n");
        prompt.append("2. draw：用户明确要求生成、绘制一张新图片时使用。"
                + "必须存在创建视觉内容的明确语义；仅出现‘画面感’、‘讲故事’、‘声音’等词不能判为 draw。"
                + "将英文绘图提示写入 en_prompt，中文画面说明写入 cn_description。\n");
        prompt.append("3. persona_switch：用户明确要求切换机器人长期说话人设时使用，persona 必须从可选人设名称中原样选择。"
                + "人格切换只改变后续对话风格和默认音色，不代表本次要发送语音；因此 reply_mode 必须为 keep，voice_style 必须为 default。"
                + "若用户指定的人格不在可选列表中，仍使用 persona_switch 并将用户原话填入 persona，由程序返回可用人格列表，禁止臆造或替换为其他人格。"
                + "音色、男声、女声、温柔声音不属于人设切换。\n");
        prompt.append("4. audio_transcribe：用户明确要求获取某条历史语音的文字、转写或内容时使用。"
                + "audio_source 表示机器人、用户或任意来源；audio_index 从最新一条开始计数。\n");
        prompt.append("5. image_action：用户明确要求分析、解题或修改已发送图片本身时使用。"
                + "只有 pending_image=true，或用户明确指向上一张图片且 has_last_image=true 时才可使用。"
                + "⚠️ 以下情况不是 image_action，必须判为 document_edit："
                + "A）插入图片到文档：'把图片放到文档里'、'把图片插入文档'、'在文档中添加图片'；"
                + "B）图片内容生成新文档：'图片转文档'、'拍照转文档'、'把图片上的文字整理成文档/表格'。"
                + "A 是把图片文件嵌入已有文档，必须是 document_edit；B 是根据本轮图片内容新建文件，必须是 generate_file，不能使用历史文档。"
                + "image_action 仅用于对图片本身做操作（分析图片内容、解题、美化、裁剪、加滤镜）。"
                + "image_action 选择 analyze、solve、edit 或 clarify，完整要求写入 image_prompt。\n");
        prompt.append("6. draw_size：仅当 pending_draw_size=true 且用户正在回答图片尺寸时使用。"
                + "方形对应1024x1024，竖屏对应768x1024，横屏对应1024x576。"
                + "如果用户转而提出无关请求，应按新请求判断，不要强制 draw_size。\n\n");
        prompt.append("7. document_summary：当前有文件且用户要求总结文件时使用。\n");
        prompt.append("8. document_question：当前有文件且用户根据文件内容提问时使用。\n");
        prompt.append("9. generate_file：用户要求从零创建新文件、或把聊天回答整理成文件时使用。"
                + "⚠️ 禁止用于格式转换！'转成Word/PDF'、'改成docx/pdf格式'、'导出为Word' 这类请求必须判为 document_edit，绝不能判为 generate_file。\n");
        prompt.append("document_action 只能是 none|summary|question|edit；没有文件时必须为 none。"
                + "编辑或格式转换时 output_file_type 为用户指定的格式（docx/pdf/xlsx/pptx/txt/md/csv），否则为 none。\n\n");
        prompt.append("10. weather：用户明确查询某个城市、区县、乡镇的天气、温度、降雨或风力时使用。"
                + "weather_location 必须填写可供 Open-Meteo 检索的英文地点名，例如北京填 Beijing，上海填 Shanghai，"
                + "和平镇填 Heping；用户提供了省、市、县时也要保留这些英文行政区信息。"
                + "全天或未说明时段使用today或tomorrow；上午、下午、晚上分别使用today_morning、"
                + "today_afternoon、today_evening或对应的tomorrow前缀。"
                + "用户未说明地点时 weather_location 为空。\n\n");
        prompt.append("11. task_plan：用户要求制定学习、项目、工作或生活任务计划时使用。"
                + "plan_goal 填写最终目标，plan_deadline 只填写真正的截止日期或时刻，例如后天或3天后，"
                + "plan_available_time 填写每天或各时间段可用时间。若用户说‘我有一小时写作业’，"
                + "这不是截止时间，time_budget_minutes=60，plan_deadline 为空。"
                + "用户同时要求生成Word或PDF时仍然使用task_plan，并设置output_file_type。\n");
        prompt.append("12. plan_adjust：用户要求调整、延期、重新安排当前计划，或说明某项任务已经完成时使用。\n");
        prompt.append("13. plan_progress：用户询问当前计划完成情况、下一项任务或还剩什么时使用。\n\n");
        prompt.append("14. calculator：用户要求四则运算、百分比、折扣、总价、单位换算、汇率、进制、BMI、个税、房贷、"
                + "中文大写金额或亲戚称呼计算时使用。自然语言参数由 CalculatorService 再调用专用工具处理；"
                + "基础四则运算仍可直接使用 calculation_operation、calculation_left、calculation_right 等字段。\n\n");
        prompt.append("15. expense_split：用户要求多人 AA、平分消费、根据不同已付款金额结算或计算谁该转给谁时使用。"
                + "例如“我们三个人吃饭300元，我付了200，张三付了100，怎么算”。\n");
        prompt.append("16. deadline_countdown：用户询问距离某个截止日期或时间还有多久、剩余几天几小时、是否超时时使用。"
                + "将明确的时间表达写入 plan_deadline，例如明天下午六点、2026-07-25 18:00。\n\n");
        prompt.append("17. travel_plan：用户给出起点、终点并要求路线、导航、出行安排或中途停留时使用。"
                + "travel_origin填写最初起点，travel_destination填写最终终点。"
                + "origin_city和destination_city只填写用户明确说出或能从明确地标确定的城市，无法确定时留空，禁止猜测。"
                + "例如‘从杭州西湖去上海外滩’填写origin_city=杭州、destination_city=上海。"
                + "用户说‘先去A、再去B、最后去C’时，A和B等中间地点按顺序写入travel_stops数组，不能忽略。"
                + "没有途经点时travel_stops必须为空数组；‘一个小时’等可用时长填入time_budget_minutes。"
                + "中途想吃面、咖啡等填入meal_keyword；明确出发时间填travel_departure_time。"
                + "即使用户说‘帮我规划’，只要核心是从A到B出行，必须是travel_plan，绝不能是task_plan。\n");
        prompt.append("17a. taxi_trip：用户明确要求打车、叫车、查询打车订单、取消打车订单或询问司机位置时使用。"
                + "新叫车填写travel_origin、travel_destination、origin_city、destination_city；城市从明确地标可确定时填写。"
                + "用户要求叫车时只负责进入报价和确认流程，绝不能自动确认下单。\n");
        prompt.append("18. diet_plan：用户要求饮食规划、外卖推荐、减脂餐、增肌餐或控糖餐时使用。"
                + "diet_goal 填减脂、增肌、控糖、维持体重或空字符串；不要把附近餐厅搜索判为diet_plan。\n");
        prompt.append("19. nearby_food：用户说自己在某位置、询问附近有什么好吃的、附近餐厅或附近外卖时使用。"
                + "nearby_location 填用户明确说出的地点，未重复时可留空；nearby_action 只能是remember或search；"
                + "用户指定麦当劳、肯德基、咖啡、面馆等品牌或餐品时，必须原样写入meal_keyword；"
                + "‘附近有什么好吃的’‘附近吃什么’等泛化查询的meal_keyword必须为空字符串，"
                + "禁止填写‘什么好吃的’‘吃什么’。"
                + "例如‘我现在在阿里园区，我想吃麦当劳’必须是nearby_food，meal_keyword=麦当劳，不是food_order。\n");
        prompt.append("20. calendar_event：用户创建、查询、完成、取消或延后提醒/日程时使用。"
                + "calendar_action 为create|list|complete|cancel|snooze；创建时填写calendar_title、calendar_time、"
                + "calendar_recurrence(none|daily|weekly|monthly|yearly)。"
                + "calendar_time_type为auto|relative|absolute。相对时间还要填写calendar_time_amount和calendar_time_unit(second|minute|hour|day)。"
                + "提前提醒统一换算为calendar_lead_time_seconds。‘30秒后提醒我’表示relative/30/second，绝不能填成提前30分钟；"
                + "只有‘明天8点开会，提前30分钟提醒’才把calendar_lead_time_seconds填为1800。"
                + "pending_calendar=true时，用户是在补充上一轮日历时间，仍输出calendar_event/create并只填写本轮提供的时间字段。\n");
        prompt.append("21. planning_capabilities：用户询问‘你可以帮我做什么规划’、规划功能有哪些时使用。\n\n");
        prompt.append("22. bilibili_search：用户想看剧、看电影、看视频、听歌、听音乐，或明确要求从哔哩哔哩寻找内容时使用。"
                + "bilibili_query填写适合搜索的关键词，bilibili_category只能是study、music、series或video。"
                + "例如‘我想听周杰伦的歌’填写周杰伦 歌曲/music；‘我想看剧’填写热门电视剧/series。"
                + "用户要求制定学习计划时，必须先输出task_plan，再输出bilibili_search，学习资源关键词使用课程主题加‘系统课程’。"
                + "例如‘我想学习线性代数，预计三十天，帮我完成一份计划’必须输出task_plan和bilibili_search两个动作。\n\n");
        prompt.append("23. media_lookup：用户要求查询动漫、番剧、歌手、歌曲、专辑或歌词的资料时使用。"
                + "media_query填写作品、歌手或歌曲关键词；media_category只能是anime、music或lyrics。"
                + "查询完成后程序会自动追加哔哩哔哩入口，不要再输出重复的bilibili_search动作。"
                + "例如‘查一下海贼王动漫资料’使用海贼王/anime；‘查周杰伦的专辑’使用周杰伦/music；"
                + "‘找晴天的歌词’使用晴天/lyrics。单纯‘我想听周杰伦的歌’仍使用bilibili_search。\n");
        prompt.append("24. email_query：用户查询QQ邮箱未读、重要邮件或按关键词搜索邮件时使用。"
                + "email_action只能是unread、important或search；email_keyword只在搜索指定发件人、主题或内容时填写。"
                + "例如‘我有什么未读邮件’使用unread；‘有没有重要邮件’使用important；"
                + "‘查腾讯发来的邮件’使用search并填写腾讯。\n");
        prompt.append("25. food_order：用户明确指定餐厅，并要求点外卖、点餐或获取外卖平台入口时使用。"
                + "food_order_restaurants填写餐厅名称，多个名称用逗号分隔。"
                + "用户同时提供当前位置或收货地点时，将地点写入nearby_location。"
                + "只表达‘我在某地，想吃某品牌/餐品’但没有要求下单时使用nearby_food；"
                + "只问附近有什么餐厅时仍使用nearby_food；要求营养或减脂外卖建议时仍使用diet_plan。\n\n");

        prompt.append("Document rules: when has_document=true, use document_summary for summarizing, document_question for questions, document_edit when the user asks to modify, rewrite, delete, add, correct, insert image into document, or convert the format of the current document (PDF转Word/Word转PDF等), and generate_file only when the user asks to create a brand new file from scratch. document_action must be none, summary, question, or edit. output_file_type supports: docx, pdf, xlsx, pptx, txt, md, csv, or none. "
        + "⚠️ 强制规则：'转成Word/PDF/Excel'='改成xxx格式'='导出为xxx' → 必须 document_edit；'把图片放到/插入/添加到文档' → 必须 document_edit。"
        + "output_file_type 规则：用户说'生成 Word/文档/转成Word/改成Word格式' → docx；说'PDF/转成PDF' → pdf；说'Excel/xlsx' → xlsx；说'PPT/pptx' → pptx；说'TXT/文本' → txt；说'Markdown/md' → md；说'CSV' → csv；未明确说明 → none。\n");
        prompt.append("输出规则：\n");
        prompt.append("- 用户明确只要语音时 reply_mode=voice；明确同时要文字和语音时为both；要求关闭语音时为text；否则为keep。\n");
        prompt.append("- voice_style：小男孩=boy，小女孩=girl，成年男声=male，成年女声=female，温柔柔和=warm，活泼元气=lively，无要求=default。\n");
        prompt.append("- 未使用的字符串字段填空字符串，image_size填none，image_action填none，audio_source填any，audio_index填1，weather_day填today。\n");
        prompt.append("最高优先级校验示例：用户输入‘用小男孩的音色给我讲个笑话’时，"
                + "actions只能有一个chat动作，reply_mode必须为voice，voice_style必须为boy，"
                + "en_prompt和cn_description必须为空，image_size必须为none。\n");
        prompt.append("提交结果前逐项检查：音色要求是否正确写入voice_style；语音要求是否正确写入reply_mode；"
                + "没有明确生成图片要求时intent绝不能为draw。");
        prompt.append("\n输出必须是以下结构，且每个动作包含全部字段："
                + "{\"actions\":[{\"action_text\":\"当前动作对应的用户原始要求\","
                + "\"intent\":\"chat|draw|persona_switch|audio_transcribe|image_action|draw_size|document_summary|document_question|generate_file|document_edit|weather|task_plan|plan_adjust|plan_progress|calculator|expense_split|deadline_countdown|travel_plan|taxi_trip|diet_plan|nearby_food|calendar_event|planning_capabilities|bilibili_search|media_lookup|email_query|food_order\","
                + "\"en_prompt\":\"\",\"cn_description\":\"\","
                + "\"image_size\":\"none|1024x1024|768x1024|1024x576\","
                + "\"reply_mode\":\"keep|text|voice|both\","
                + "\"voice_style\":\"default|boy|girl|male|female|warm|lively\","
                + "\"persona\":\"\",\"image_action\":\"none|analyze|solve|edit|clarify\","
                + "\"image_prompt\":\"\",\"audio_source\":\"any|bot|user\",\"audio_index\":1,"
                + "\"document_action\":\"none|summary|question|edit\",\"output_file_type\":\"none|docx|pdf|xlsx|pptx|txt|md|csv\","
              
                + "\"weather_location\":\"\",\"weather_day\":\"today|tomorrow|today_morning|today_afternoon|today_evening|tomorrow_morning|tomorrow_afternoon|tomorrow_evening\","
                + "\"plan_goal\":\"\",\"plan_deadline\":\"\",\"plan_available_time\":\"\","
                + "\"calculation_operation\":\"add|subtract|multiply|divide|percentage|total_price\","
                + "\"calculation_left\":\"0\",\"calculation_right\":\"0\","
                + "\"calculation_quantity\":\"1\",\"calculation_unit_price\":\"0\","
                + "\"calculation_discount_percent\":\"0\","
                + "\"travel_origin\":\"\",\"travel_destination\":\"\",\"travel_stops\":[],"
                + "\"origin_city\":\"\",\"destination_city\":\"\",\"travel_departure_time\":\"\","
                + "\"time_budget_minutes\":0,\"meal_keyword\":\"\",\"diet_goal\":\"\","
                + "\"nearby_location\":\"\",\"nearby_action\":\"remember|search\","
                + "\"calendar_action\":\"create|list|complete|cancel|snooze\",\"calendar_title\":\"\","
                + "\"calendar_time\":\"\",\"calendar_recurrence\":\"none|daily|weekly|monthly|yearly\","
                + "\"calendar_reminder_minutes\":0,\"calendar_time_type\":\"auto|relative|absolute\","
                + "\"calendar_time_amount\":0,\"calendar_time_unit\":\"second|minute|hour|day\","
                + "\"calendar_lead_time_seconds\":0,\"bilibili_query\":\"\","
                + "\"bilibili_category\":\"study|music|series|video\","
                + "\"media_query\":\"\",\"media_category\":\"anime|music|lyrics\","
                + "\"email_action\":\"unread|important|search\",\"email_keyword\":\"\","
                + "\"food_order_restaurants\":\"\"}]}。");
        return prompt.toString();
    }

}
