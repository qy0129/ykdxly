package com.example.ilink.tools.persona;

import com.example.ilink.conversation.UserSessionStore;
import com.example.ilink.feature.persona.Personas;
import com.example.ilink.tools.core.Tool;
import com.example.ilink.tools.core.ToolArguments;
import com.example.ilink.tools.core.ToolContext;
import com.example.ilink.tools.core.ToolDefinition;
import com.example.ilink.tools.core.ToolResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Function Calling 人设切换工具。 */
public final class PersonaSwitchTool implements Tool {

    public static final String NAME = "switch_persona";

    private final UserSessionStore sessions;
    private final ToolDefinition definition;

    /** 根据当前资源中的人设列表创建工具定义。 */
    public PersonaSwitchTool(UserSessionStore sessions) {
        this.sessions = sessions;

        JsonObject persona = ToolDefinition.stringProperty("需要切换到的人设名称");
        JsonArray names = new JsonArray();
        Personas.getAll().keySet().forEach(names::add);
        persona.add("enum", names);
        JsonObject properties = new JsonObject();
        properties.add("persona", persona);
        this.definition = new ToolDefinition(
                NAME,
                "切换人设",
                "切换机器人后续对话使用的长期说话人设。音色要求不属于人设切换。",
                ToolDefinition.objectParameters(properties, "persona"),
                true);
    }

    /** 返回人设切换工具定义。 */
    @Override
    public ToolDefinition definition() {
        return definition;
    }

    /** 校验并保存用户选择的人设。 */
    @Override
    public ToolResult execute(ToolContext context, JsonObject arguments) {
        String persona = ToolArguments.requireString(arguments, "persona");
        if (Personas.get(persona) == null) {
            return ToolResult.failure("不存在该人设，可用人设：" + String.join("、", Personas.getAll().keySet()));
        }
        sessions.setPersona(context.userId(), persona);
        return ToolResult.success("好的，已切换为" + persona + "风格。");
    }
}
