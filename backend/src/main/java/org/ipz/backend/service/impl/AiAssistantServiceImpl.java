package org.ipz.backend.service.impl;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ipz.backend.controller.mapper.CartControllerMapper;
import org.ipz.backend.controller.mapper.CheckoutControllerMapper;
import org.ipz.backend.controller.mapper.TrainControllerMapper;
import org.ipz.backend.exception.AiServiceException;
import org.ipz.backend.model.Train;
import org.ipz.backend.model.dto.AiChatRequestDto;
import org.ipz.backend.model.dto.AiChatResponseDto;
import org.ipz.backend.model.dto.AiSmartSearchRequestDto;
import org.ipz.backend.model.dto.AiSmartSearchResponseDto;
import org.ipz.backend.model.dto.TrainFilter;
import org.ipz.backend.service.AiAssistantService;
import org.ipz.backend.service.TrainService;
import org.ipz.backend.service.tools.AiTool;
import org.ipz.backend.service.tools.AiToolContext;
import org.ipz.backend.service.tools.AiToolSupport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiAssistantServiceImpl implements AiAssistantService {

    private static final String CHAT_CACHE_PREFIX = "ai:chat:";
    private static final int MAX_TOOL_ITERATIONS = 5;

    private final Map<String, AiTool> toolStrategies;
    private final AiToolSupport toolSupport;
    private final TrainService trainService;
    private final TrainControllerMapper trainMapper;
    private final CartControllerMapper cartMapper;
    private final CheckoutControllerMapper checkoutMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.ai.base-url:https://api.groq.com/openai/v1}")
    private String aiBaseUrl;

    @Value("${app.ai.api-key:}")
    private String aiApiKey;

    @Value("${app.ai.model:llama-3.3-70b-versatile}")
    private String aiModel;

    @Value("${app.ai.max-history-messages:10}")
    private int maxHistoryMessages;

    @Value("${app.ai.history-ttl-minutes:30}")
    private long historyTtlMinutes;

    @Value("${app.ai.timeout-seconds:30}")
    private int timeoutSeconds;

    private RestClient restClient;
    private String systemPromptTemplate;

    @PostConstruct
    void init() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        int timeoutMs = Math.max(timeoutSeconds, 5) * 1000;
        requestFactory.setConnectTimeout(timeoutMs);
        requestFactory.setReadTimeout(timeoutMs);

        String normalizedBaseUrl = aiBaseUrl.endsWith("/")
                ? aiBaseUrl.substring(0, aiBaseUrl.length() - 1)
                : aiBaseUrl;

        this.restClient = RestClient.builder()
                .baseUrl(normalizedBaseUrl)
                .requestFactory(requestFactory)
                .build();

        this.systemPromptTemplate = loadSystemPromptTemplate();
    }

    @Override
    public AiChatResponseDto chat(AiChatRequestDto requestDto, String firebaseUid, String userEmail, String userName) {
        ensureApiKeyConfigured();

        String sessionId = StringUtils.hasText(requestDto.sessionId())
                ? requestDto.sessionId().trim()
                : UUID.randomUUID().toString();

        String userMessage = requestDto.message().trim();
        long startTime = System.currentTimeMillis();

        List<HistoryEntry> history = loadHistory(sessionId);
        List<Map<String, Object>> messages = new ArrayList<>();

        messages.add(Map.of(
                "role", "system",
                "content", buildSystemPrompt()
        ));

        for (HistoryEntry entry : history) {
            messages.add(Map.of(
                    "role", entry.role(),
                    "content", entry.content()
            ));
        }

        messages.add(Map.of(
                "role", "user",
                "content", userMessage
        ));

        AiToolContext toolContext = new AiToolContext(firebaseUid, userEmail, userName);
        Set<String> usedTools = new LinkedHashSet<>();

        String assistantReply = executeConversationWithTools(messages, toolContext, usedTools);

        history.add(new HistoryEntry("user", userMessage));
        history.add(new HistoryEntry("assistant", assistantReply));
        saveHistory(sessionId, history);

        long elapsedMs = System.currentTimeMillis() - startTime;
        log.info("AI chat completed in {} ms (sessionId={}, tools={}, suggestedTrains={}, bookedTickets={})",
                elapsedMs, sessionId, usedTools, toolContext.getCollectedTrains().size(), toolContext.getBookedTickets().size());

        return AiChatResponseDto.builder()
                .sessionId(sessionId)
                .reply(assistantReply)
                .suggestedTrains(trainMapper.toDtoList(new ArrayList<>(toolContext.getCollectedTrains().values())))
                .usedTools(new ArrayList<>(usedTools))
                .cart(toolContext.getUpdatedCart() != null ? cartMapper.toDto(toolContext.getUpdatedCart()) : null)
                .bookedTickets(checkoutMapper.toPurchasedTicketDtoList(toolContext.getBookedTickets()))
                .build();
    }

    @Override
    public void clearChatHistory(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return;
        }
        try {
            redisTemplate.delete(CHAT_CACHE_PREFIX + sessionId.trim());
            log.info("Cleared AI chat history for session: {}", sessionId);
        } catch (Exception e) {
            log.warn("Failed to delete AI chat history from Redis for session {}: {}", sessionId, e.getMessage());
        }
    }

    @Override
    public AiSmartSearchResponseDto smartSearch(AiSmartSearchRequestDto requestDto) {
        ensureApiKeyConfigured();

        String extractionSystemPrompt = """
                Ти — парсер пошукових запитів для сервісу залізничних квитків.
                Сьогоднішня дата: %s.
                Доступні міста в базі: %s.
                Перетвори запит користувача у JSON-об'єкт з такими полями (якщо параметр не вказано — став null):
                {
                  "fromCity": "місто відправлення в називному відмінку українською (наприклад, 'Київ') або null",
                  "toCity": "місто прибуття в називному відмінку українською (наприклад, 'Львів') або null",
                  "city": "місто (якщо не зрозуміло звідки чи куди) в називному відмінку або null",
                  "search": "номер потяга або ключове слово (якщо є) або null",
                  "date": "дата у форматі YYYY-MM-DD або null",
                  "maxPrice": число (максимальна ціна в грн) або null,
                  "wifiOnly": true (якщо користувач просить з Wi-Fi/вайфаєм) або null
                }
                Поверни ТІЛЬКИ валідний JSON без markdown-блоків.
                """.formatted(LocalDate.now(), trainService.findAllCities());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", aiModel);
        payload.put("temperature", 0.1);
        payload.put("response_format", Map.of("type", "json_object"));
        payload.put("messages", List.of(
                Map.of("role", "system", "content", extractionSystemPrompt),
                Map.of("role", "user", "content", requestDto.query().trim())
        ));

        JsonNode responseNode = callChatCompletions(payload);
        String content = extractMessageContent(responseNode);

        String fromCity = null;
        String toCity = null;
        TrainFilter filter;

        try {
            JsonNode json = objectMapper.readTree(cleanJsonContent(content));
            fromCity = toolSupport.normalizeCityName(toolSupport.getTextOrNull(json, "fromCity"));
            toCity = toolSupport.normalizeCityName(toolSupport.getTextOrNull(json, "toCity"));
            String city = toolSupport.normalizeCityName(toolSupport.getTextOrNull(json, "city"));
            String search = toolSupport.getTextOrNull(json, "search");
            LocalDate date = toolSupport.parseLocalDateOrNull(toolSupport.getTextOrNull(json, "date"));
            BigDecimal maxPrice = toolSupport.getDecimalOrNull(json, "maxPrice");
            Boolean wifiOnly = toolSupport.getBooleanOrNull(json, "wifiOnly");

            if (!StringUtils.hasText(city)) {
                city = StringUtils.hasText(toCity) ? toCity : fromCity;
            }

            filter = TrainFilter.builder()
                    .search(search)
                    .city(city)
                    .date(date)
                    .maxPrice(maxPrice)
                    .wifiOnly(wifiOnly)
                    .build();
        } catch (Exception e) {
            log.warn("Failed to parse smart-search JSON from LLM ({}), falling back to text search", e.getMessage());
            filter = TrainFilter.builder().search(requestDto.query().trim()).build();
        }

        List<Train> matched = toolSupport.searchTrainsWithEndpoints(filter, fromCity, toCity);

        return AiSmartSearchResponseDto.builder()
                .appliedFilter(filter)
                .trains(trainMapper.toDtoList(matched))
                .build();
    }

    private String executeConversationWithTools(
            List<Map<String, Object>> messages,
            AiToolContext toolContext,
            Set<String> usedTools) {

        List<Map<String, Object>> tools = toolStrategies.values().stream()
                .map(AiTool::getDefinition)
                .toList();

        for (int iteration = 0; iteration < MAX_TOOL_ITERATIONS; iteration++) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", aiModel);
            payload.put("temperature", 0.3);
            payload.put("messages", messages);
            payload.put("tools", tools);
            payload.put("tool_choice", "auto");

            JsonNode responseNode;
            try {
                responseNode = callChatCompletions(payload);
            } catch (AiToolUseFailedException ex) {
                log.warn("Groq tool_use_failed occurred, executing fallback completion with inline train catalog");
                return executeFallbackWithInlineCatalog(messages, toolContext, usedTools);
            }

            JsonNode messageNode = responseNode.path("choices").path(0).path("message");
            JsonNode toolCallsNode = messageNode.path("tool_calls");

            if (toolCallsNode.isMissingNode() || !toolCallsNode.isArray() || toolCallsNode.isEmpty()) {
                String content = messageNode.path("content").asText("");
                return StringUtils.hasText(content)
                        ? content.trim()
                        : "Вибачте, не вдалося сформувати відповідь. Спробуйте уточнити ваш запит.";
            }

            Map<String, Object> assistantToolCallMsg = new LinkedHashMap<>();
            assistantToolCallMsg.put("role", "assistant");
            if (!messageNode.path("content").isNull() && StringUtils.hasText(messageNode.path("content").asText())) {
                assistantToolCallMsg.put("content", messageNode.path("content").asText());
            } else {
                assistantToolCallMsg.put("content", null);
            }
            assistantToolCallMsg.put("tool_calls", objectMapper.convertValue(toolCallsNode, List.class));
            messages.add(assistantToolCallMsg);

            for (JsonNode toolCall : toolCallsNode) {
                String toolCallId = toolCall.path("id").asText();
                String functionName = toolCall.path("function").path("name").asText();
                String argumentsJson = toolCall.path("function").path("arguments").asText("{}");

                usedTools.add(functionName);
                String toolResultJson = executeTool(functionName, argumentsJson, toolContext);

                Map<String, Object> toolResponseMsg = new LinkedHashMap<>();
                toolResponseMsg.put("role", "tool");
                toolResponseMsg.put("tool_call_id", toolCallId);
                toolResponseMsg.put("name", functionName);
                toolResponseMsg.put("content", toolResultJson);
                messages.add(toolResponseMsg);
            }
        }

        Map<String, Object> finalPayload = new LinkedHashMap<>();
        finalPayload.put("model", aiModel);
        finalPayload.put("temperature", 0.3);
        finalPayload.put("messages", messages);

        JsonNode finalResponse = callChatCompletions(finalPayload);
        String finalContent = extractMessageContent(finalResponse);
        return StringUtils.hasText(finalContent)
                ? finalContent.trim()
                : "Ось знайдені варіанти за вашим запитом.";
    }

    private String executeTool(
            String functionName,
            String argumentsJson,
            AiToolContext toolContext) {

        log.info("Executing AI tool '{}' with args: {}", functionName, argumentsJson);

        AiTool strategy = toolStrategies.get(functionName);
        if (strategy == null) {
            log.warn("Requested unknown AI tool strategy: {}", functionName);
            return toJsonSafe(Map.of("error", "Unknown tool: " + functionName));
        }

        try {
            JsonNode args = StringUtils.hasText(argumentsJson)
                    ? objectMapper.readTree(argumentsJson)
                    : objectMapper.createObjectNode();

            Object result = strategy.execute(args, toolContext);
            return toJsonSafe(result);
        } catch (Exception e) {
            log.warn("Error executing tool {}: {}", functionName, e.getMessage());
            return toJsonSafe(Map.of("error", e.getMessage() != null ? e.getMessage() : "Tool execution failed"));
        }
    }

    private String executeFallbackWithInlineCatalog(
            List<Map<String, Object>> originalMessages,
            AiToolContext toolContext,
            Set<String> usedTools) {

        List<Train> allTrains = trainService.findTrains(new TrainFilter());
        usedTools.add("search_trains");

        String userQueryLower = "";
        for (int i = originalMessages.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = originalMessages.get(i);
            if ("user".equals(msg.get("role")) && msg.get("content") != null) {
                userQueryLower = msg.get("content").toString().toLowerCase(Locale.ROOT);
                break;
            }
        }

        for (Train train : allTrains) {
            if (matchesQueryKeywords(train, userQueryLower)) {
                toolContext.addSuggestedTrain(train);
            }
        }

        String catalogJson = toJsonSafe(allTrains.stream().map(toolSupport::toCompactTrainMap).toList());

        List<Map<String, Object>> fallbackMessages = new ArrayList<>();
        fallbackMessages.add(Map.of(
                "role", "system",
                "content", buildSystemPrompt() + "\n\nАктуальний каталог потягів у базі даних:\n" + catalogJson
        ));

        for (Map<String, Object> msg : originalMessages) {
            Object role = msg.get("role");
            if (("user".equals(role) || "assistant".equals(role)) && msg.get("content") != null && !msg.containsKey("tool_calls")) {
                fallbackMessages.add(Map.of("role", role, "content", msg.get("content")));
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", aiModel);
        payload.put("temperature", 0.3);
        payload.put("messages", fallbackMessages);

        JsonNode responseNode = callChatCompletions(payload);
        return extractMessageContent(responseNode);
    }

    private boolean matchesQueryKeywords(Train train, String queryLower) {
        if (!StringUtils.hasText(queryLower)) {
            return false;
        }
        String fromPrefix = train.getFromCity() != null && train.getFromCity().length() >= 4
                ? train.getFromCity().substring(0, 4).toLowerCase(Locale.ROOT)
                : "";
        String toPrefix = train.getToCity() != null && train.getToCity().length() >= 4
                ? train.getToCity().substring(0, 4).toLowerCase(Locale.ROOT)
                : "";
        return (!fromPrefix.isEmpty() && queryLower.contains(fromPrefix))
                || (!toPrefix.isEmpty() && queryLower.contains(toPrefix))
                || (train.getNumber() != null && queryLower.contains(train.getNumber().toLowerCase(Locale.ROOT)));
    }

    private JsonNode callChatCompletions(Map<String, Object> payload) {
        try {
            String responseBody = restClient.post()
                    .uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + aiApiKey.trim())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(String.class);

            if (!StringUtils.hasText(responseBody)) {
                throw new AiServiceException(HttpStatus.BAD_GATEWAY, "Empty response from AI provider");
            }
            return objectMapper.readTree(responseBody);
        } catch (HttpStatusCodeException ex) {
            int statusCode = ex.getStatusCode().value();
            String errorBody = ex.getResponseBodyAsString();

            if (statusCode == 400 && errorBody != null && errorBody.contains("tool_use_failed")) {
                throw new AiToolUseFailedException(errorBody);
            }
            if (statusCode == 429) {
                log.warn("AI provider rate limit exceeded (429): {}", errorBody);
                throw new AiServiceException(HttpStatus.TOO_MANY_REQUESTS,
                        "Ліміт запитів до AI-сервісу тимчасово вичерпано. Спробуйте через хвилину.");
            }
            if (statusCode == 401 || statusCode == 403) {
                log.error("AI provider authentication failed ({}): check AI_API_KEY", statusCode);
                throw new AiServiceException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Помилка авторизації AI-провайдера. Перевірте конфігурацію AI_API_KEY.");
            }

            log.error("AI provider HTTP error {}: {}", statusCode, errorBody);
            throw new AiServiceException(HttpStatus.SERVICE_UNAVAILABLE,
                    "AI-сервіс тимчасово недоступний (HTTP " + statusCode + "). Спробуйте пізніше.");
        } catch (ResourceAccessException ex) {
            log.error("AI provider timeout or network error: {}", ex.getMessage());
            throw new AiServiceException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Час очікування відповіді від AI-сервісу вичерпано. Спробуйте ще раз.", ex);
        } catch (AiServiceException | AiToolUseFailedException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Unexpected error calling AI provider", ex);
            throw new AiServiceException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Сталася помилка під час звернення до AI-асистента.", ex);
        }
    }

    private void ensureApiKeyConfigured() {
        if (!StringUtils.hasText(aiApiKey)) {
            throw new AiServiceException(HttpStatus.SERVICE_UNAVAILABLE,
                    "AI_API_KEY не налаштовано на сервері. Додайте ключ Groq у змінні середовища.");
        }
    }

    private String buildSystemPrompt() {
        return systemPromptTemplate.replace("{{CURRENT_DATE}}", LocalDate.now().toString());
    }

    private String loadSystemPromptTemplate() {
        try {
            ClassPathResource resource = new ClassPathResource("prompts/assistant-system.txt");
            if (resource.exists()) {
                return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.warn("Could not read prompts/assistant-system.txt: {}", e.getMessage());
        }
        return "Ти — AI-асистент сервісу залізничних квитків My Train App. Сьогоднішня дата: {{CURRENT_DATE}}. "
                + "Використовуй інструменти для пошуку потягів та міст і не вигадуй дані.";
    }

    private List<HistoryEntry> loadHistory(String sessionId) {
        try {
            String json = redisTemplate.opsForValue().get(CHAT_CACHE_PREFIX + sessionId);
            if (StringUtils.hasText(json)) {
                return new ArrayList<>(objectMapper.readValue(json, new TypeReference<List<HistoryEntry>>() {}));
            }
        } catch (Exception e) {
            log.warn("Failed to load AI chat history from Redis for session {}: {}", sessionId, e.getMessage());
        }
        return new ArrayList<>();
    }

    private void saveHistory(String sessionId, List<HistoryEntry> history) {
        try {
            int maxEntries = Math.max(2, maxHistoryMessages);
            List<HistoryEntry> trimmed = history.size() > maxEntries
                    ? history.subList(history.size() - maxEntries, history.size())
                    : history;
            String json = objectMapper.writeValueAsString(trimmed);
            redisTemplate.opsForValue().set(
                    CHAT_CACHE_PREFIX + sessionId,
                    json,
                    Duration.ofMinutes(Math.max(1, historyTtlMinutes))
            );
        } catch (Exception e) {
            log.warn("Failed to save AI chat history to Redis for session {}: {}", sessionId, e.getMessage());
        }
    }

    private String extractMessageContent(JsonNode responseNode) {
        return responseNode.path("choices").path(0).path("message").path("content").asText("");
    }

    private String cleanJsonContent(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "{}";
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                return trimmed.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return trimmed;
    }

    private String toJsonSafe(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{\"error\":\"Serialization failed\"}";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record HistoryEntry(String role, String content) {
    }

    private static class AiToolUseFailedException extends RuntimeException {
        AiToolUseFailedException(String message) {
            super(message);
        }
    }
}
