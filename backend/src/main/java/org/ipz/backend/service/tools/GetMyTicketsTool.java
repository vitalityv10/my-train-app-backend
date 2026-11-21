package org.ipz.backend.service.tools;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.ipz.backend.model.Ticket;
import org.ipz.backend.service.UserService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component("get_my_tickets")
@RequiredArgsConstructor
public class GetMyTicketsTool implements AiTool {

    private final UserService userService;

    @Override
    public Map<String, Object> getDefinition() {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", "get_my_tickets",
                        "description", "Повертає список придбаних та заброньованих квитків поточного авторизованого користувача.",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of()
                        )
                )
        );
    }

    @Override
    public Object execute(JsonNode args, AiToolContext context) {
        if (!context.isAuthenticated()) {
            return Map.of(
                    "authenticated", false,
                    "message", "Користувач не авторизований. Попросіть його увійти в акаунт, щоб переглянути придбані квитки."
            );
        }

        List<Ticket> tickets = userService.getUserTickets(context.getFirebaseUid());
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

        return Map.of(
                "authenticated", true,
                "count", ticketSummaries.size(),
                "tickets", ticketSummaries
        );
    }
}
