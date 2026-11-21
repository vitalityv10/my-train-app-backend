package org.ipz.backend.service.tools;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.ipz.backend.model.Train;
import org.ipz.backend.model.dto.TrainFilter;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component("search_trains")
@RequiredArgsConstructor
public class SearchTrainsTool implements AiTool {

    private final AiToolSupport toolSupport;

    @Override
    public Map<String, Object> getDefinition() {
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

        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", "search_trains",
                        "description", "Шукає потяги у базі даних за містом відправлення/прибуття, датою, максимальною ціною, наявністю Wi-Fi або номером потяга.",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", searchProps
                        )
                )
        );
    }

    @Override
    public Object execute(JsonNode args, AiToolContext context) {
        String fromCity = toolSupport.normalizeCityName(toolSupport.getTextOrNull(args, "fromCity"));
        String toCity = toolSupport.normalizeCityName(toolSupport.getTextOrNull(args, "toCity"));
        String city = toolSupport.normalizeCityName(toolSupport.getTextOrNull(args, "city"));
        String search = toolSupport.getTextOrNull(args, "search");
        LocalDate date = toolSupport.parseLocalDateOrNull(toolSupport.getTextOrNull(args, "date"));
        BigDecimal maxPrice = toolSupport.getDecimalOrNull(args, "maxPrice");
        Boolean wifiOnly = toolSupport.getBooleanOrNull(args, "wifiOnly");

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

        List<Train> trains = toolSupport.searchTrainsWithEndpoints(filter, fromCity, toCity);
        boolean dateFallbackApplied = false;

        if (trains.isEmpty() && date != null) {
            TrainFilter withoutDate = TrainFilter.builder()
                    .search(search)
                    .city(city)
                    .maxPrice(maxPrice)
                    .wifiOnly(wifiOnly)
                    .build();
            trains = toolSupport.searchTrainsWithEndpoints(withoutDate, fromCity, toCity);
            dateFallbackApplied = !trains.isEmpty();
        }

        trains.forEach(context::addSuggestedTrain);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", trains.size());
        if (dateFallbackApplied) {
            result.put("note", "На точну дату рейсів не знайдено, показано регулярні рейси за цим маршрутом у розкладі.");
        }
        result.put("trains", trains.stream().map(toolSupport::toCompactTrainMap).toList());
        return result;
    }
}
