package com.example.ilink.tools.calculator;

import com.example.ilink.tools.core.Tool;
import com.example.ilink.tools.core.ToolArguments;
import com.example.ilink.tools.core.ToolContext;
import com.example.ilink.tools.core.ToolDefinition;
import com.example.ilink.tools.core.ToolResult;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;

/** 根据中文亲属链条推导可能的亲属称呼。 */
public final class RelationTool implements Tool {

    public static final String NAME = "relation_query";

    private static final LinkedHashMap<String, String> TERMS = new LinkedHashMap<>();
    private static final LinkedHashMap<String, String> DISPLAY = new LinkedHashMap<>();
    private static final LinkedHashMap<String, String> COMPOSITION = new LinkedHashMap<>();

    static {
        TERMS.put("爸爸", "father");
        TERMS.put("父亲", "father");
        TERMS.put("爹", "father");
        TERMS.put("妈妈", "mother");
        TERMS.put("母亲", "mother");
        TERMS.put("娘", "mother");
        TERMS.put("哥哥", "elder_brother");
        TERMS.put("弟弟", "younger_brother");
        TERMS.put("姐姐", "elder_sister");
        TERMS.put("妹妹", "younger_sister");
        TERMS.put("爷爷", "grandfather_f");
        TERMS.put("奶奶", "grandmother_f");
        TERMS.put("外公", "grandfather_m");
        TERMS.put("姥爷", "grandfather_m");
        TERMS.put("外婆", "grandmother_m");
        TERMS.put("姥姥", "grandmother_m");
        TERMS.put("儿子", "son");
        TERMS.put("女儿", "daughter");
        TERMS.put("丈夫", "husband");
        TERMS.put("老公", "husband");
        TERMS.put("妻子", "wife");
        TERMS.put("老婆", "wife");
        TERMS.put("伯父", "uncle_f_elder");
        TERMS.put("叔父", "uncle_f_younger");
        TERMS.put("姑妈", "aunt_f");
        TERMS.put("姑姑", "aunt_f");
        TERMS.put("舅舅", "uncle_m");
        TERMS.put("姨妈", "aunt_m");
        TERMS.put("公公", "father_in_law_h");
        TERMS.put("婆婆", "mother_in_law_h");
        TERMS.put("岳父", "father_in_law_w");
        TERMS.put("岳母", "mother_in_law_w");
        TERMS.put("侄子", "nephew_brother");
        TERMS.put("侄女", "niece_brother");
        TERMS.put("外甥", "nephew_sister");
        TERMS.put("外甥女", "niece_sister");
        TERMS.put("孙子", "grandson_son");
        TERMS.put("孙女", "granddaughter_son");
        TERMS.put("外孙", "grandson_daughter");
        TERMS.put("外孙女", "granddaughter_daughter");
        TERMS.put("姐夫", "brother_in_law_es");
        TERMS.put("妹夫", "brother_in_law_ys");
        TERMS.put("嫂子", "sister_in_law_eb");
        TERMS.put("弟媳", "sister_in_law_yb");
        TERMS.put("儿媳", "daughter_in_law");
        TERMS.put("女婿", "son_in_law");
        TERMS.put("伯母", "aunt_f_elder_wife");
        TERMS.put("婶婶", "aunt_f_younger_wife");
        TERMS.put("舅妈", "aunt_m_wife");
        TERMS.put("姑父", "uncle_f_husband");
        TERMS.put("姨父", "uncle_m_husband");
        TERMS.put("伯祖父", "granduncle_f_elder");
        TERMS.put("叔祖父", "granduncle_f_younger");
        TERMS.put("姑婆", "grandaunt_f");
        TERMS.put("舅公", "granduncle_m");
        TERMS.put("姨婆", "grandaunt_m");

        DISPLAY.put("father", "爸爸");
        DISPLAY.put("mother", "妈妈");
        DISPLAY.put("elder_brother", "哥哥");
        DISPLAY.put("younger_brother", "弟弟");
        DISPLAY.put("elder_sister", "姐姐");
        DISPLAY.put("younger_sister", "妹妹");
        DISPLAY.put("grandfather_f", "爷爷");
        DISPLAY.put("grandmother_f", "奶奶");
        DISPLAY.put("grandfather_m", "外公/姥爷");
        DISPLAY.put("grandmother_m", "外婆/姥姥");
        DISPLAY.put("son", "儿子");
        DISPLAY.put("daughter", "女儿");
        DISPLAY.put("husband", "丈夫/老公");
        DISPLAY.put("wife", "妻子/老婆");
        DISPLAY.put("uncle_f_elder", "伯父");
        DISPLAY.put("uncle_f_younger", "叔父");
        DISPLAY.put("aunt_f", "姑妈/姑姑");
        DISPLAY.put("uncle_m", "舅舅");
        DISPLAY.put("aunt_m", "姨妈");
        DISPLAY.put("father_in_law_h", "公公");
        DISPLAY.put("mother_in_law_h", "婆婆");
        DISPLAY.put("father_in_law_w", "岳父");
        DISPLAY.put("mother_in_law_w", "岳母");
        DISPLAY.put("nephew_brother", "侄子");
        DISPLAY.put("niece_brother", "侄女");
        DISPLAY.put("nephew_sister", "外甥");
        DISPLAY.put("niece_sister", "外甥女");
        DISPLAY.put("grandson_son", "孙子");
        DISPLAY.put("granddaughter_son", "孙女");
        DISPLAY.put("grandson_daughter", "外孙");
        DISPLAY.put("granddaughter_daughter", "外孙女");
        DISPLAY.put("brother_in_law_es", "姐夫/妹夫");
        DISPLAY.put("brother_in_law_ys", "妹夫/姐夫");
        DISPLAY.put("sister_in_law_eb", "嫂子/弟媳");
        DISPLAY.put("sister_in_law_yb", "弟媳/嫂子");
        DISPLAY.put("daughter_in_law", "儿媳");
        DISPLAY.put("son_in_law", "女婿");
        DISPLAY.put("aunt_f_elder_wife", "伯母");
        DISPLAY.put("aunt_f_younger_wife", "婶婶");
        DISPLAY.put("aunt_m_wife", "舅妈");
        DISPLAY.put("uncle_f_husband", "姑父");
        DISPLAY.put("uncle_m_husband", "姨父");
        DISPLAY.put("granduncle_f_elder", "伯祖父");
        DISPLAY.put("granduncle_f_younger", "叔祖父");
        DISPLAY.put("grandaunt_f", "姑婆");
        DISPLAY.put("granduncle_m", "舅公");
        DISPLAY.put("grandaunt_m", "姨婆");

        // Self -> parent
        COMPOSITION.put("father:father", "grandfather_f");
        COMPOSITION.put("father:mother", "grandmother_f");
        COMPOSITION.put("mother:father", "grandfather_m");
        COMPOSITION.put("mother:mother", "grandmother_m");

        // Parent -> sibling
        COMPOSITION.put("father:elder_brother", "uncle_f_elder");
        COMPOSITION.put("father:younger_brother", "uncle_f_younger");
        COMPOSITION.put("father:elder_sister", "aunt_f");
        COMPOSITION.put("father:younger_sister", "aunt_f");
        COMPOSITION.put("mother:elder_brother", "uncle_m");
        COMPOSITION.put("mother:younger_brother", "uncle_m");
        COMPOSITION.put("mother:elder_sister", "aunt_m");
        COMPOSITION.put("mother:younger_sister", "aunt_m");

        // Parent -> child (ego's siblings)
        COMPOSITION.put("father:son", "elder_brother");
        COMPOSITION.put("father:daughter", "elder_sister");
        COMPOSITION.put("mother:son", "elder_brother");
        COMPOSITION.put("mother:daughter", "elder_sister");

        // Sibling -> parent (same parents)
        COMPOSITION.put("elder_brother:father", "father");
        COMPOSITION.put("elder_brother:mother", "mother");
        COMPOSITION.put("younger_brother:father", "father");
        COMPOSITION.put("younger_brother:mother", "mother");
        COMPOSITION.put("elder_sister:father", "father");
        COMPOSITION.put("elder_sister:mother", "mother");
        COMPOSITION.put("younger_sister:father", "father");
        COMPOSITION.put("younger_sister:mother", "mother");

        // Sibling -> spouse
        COMPOSITION.put("elder_brother:wife", "sister_in_law_eb");
        COMPOSITION.put("younger_brother:wife", "sister_in_law_yb");
        COMPOSITION.put("elder_sister:husband", "brother_in_law_es");
        COMPOSITION.put("younger_sister:husband", "brother_in_law_ys");

        // Spouse -> parent
        COMPOSITION.put("husband:father", "father_in_law_h");
        COMPOSITION.put("husband:mother", "mother_in_law_h");
        COMPOSITION.put("wife:father", "father_in_law_w");
        COMPOSITION.put("wife:mother", "mother_in_law_w");

        // Spouse -> sibling
        COMPOSITION.put("husband:elder_brother", "elder_brother");
        COMPOSITION.put("husband:younger_brother", "younger_brother");
        COMPOSITION.put("husband:elder_sister", "elder_sister");
        COMPOSITION.put("husband:younger_sister", "younger_sister");
        COMPOSITION.put("wife:elder_brother", "elder_brother");
        COMPOSITION.put("wife:younger_brother", "younger_brother");
        COMPOSITION.put("wife:elder_sister", "elder_sister");
        COMPOSITION.put("wife:younger_sister", "younger_sister");

        // Child -> spouse
        COMPOSITION.put("son:wife", "daughter_in_law");
        COMPOSITION.put("daughter:husband", "son_in_law");

        // Child -> child
        COMPOSITION.put("son:son", "grandson_son");
        COMPOSITION.put("son:daughter", "granddaughter_son");
        COMPOSITION.put("daughter:son", "grandson_daughter");
        COMPOSITION.put("daughter:daughter", "granddaughter_daughter");

        // Uncle/aunt -> spouse
        COMPOSITION.put("uncle_f_elder:wife", "aunt_f_elder_wife");
        COMPOSITION.put("uncle_f_younger:wife", "aunt_f_younger_wife");
        COMPOSITION.put("uncle_m:wife", "aunt_m_wife");
        COMPOSITION.put("aunt_f:husband", "uncle_f_husband");
        COMPOSITION.put("aunt_m:husband", "uncle_m_husband");

        // Grandparent -> child (parent's siblings)
        COMPOSITION.put("grandfather_f:son", "father");
        COMPOSITION.put("grandfather_f:daughter", "aunt_f");
        COMPOSITION.put("grandmother_f:son", "father");
        COMPOSITION.put("grandmother_f:daughter", "aunt_f");
        COMPOSITION.put("grandfather_m:son", "uncle_m");
        COMPOSITION.put("grandfather_m:daughter", "mother");
        COMPOSITION.put("grandmother_m:son", "uncle_m");
        COMPOSITION.put("grandmother_m:daughter", "mother");

        // Grandparent -> sibling of grandparent
        COMPOSITION.put("grandfather_f:elder_brother", "granduncle_f_elder");
        COMPOSITION.put("grandfather_f:younger_brother", "granduncle_f_younger");
        COMPOSITION.put("grandfather_f:elder_sister", "grandaunt_f");
        COMPOSITION.put("grandfather_f:younger_sister", "grandaunt_f");
        COMPOSITION.put("grandmother_f:elder_brother", "granduncle_f_elder");
        COMPOSITION.put("grandmother_f:younger_brother", "granduncle_f_younger");

        // Sibling's child
        COMPOSITION.put("elder_brother:son", "nephew_brother");
        COMPOSITION.put("elder_brother:daughter", "niece_brother");
        COMPOSITION.put("younger_brother:son", "nephew_brother");
        COMPOSITION.put("younger_brother:daughter", "niece_brother");
        COMPOSITION.put("elder_sister:son", "nephew_sister");
        COMPOSITION.put("elder_sister:daughter", "niece_sister");
        COMPOSITION.put("younger_sister:son", "nephew_sister");
        COMPOSITION.put("younger_sister:daughter", "niece_sister");

        // Grandchild -> partner
        COMPOSITION.put("grandson_son:wife", "granddaughter_in_law");
        COMPOSITION.put("granddaughter_son:husband", "grandson_in_law");
        COMPOSITION.put("grandson_daughter:wife", "granddaughter_in_law");
        COMPOSITION.put("granddaughter_daughter:husband", "grandson_in_law");

        // Grandparent's sibling's spouse
        COMPOSITION.put("granduncle_f_elder:wife", "grandaunt_f_elder_wife");
        COMPOSITION.put("granduncle_f_younger:wife", "grandaunt_f_younger_wife");
        COMPOSITION.put("grandaunt_f:husband", "granduncle_f_husband");
        COMPOSITION.put("granduncle_m:wife", "grandaunt_m_wife");
        COMPOSITION.put("grandaunt_m:husband", "granduncle_m_husband");

        // Cross mappings for gender-neutral traversal
        COMPOSITION.put("father:brother", "uncle_f_elder");
        COMPOSITION.put("father:sister", "aunt_f");
        COMPOSITION.put("mother:brother", "uncle_m");
        COMPOSITION.put("mother:sister", "aunt_m");
        COMPOSITION.put("elder_brother:brother", "elder_brother");
        COMPOSITION.put("elder_brother:sister", "elder_sister");
        COMPOSITION.put("younger_brother:brother", "younger_brother");
        COMPOSITION.put("younger_brother:sister", "younger_sister");
    }

    private final ToolDefinition definition;

    /** 创建亲戚称呼计算工具并声明 Function Calling 参数。 */
    public RelationTool() {
        JsonObject properties = new JsonObject();
        properties.add("chain", ToolDefinition.stringProperty("关系链，如「爸爸的哥哥」"));
        this.definition = new ToolDefinition(
                NAME, "亲戚关系查询",
                "查询中国亲戚关系，支持多级关系链，如「爸爸的哥哥」、「妈妈的妹妹的丈夫」等",
                ToolDefinition.objectParameters(properties, "chain"), true);
    }

    @Override
    /** 返回亲戚称呼计算工具的标准定义。 */
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    /** 解析“爸爸的哥哥”这类链式表达并返回称呼结果。 */
    public ToolResult execute(ToolContext context, JsonObject arguments) {
        String chain = ToolArguments.requireString(arguments, "chain");

        if (chain.isBlank()) {
            return ToolResult.failure("请输入关系链");
        }

        String[] parts = chain.split("的");
        if (parts.length == 0) {
            return ToolResult.failure("无效的关系链");
        }

        String current = TERMS.get(parts[0]);
        if (current == null) {
            return ToolResult.failure("无法识别的关系: " + parts[0]);
        }

        for (int i = 1; i < parts.length; i++) {
            String part = parts[i].trim();
            if (part.isEmpty()) continue;
            String next = TERMS.get(part);
            if (next == null) {
                return ToolResult.failure("无法识别的关系: " + part);
            }
            String key = current + ":" + next;
            String result = COMPOSITION.get(key);
            if (result == null) {
                return ToolResult.failure("无法计算关系: " + DISPLAY.getOrDefault(current, current) + "的" + DISPLAY.getOrDefault(next, next));
            }
            current = result;
        }

        String display = DISPLAY.get(current);
        if (display == null) {
            return ToolResult.failure("无法确定最终关系名称");
        }

        return ToolResult.success("━━━━ 关系查询 ━━━━\n"
                + chain + " = " + display + "\n"
                + "━━━━━━━━━━━━━━");
    }
}
