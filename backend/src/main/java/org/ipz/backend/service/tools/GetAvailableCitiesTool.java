package org.ipz.backend.service.tools;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.ipz.backend.service.TrainService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component("get_available_cities")
@RequiredArgsConstructor
public class GetAvailableCitiesTool implements AiTool {

    private final TrainService trainService;

    @Override
    public Map<String, Object> getDefinition() {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", "get_available_cities",
                        "description", "Повертає список усіх міст відправлення та прибуття, які є в розкладі потягів.",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of()
                        )
                )
        );
    }

    @Override
    public Object execute(JsonNode args, AiToolContext context) {
        List<String> cities = trainService.findAllCities();
        return Map.of(
                "count", cities.size(),
                "cities", cities
        );
    }
}
