package com.leoli.gateway.admin.tool;

import lombok.Getter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 工具定义类.
 * 定义单个工具的结构，支持 OpenAI、Claude、Gemini 三种格式.
 *
 * @author leoli
 */
@Getter
public class ToolDefinition {

    private final String name;
    private final String description;
    private final Map<String, Object> parameters;
    private final String category;
    private final boolean readOnly;

    public ToolDefinition(String name, String description, Map<String, Object> parameters,
                          String category, boolean readOnly) {
        this.name = name;
        this.description = description;
        this.parameters = parameters;
        this.category = category;
        this.readOnly = readOnly;
    }

    /**
     * 转换为 OpenAI tools 格式.
     * 格式: {"type": "function", "function": {"name", "description", "parameters"}}
     */
    public Map<String, Object> toOpenAIFormat() {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name);
        function.put("description", description);
        function.put("parameters", parameters);

        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "function");
        tool.put("function", function);

        return tool;
    }

    /**
     * 转换为 Claude tools 格式.
     * 格式: {"name": "...", "description": "...", "input_schema": {...}}
     */
    public Map<String, Object> toClaudeFormat() {
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", name);
        tool.put("description", description);
        tool.put("input_schema", parameters);

        return tool;
    }

    /**
     * 转换为 Gemini functionDeclarations 格式.
     * 格式: {"name": "...", "description": "...", "parameters": {...}}
     */
    public Map<String, Object> toGeminiFormat() {
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", name);
        tool.put("description", description);
        tool.put("parameters", parameters);

        return tool;
    }

    // ===================== 工具定义工厂方法 =====================

    /**
     * 创建简单参数的工具定义
     */
    public static ToolDefinition create(String name, String description,
                                        Map<String, Object> properties,
                                        List<String> required,
                                        String category,
                                        boolean readOnly) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        if (required != null && !required.isEmpty()) {
            parameters.put("required", required);
        }

        return new ToolDefinition(name, description, parameters, category, readOnly);
    }

    /**
     * 创建无参数的工具定义
     */
    public static ToolDefinition createNoArgs(String name, String description,
                                               String category, boolean readOnly) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", new LinkedHashMap<>());

        return new ToolDefinition(name, description, parameters, category, readOnly);
    }

    @Override
    public String toString() {
        return String.format("ToolDefinition{name='%s', category='%s', readOnly=%s}",
                name, category, readOnly);
    }
}