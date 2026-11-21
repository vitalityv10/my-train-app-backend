package org.ipz.backend.service.tools;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.ipz.backend.exception.NotFoundException;
import org.ipz.backend.model.Train;
import org.ipz.backend.service.TrainService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component("get_train_details")
@RequiredArgsConstructor
public class GetTrainDetailsTool implements AiTool {

    private final TrainService trainService;
    private final AiToolSupport toolSupport;

    @Override
    public Map<String, Object> getDefinition() {
        return Map.of(
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
        );
    }

    @Override
    public Object execute(JsonNode args, AiToolContext context) {
        int trainId = args.path("trainId").asInt(-1);
        if (trainId <= 0) {
            return Map.of("error", "Некоректний trainId");
        }
        try {
            Train train = trainService.findById(trainId);
            context.addSuggestedTrain(train);
            return toolSupport.toCompactTrainMap(train);
        } catch (NotFoundException e) {
            return Map.of("error", "Потяг з ID " + trainId + " не знайдено");
        }
    }
}
