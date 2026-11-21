package org.ipz.backend.service.tools;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

public interface AiTool {

    Map<String, Object> getDefinition();

    Object execute(JsonNode args, AiToolContext context);
}
