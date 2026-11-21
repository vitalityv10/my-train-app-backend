package org.ipz.backend.service.impl;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ipz.backend.controller.mapper.TrainControllerMapper;
import org.ipz.backend.exception.AiServiceException;
import org.ipz.backend.exception.NotFoundException;
import org.ipz.backend.model.Ticket;
import org.ipz.backend.model.Train;
import org.ipz.backend.model.TrainOccupancy;
import org.ipz.backend.model.dto.AiChatRequestDto;
import org.ipz.backend.model.dto.AiChatResponseDto;
import org.ipz.backend.model.dto.AiSmartSearchRequestDto;
import org.ipz.backend.model.dto.AiSmartSearchResponseDto;
import org.ipz.backend.model.dto.TrainFilter;
import org.ipz.backend.service.AiAssistantService;
import org.ipz.backend.service.OccupancyService;
import org.ipz.backend.service.TrainService;
import org.ipz.backend.service.UserService;
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
import java.time.format.DateTimeParseException;
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
    private static final int MAX_TOOL_ITERATIONS = 3;
    private static final int MAX_SUGGESTED_TRAINS = 6;

    private final TrainService trainService;
    private final OccupancyService occupancyService;
    private final UserService userService;
    private final TrainControllerMapper trainMapper;
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
    public AiChatResponseDto chat(AiChatRequestDto requestDto, String firebaseUid) {
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

        Map<Integer, Train> collectedTrains = new LinkedHashMap<>();
        Set<String> usedTools = new LinkedHashSet<>();

        String assistantReply = executeConversationWithTools(messages, firebaseUid, collectedTrains, usedTools);

        history.add(new HistoryEntry("user", userMessage));
        history.add(new HistoryEntry("assistant", assistantReply));
        saveHistory(sessionId, history);

        long elapsedMs = System.currentTimeMillis() - startTime;
        log.info("AI chat completed in {} ms (sessionId={}, tools={}, suggestedTrains={})",
                elapsedMs, sessionId, usedTools, collectedTrains.size());

        return AiChatResponseDto.builder()
                .sessionId(sessionId)
                .reply(assistantReply)
                .suggestedTrains(trainMapper.toDtoList(new ArrayList<>(collectedTrains.values())))
                .usedTools(new ArrayList<>(usedTools))
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
        TrainFilter filter = new TrainFilter();

        try {
            JsonNode json = objectMapper.readTree(cleanJsonContent(content));
            fromCity = normalizeCityName(getTextOrNull(json, "fromCity"));
            toCity = normalizeCityName(getTextOrNull(json, "toCity"));
            String city = normalizeCityName(getTextOrNull(json, "city"));
            String search = getTextOrNull(json, "search");
            LocalDate date = parseLocalDateOrNull(getTextOrNull(json, "date"));
            BigDecimal maxPrice = getDecimalOrNull(json, "maxPrice");
            Boolean wifiOnly = getBooleanOrNull(json, "wifiOnly");

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

        List<Train> matched = searchTrainsWithEndpoints(filter, fromCity, toCity);

        return AiSmartSearchResponseDto.builder()
                .appliedFilter(filter)
                .trains(trainMapper.toDtoList(matched))
                .build();
    }

    private String executeConversationWithTools(
            List<Map<String, Object>> messages,
            String firebaseUid,
            Map<Integer, Train> collectedTrains,
            Set<String> usedTools) {

        List<Map<String, Object>> tools = buildToolDefinitions();

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
                return executeFallbackWithInlineCatalog(messages, collectedTrains, usedTools);
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
                String toolResultJson = executeTool(functionName, argumentsJson, firebaseUid, collectedTrains);

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

    private String executeFallbackWithInlineCatalog(
            List<Map<String, Object>> originalMessages,
            Map<Integer, Train> collectedTrains,
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
            if (matchesQueryKeywords(train, userQueryLower) && collectedTrains.size() < MAX_SUGGESTED_TRAINS) {
                collectedTrains.put(train.getId(), train);
            }
        }

        String catalogJson = toJsonSafe(allTrains.stream().map(this::toCompactTrainMap).toList());

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

    private String executeTool(
            String functionName,
            String argumentsJson,
            String firebaseUid,
            Map<Integer, Train> collectedTrains) {

        log.info("Executing AI tool '{}' with args: {}", functionName, argumentsJson);

        try {
            JsonNode args = StringUtils.hasText(argumentsJson)
                    ? objectMapper.readTree(argumentsJson)
                    : objectMapper.createObjectNode();

            return switch (functionName) {
                case "get_available_cities" -> handleGetAvailableCities();
                case "search_trains" -> handleSearchTrains(args, collectedTrains);
                case "get_train_details" -> handleGetTrainDetails(args, collectedTrains);
                case "check_seat_occupancy" -> handleCheckSeatOccupancy(args);
                case "get_my_tickets" -> handleGetMyTickets(firebaseUid);
                default -> toJsonSafe(Map.of("error", "Unknown tool: " + functionName));
            };
        } catch (Exception e) {
            log.warn("Error executing tool {}: {}", functionName, e.getMessage());
            return toJsonSafe(Map.of("error", e.getMessage() != null ? e.getMessage() : "Tool execution failed"));
        }
    }

    private String handleGetAvailableCities() {
        List<String> cities = trainService.findAllCities();
        return toJsonSafe(Map.of(
                "count", cities.size(),
                "cities", cities
        ));
    }

    private String handleSearchTrains(JsonNode args, Map<Integer, Train> collectedTrains) {
        String fromCity = normalizeCityName(getTextOrNull(args, "fromCity"));
        String toCity = normalizeCityName(getTextOrNull(args, "toCity"));
        String city = normalizeCityName(getTextOrNull(args, "city"));
        String search = getTextOrNull(args, "search");
        LocalDate date = parseLocalDateOrNull(getTextOrNull(args, "date"));
        BigDecimal maxPrice = getDecimalOrNull(args, "maxPrice");
        Boolean wifiOnly = getBooleanOrNull(args, "wifiOnly");

        if (!StringUtils.hasText(city)) {
            city = StringUtils.hasText(toCity) ? toCity : fromCity;
        }

        TrainFilter filter = TrainFilter.builder()
                .search(search)
                .city(city)
                .date(date)
                .maxPrice(maxPrice)
                .wifiOnly(wifiOnly)
                .build();

        List<Train> trains = searchTrainsWithEndpoints(filter, fromCity, toCity);
        boolean dateFallbackApplied = false;

        if (trains.isEmpty() && date != null) {
            TrainFilter withoutDate = TrainFilter.builder()
                    .search(search)
                    .city(city)
                    .maxPrice(maxPrice)
                    .wifiOnly(wifiOnly)
                    .build();
            trains = searchTrainsWithEndpoints(withoutDate, fromCity, toCity);
            dateFallbackApplied = !trains.isEmpty();
        }

        for (Train train : trains) {
            if (collectedTrains.size() < MAX_SUGGESTED_TRAINS) {
                collectedTrains.put(train.getId(), train);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", trains.size());
        if (dateFallbackApplied) {
            result.put("note", "На точну дату рейсів не знайдено, показано регулярні рейси за цим маршрутом у розкладі.");
        }
        result.put("trains", trains.stream().map(this::toCompactTrainMap).toList());
        return toJsonSafe(result);
    }

    private List<Train> searchTrainsWithEndpoints(TrainFilter filter, String fromCity, String toCity) {
        List<Train> initial = trainService.findTrains(filter);

        return initial.stream()
                .filter(train -> !StringUtils.hasText(fromCity) || cityMatches(train.getFromCity(), fromCity))
                .filter(train -> !StringUtils.hasText(toCity) || cityMatches(train.getToCity(), toCity))
                .toList();
    }

    private boolean cityMatches(String actualCity, String requestedCity) {
        if (!StringUtils.hasText(actualCity) || !StringUtils.hasText(requestedCity)) {
            return false;
        }
        String a = actualCity.trim().toLowerCase(Locale.ROOT);
        String r = requestedCity.trim().toLowerCase(Locale.ROOT);
        return a.equals(r) || a.contains(r) || r.contains(a);
    }

    private String normalizeCityName(String rawCity) {
        if (!StringUtils.hasText(rawCity)) {
            return null;
        }
        String trimmed = rawCity.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        List<String> knownCities = trainService.findAllCities();

        for (String known : knownCities) {
            if (known.equalsIgnoreCase(trimmed)) {
                return known;
            }
        }

        for (String known : knownCities) {
            String knownLower = known.toLowerCase(Locale.ROOT);
            int prefixLen = Math.min(4, knownLower.length());
            if (prefixLen >= 3 && lower.startsWith(knownLower.substring(0, prefixLen))) {
                return known;
            }
        }

        if (lower.startsWith("києв") || lower.startsWith("київ")) {
            return "Київ";
        }
        if (lower.startsWith("львов") || lower.startsWith("львів")) {
            return "Львів";
        }
        if (lower.startsWith("одес")) {
            return "Одеса";
        }
        if (lower.startsWith("харков") || lower.startsWith("харків")) {
            return "Харків";
        }
        if (lower.startsWith("дніпр")) {
            return "Дніпро";
        }

        return trimmed;
    }

    private String handleGetTrainDetails(JsonNode args, Map<Integer, Train> collectedTrains) {
        int trainId = args.path("trainId").asInt(-1);
        if (trainId <= 0) {
            return toJsonSafe(Map.of("error", "Некоректний trainId"));
        }
        try {
            Train train = trainService.findById(trainId);
            if (collectedTrains.size() < MAX_SUGGESTED_TRAINS) {
                collectedTrains.put(train.getId(), train);
            }
            return toJsonSafe(toCompactTrainMap(train));
        } catch (NotFoundException e) {
            return toJsonSafe(Map.of("error", "Потяг з ID " + trainId + " не знайдено"));
        }
    }

    private String handleCheckSeatOccupancy(JsonNode args) {
        int trainId = args.path("trainId").asInt(-1);
        if (trainId <= 0) {
            return toJsonSafe(Map.of("error", "Некоректний trainId"));
        }

        LocalDate date = parseLocalDateOrNull(getTextOrNull(args, "date"));
        if (date == null) {
            try {
                Train train = trainService.findById(trainId);
                date = train.getDepartureTime() != null
                        ? train.getDepartureTime().toLocalDate()
                        : LocalDate.now();
            } catch (Exception ignored) {
                date = LocalDate.now();
            }
        }

        try {
            TrainOccupancy occupancy = occupancyService.getTrainOccupancy(trainId, date);
            int totalSeats = occupancy.getTotalCarriages() * occupancy.getSeatsPerCarriage();
            int occupiedCount = occupancy.getOccupiedSeats() != null ? occupancy.getOccupiedSeats().size() : 0;
            int freeSeats = Math.max(0, totalSeats - occupiedCount);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("trainId", trainId);
            result.put("date", date.toString());
            result.put("totalCarriages", occupancy.getTotalCarriages());
            result.put("seatsPerCarriage", occupancy.getSeatsPerCarriage());
            result.put("totalSeats", totalSeats);
            result.put("occupiedSeatsCount", occupiedCount);
            result.put("freeSeatsCount", freeSeats);
            result.put("occupiedSeats", occupancy.getOccupiedSeats());
            return toJsonSafe(result);
        } catch (NotFoundException e) {
            return toJsonSafe(Map.of("error", "Потяг з ID " + trainId + " не знайдено"));
        }
    }

    private String handleGetMyTickets(String firebaseUid) {
        if (!StringUtils.hasText(firebaseUid)) {
            return toJsonSafe(Map.of(
                    "authenticated", false,
                    "message", "Користувач не авторизований. Попросіть його увійти в акаунт, щоб переглянути придбані квитки."
            ));
        }

        List<Ticket> tickets = userService.getUserTickets(firebaseUid);
        List<Map<String, Object>> ticketSummaries = tickets.stream().map(t -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("ticketId", t.getTicketId());
            map.put("trainNumber", t.getTrainNumber());
            map.put("trainTitle", t.getTrainTitle());
            map.put("fromCity", t.getFromCity());
            map.put("toCity", t.getToCity());
            map.put("departureDate", t.getDepartureDate() != null ? t.getDepartureDate().toString() : null);
            map.put("carriage", t.getCarriage());
            map.put("seatNumber", t.getSeatNumber());
            map.put("seatClass", t.getSeatClass());
            map.put("price", t.getPrice());
            map.put("paymentStatus", t.getPaymentStatus());
            return map;
        }).toList();

        return toJsonSafe(Map.of(
                "authenticated", true,
                "count", ticketSummaries.size(),
                "tickets", ticketSummaries
        ));
    }

    private Map<String, Object> toCompactTrainMap(Train t) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", t.getId());
        map.put("number", t.getNumber());
        map.put("title", t.getTitle());
        map.put("type", t.getType() != null ? t.getType().getValue() : null);
        map.put("fromCity", t.getFromCity());
        map.put("fromStation", t.getFromStation());
        map.put("departureTime", t.getDepartureTime() != null ? t.getDepartureTime().toString() : null);
        map.put("toCity", t.getToCity());
        map.put("toStation", t.getToStation());
        map.put("arrivalTime", t.getArrivalTime() != null ? t.getArrivalTime().toString() : null);
        map.put("duration", t.getDuration());
        map.put("seatClass", t.getSeatClass());
        map.put("basePrice", t.getBasePrice());
        map.put("hasWifi", t.getHasWifi());
        map.put("hasAirConditioning", t.getHasAirConditioning());
        return map;
    }

    private List<Map<String, Object>> buildToolDefinitions() {
        List<Map<String, Object>> tools = new ArrayList<>();

        tools.add(Map.of(
                "type", "function",
                "function", Map.of(
                        "name", "get_available_cities",
                        "description", "Повертає список усіх міст відправлення та прибуття, які є в розкладі потягів.",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of()
                        )
                )
        ));

        Map<String, Object> searchProps = new LinkedHashMap<>();
        searchProps.put("fromCity", Map.of(
                "type", "string",
                "description", "Місто відправлення українською в називному відмінку (наприклад: 'Київ', 'Львів', 'Харків')"
        ));
        searchProps.put("toCity", Map.of(
                "type", "string",
                "description", "Місто прибуття українською в називному відмінку (наприклад: 'Львів', 'Одеса', 'Дніпро', 'Ужгород', 'Чернівці', 'Івано-Франківськ', 'Запоріжжя')"
        ));
        searchProps.put("city", Map.of(
                "type", "string",
                "description", "Місто відправлення або прибуття українською в називному відмінку, якщо напрямок не уточнено"
        ));
        searchProps.put("search", Map.of(
                "type", "string",
                "description", "Пошуковий рядок за номером потяга (наприклад '715К') або назвою"
        ));
        searchProps.put("date", Map.of(
                "type", "string",
                "description", "Дата відправлення у форматі YYYY-MM-DD"
        ));
        searchProps.put("maxPrice", Map.of(
                "type", "number",
                "description", "Максимальна ціна квитка у гривнях"
        ));
        searchProps.put("wifiOnly", Map.of(
                "type", "boolean",
                "description", "Встановити true, якщо потрібні лише потяги з Wi-Fi"
        ));

        tools.add(Map.of(
                "type", "function",
                "function", Map.of(
                        "name", "search_trains",
                        "description", "Шукає потяги у базі даних за містом відправлення/прибуття, датою, максимальною ціною, наявністю Wi-Fi або номером потяга.",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", searchProps
                        )
                )
        ));

        tools.add(Map.of(
                "type", "function",
                "function", Map.of(
                        "name", "get_train_details",
                        "description", "Повертає детальну інформацію про конкретний потяг за його числовим ID.",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "trainId", Map.of("type", "integer", "description", "Унікальний числовий ID потяга")
                                ),
                                "required", List.of("trainId")
                        )
                )
        ));

        tools.add(Map.of(
                "type", "function",
                "function", Map.of(
                        "name", "check_seat_occupancy",
                        "description", "Перевіряє кількість вільних та зайнятих місць на конкретний потяг і дату.",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "trainId", Map.of("type", "integer", "description", "Унікальний числовий ID потяга"),
                                        "date", Map.of("type", "string", "description", "Дата відправлення YYYY-MM-DD (опційно)")
                                ),
                                "required", List.of("trainId")
                        )
                )
        ));

        tools.add(Map.of(
                "type", "function",
                "function", Map.of(
                        "name", "get_my_tickets",
                        "description", "Повертає список придбаних квитків поточного авторизованого користувача.",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of()
                        )
                )
        ));

        return tools;
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

    private String getTextOrNull(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        String val = node.get(field).asText("").trim();
        return StringUtils.hasText(val) && !"null".equalsIgnoreCase(val) ? val : null;
    }

    private BigDecimal getDecimalOrNull(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        JsonNode val = node.get(field);
        if (val.isNumber()) {
            return val.decimalValue();
        }
        try {
            String text = val.asText("").trim();
            return StringUtils.hasText(text) ? new BigDecimal(text) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Boolean getBooleanOrNull(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        JsonNode val = node.get(field);
        if (val.isBoolean()) {
            return val.booleanValue();
        }
        String text = val.asText("").trim();
        if ("true".equalsIgnoreCase(text)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(text)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private LocalDate parseLocalDateOrNull(String dateStr) {
        if (!StringUtils.hasText(dateStr)) {
            return null;
        }
        try {
            return LocalDate.parse(dateStr.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
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
