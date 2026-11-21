package org.ipz.backend.service.tools;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.ipz.backend.exception.NotFoundException;
import org.ipz.backend.model.Train;
import org.ipz.backend.model.TrainOccupancy;
import org.ipz.backend.service.OccupancyService;
import org.ipz.backend.service.TrainService;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component("check_seat_occupancy")
@RequiredArgsConstructor
public class CheckSeatOccupancyTool implements AiTool {

    private final TrainService trainService;
    private final OccupancyService occupancyService;
    private final AiToolSupport toolSupport;

    @Override
    public Map<String, Object> getDefinition() {
        return Map.of(
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
        );
    }

    @Override
    public Object execute(JsonNode args, AiToolContext context) {
        int trainId = args.path("trainId").asInt(-1);
        if (trainId <= 0) {
            return Map.of("error", "Некоректний trainId");
        }

        LocalDate date = toolSupport.parseLocalDateOrNull(toolSupport.getTextOrNull(args, "date"));
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
            return result;
        } catch (NotFoundException e) {
            return Map.of("error", "Потяг з ID " + trainId + " не знайдено");
        }
    }
}
