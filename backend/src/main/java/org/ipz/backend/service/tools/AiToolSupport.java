package org.ipz.backend.service.tools;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.ipz.backend.exception.NotFoundException;
import org.ipz.backend.model.Cart;
import org.ipz.backend.model.CartItem;
import org.ipz.backend.model.Train;
import org.ipz.backend.model.dto.TrainFilter;
import org.ipz.backend.service.TrainService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class AiToolSupport {

    private final TrainService trainService;

    public Map<String, Object> toCompactTrainMap(Train t) {
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

    public Map<String, Object> buildCartSummaryMap(Cart cart) {
        List<CartItem> items = cart != null && cart.getItems() != null ? cart.getItems() : List.of();
        int totalTickets = 0;
        BigDecimal totalPrice = BigDecimal.ZERO;
        List<Map<String, Object>> itemMaps = new ArrayList<>();

        for (CartItem item : items) {
            int qty = item.getQuantity() != null ? item.getQuantity() : 1;
            BigDecimal price = item.getBasePrice() != null ? item.getBasePrice() : BigDecimal.ZERO;
            totalTickets += qty;
            totalPrice = totalPrice.add(price.multiply(BigDecimal.valueOf(qty)));

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("trainId", item.getId());
            m.put("number", item.getNumber());
            m.put("title", item.getTitle());
            m.put("fromCity", item.getFromCity());
            m.put("toCity", item.getToCity());
            m.put("departureTime", item.getDepartureTime() != null ? item.getDepartureTime().toString() : null);
            m.put("basePrice", price);
            m.put("quantity", qty);
            itemMaps.add(m);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("distinctTrainsCount", items.size());
        summary.put("totalTicketsCount", totalTickets);
        summary.put("totalPrice", totalPrice);
        summary.put("items", itemMaps);
        return summary;
    }

    public Train resolveTrainFromArgs(JsonNode args) {
        int trainId = args.path("trainId").asInt(-1);
        if (trainId > 0) {
            try {
                return trainService.findById(trainId);
            } catch (NotFoundException ignored) {
            }
        }
        String trainNumber = getTextOrNull(args, "trainNumber");
        if (StringUtils.hasText(trainNumber)) {
            List<Train> found = trainService.findTrains(TrainFilter.builder().search(trainNumber.trim()).build());
            for (Train t : found) {
                if (t.getNumber() != null && t.getNumber().equalsIgnoreCase(trainNumber.trim())) {
                    return t;
                }
            }
            if (found.size() == 1) {
                return found.get(0);
            }
        }
        return null;
    }

    public List<Train> searchTrainsWithEndpoints(TrainFilter filter, String fromCity, String toCity) {
        List<Train> initial = trainService.findTrains(filter);

        return initial.stream()
                .filter(train -> !StringUtils.hasText(fromCity) || cityMatches(train.getFromCity(), fromCity))
                .filter(train -> !StringUtils.hasText(toCity) || cityMatches(train.getToCity(), toCity))
                .toList();
    }

    public boolean cityMatches(String actualCity, String requestedCity) {
        if (!StringUtils.hasText(actualCity) || !StringUtils.hasText(requestedCity)) {
            return false;
        }
        String a = actualCity.trim().toLowerCase(Locale.ROOT);
        String r = requestedCity.trim().toLowerCase(Locale.ROOT);
        return a.equals(r) || a.contains(r) || r.contains(a);
    }

    public String normalizeCityName(String rawCity) {
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

    public String getTextOrNull(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        String val = node.get(field).asText("").trim();
        return StringUtils.hasText(val) && !"null".equalsIgnoreCase(val) ? val : null;
    }

    public BigDecimal getDecimalOrNull(JsonNode node, String field) {
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

    public Boolean getBooleanOrNull(JsonNode node, String field) {
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

    public LocalDate parseLocalDateOrNull(String dateStr) {
        if (!StringUtils.hasText(dateStr)) {
            return null;
        }
        try {
            return LocalDate.parse(dateStr.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
