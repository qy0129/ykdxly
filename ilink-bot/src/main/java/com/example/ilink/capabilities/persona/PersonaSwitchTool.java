package com.example.ilink.capabilities.persona;

import com.example.ilink.application.conversation.UserSessionStore;
import com.example.ilink.capabilities.persona.Personas;
import com.example.ilink.application.tooling.Tool;
import com.example.ilink.application.tooling.ToolArguments;
import com.example.ilink.application.tooling.ToolContext;
import com.example.ilink.application.tooling.ToolDefinition;
import com.example.ilink.application.tooling.ToolResult;
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
        String persona = ToolArguments.string(arguments, "persona", "").trim();
        if (Personas.get(persona) == null) {
            String requested = persona.isBlank() ? "该人格" : "“" + persona + "”";
            return ToolResult.failure(requested + "尚未预置。可用人格："
                    + String.join("、", Personas.getAll().keySet()));
        }
        sessions.setPersona(context.userId(), persona);
        return ToolResult.success("好的，已切换为" + persona + "风格。");
    }
}
