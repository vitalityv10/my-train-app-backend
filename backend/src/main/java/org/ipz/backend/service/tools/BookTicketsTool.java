package org.ipz.backend.service.tools;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.ipz.backend.exception.NotFoundException;
import org.ipz.backend.exception.SeatOccupiedException;
import org.ipz.backend.model.Cart;
import org.ipz.backend.model.CartItem;
import org.ipz.backend.model.CheckoutPurchase;
import org.ipz.backend.model.Ticket;
import org.ipz.backend.model.Train;
import org.ipz.backend.model.TrainOccupancy;
import org.ipz.backend.model.enums.PaymentMethod;
import org.ipz.backend.service.CartService;
import org.ipz.backend.service.CheckoutService;
import org.ipz.backend.service.OccupancyService;
import org.ipz.backend.service.TrainService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component("book_tickets")
@RequiredArgsConstructor
public class BookTicketsTool implements AiTool {

    private final TrainService trainService;
    private final OccupancyService occupancyService;
    private final CartService cartService;
    private final CheckoutService checkoutService;
    private final AiToolSupport toolSupport;

    @Override
    public Map<String, Object> getDefinition() {
        Map<String, Object> bookProps = new LinkedHashMap<>();
        bookProps.put("trainId", Map.of(
                "type", "integer",
                "description", "Числовий ID потяга для бронювання (не обов'язково, якщо fromCart=true або вказано trainNumber)"
        ));
        bookProps.put("trainNumber", Map.of(
                "type", "string",
                "description", "Номер потяга (наприклад '705К'), якщо бронюється конкретний потяг за номером"
        ));
        bookProps.put("departureDate", Map.of(
                "type", "string",
                "description", "Дата відправлення у форматі YYYY-MM-DD (якщо не вказано, береться дата рейсу)"
        ));
        bookProps.put("carriage", Map.of(
                "type", "integer",
                "description", "Номер вагона (якщо користувач не вказав, система автоматично підбере вільний вагон)"
        ));
        bookProps.put("seatNumber", Map.of(
                "type", "integer",
                "description", "Номер місця (якщо користувач не вказав, система автоматично підбере вільне місце)"
        ));
        bookProps.put("quantity", Map.of(
                "type", "integer",
                "description", "Кількість квитків для бронювання (за замовчуванням 1)"
        ));
        bookProps.put("contactPhone", Map.of(
                "type", "string",
                "description", "Контактний номер телефону пасажира (опційно)"
        ));
        bookProps.put("fromCart", Map.of(
                "type", "boolean",
                "description", "Встановити true, якщо користувач просить оформити замовлення на всі квитки, що вже лежать у його кошику"
        ));

        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", "book_tickets",
                        "description", "Оформлює замовлення на квитки виключно через бронювання (оплата готівкою/в касі при посадці, статус Pending, без введення даних банківської картки). Може забронювати конкретний потяг або все, що є в кошику користувача.",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", bookProps
                        )
                )
        );
    }

    @Override
    public Object execute(JsonNode args, AiToolContext context) {
        if (!context.isAuthenticated()) {
            return Map.of(
                    "authenticated", false,
                    "message", "Користувач не авторизований. Попросіть його увійти в акаунт, щоб оформити бронювання квитків."
            );
        }

        Boolean fromCart = toolSupport.getBooleanOrNull(args, "fromCart");
        int trainIdArg = args.path("trainId").asInt(-1);
        String trainNumberArg = toolSupport.getTextOrNull(args, "trainNumber");
        String contactPhone = toolSupport.getTextOrNull(args, "contactPhone");

        boolean shouldBookFromCart = Boolean.TRUE.equals(fromCart)
                || (trainIdArg <= 0 && !StringUtils.hasText(trainNumberArg));

        List<CheckoutPurchase.SeatBookingItem> bookingItems = new ArrayList<>();
        Map<String, Set<String>> reservedInBatch = new LinkedHashMap<>();

        if (shouldBookFromCart) {
            Cart cart = cartService.getCart(context.getFirebaseUid());
            if (cart.getItems() == null || cart.getItems().isEmpty()) {
                return Map.of(
                        "success", false,
                        "error", "Кошик порожній. Спочатку додайте потяг у кошик або вкажіть конкретний потяг для бронювання."
                );
            }

            for (CartItem item : cart.getItems()) {
                Train train;
                try {
                    train = trainService.findById(item.getId());
                } catch (NotFoundException e) {
                    return Map.of("success", false, "error", "Потяг з ID " + item.getId() + " не знайдено");
                }

                LocalDate depDate = item.getDepartureTime() != null
                        ? item.getDepartureTime().toLocalDate()
                        : resolveDefaultDepartureDate(train);
                int qty = item.getQuantity() != null && item.getQuantity() > 0 ? item.getQuantity() : 1;

                List<CheckoutPurchase.SeatBookingItem> allocated = allocateFreeSeats(
                        train, depDate, item.getCarriage(), item.getSeatNumber(), qty, reservedInBatch);
                if (allocated.size() < qty) {
                    return Map.of(
                            "success", false,
                            "error", "Недостатньо вільних місць на потяг " + train.getNumber() + " (" + depDate + "). Доступно: " + allocated.size()
                    );
                }
                bookingItems.addAll(allocated);
                context.addSuggestedTrain(train);
            }
        } else {
            Train train = toolSupport.resolveTrainFromArgs(args);
            if (train == null) {
                return Map.of(
                        "success", false,
                        "error", "Потяг не знайдено. Спочатку знайдіть потяг за допомогою search_trains."
                );
            }

            LocalDate depDate = toolSupport.parseLocalDateOrNull(toolSupport.getTextOrNull(args, "departureDate"));
            if (depDate == null) {
                depDate = toolSupport.parseLocalDateOrNull(toolSupport.getTextOrNull(args, "date"));
            }
            if (depDate == null) {
                depDate = resolveDefaultDepartureDate(train);
            }

            int quantity = args.path("quantity").asInt(1);
            if (quantity < 1) {
                quantity = 1;
            }

            Integer requestedCarriage = args.hasNonNull("carriage") && args.path("carriage").asInt(-1) > 0
                    ? args.path("carriage").asInt()
                    : null;
            Integer requestedSeat = args.hasNonNull("seatNumber") && args.path("seatNumber").asInt(-1) > 0
                    ? args.path("seatNumber").asInt()
                    : null;

            if (quantity == 1 && requestedCarriage != null && requestedSeat != null) {
                bookingItems.add(CheckoutPurchase.SeatBookingItem.builder()
                        .trainId(train.getId())
                        .departureDate(depDate)
                        .carriage(requestedCarriage)
                        .seatNumber(requestedSeat)
                        .build());
            } else {
                List<CheckoutPurchase.SeatBookingItem> allocated = allocateFreeSeats(
                        train, depDate, requestedCarriage, requestedSeat, quantity, reservedInBatch);
                if (allocated.size() < quantity) {
                    return Map.of(
                            "success", false,
                            "error", "Недостатньо вільних місць на потяг " + train.getNumber() + " на дату " + depDate
                    );
                }
                bookingItems.addAll(allocated);
            }

            context.addSuggestedTrain(train);
        }

        CheckoutPurchase purchaseRequest = CheckoutPurchase.builder()
                .paymentMethod(PaymentMethod.CASH)
                .contactPhone(contactPhone)
                .tickets(bookingItems)
                .build();

        try {
            List<Ticket> booked = checkoutService.purchaseTickets(
                    context.getFirebaseUid(),
                    context.getUserEmail(),
                    context.getUserName(),
                    purchaseRequest
            );
            context.getBookedTickets().addAll(booked);
            context.setUpdatedCart(cartService.getCart(context.getFirebaseUid()));

            List<Map<String, Object>> bookedSummaries = booked.stream().map(t -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("ticketId", t.getTicketId());
                map.put("trainId", t.getTrainId());
                map.put("trainNumber", t.getTrainNumber());
                map.put("trainTitle", t.getTrainTitle());
                map.put("fromCity", t.getFromCity());
                map.put("toCity", t.getToCity());
                map.put("departureDate", t.getDepartureDate() != null ? t.getDepartureDate().toString() : null);
                map.put("carriage", t.getCarriage());
                map.put("seatNumber", t.getSeatNumber());
                map.put("seatClass", t.getSeatClass());
                map.put("price", t.getPrice());
                map.put("paymentMethod", t.getPaymentMethod() != null ? t.getPaymentMethod().getValue() : "cash");
                map.put("paymentStatus", t.getPaymentStatus() != null ? t.getPaymentStatus().getValue() : "Pending");
                return map;
            }).toList();

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("bookingMode", "RESERVATION_WITHOUT_CARD");
            response.put("paymentMethod", PaymentMethod.CASH.getValue());
            response.put("paymentStatus", "Pending");
            response.put("message", "Квитки успішно заброньовано без введення даних картки. Оплата здійснюється в касі або провіднику при посадці.");
            response.put("ticketsCount", bookedSummaries.size());
            response.put("tickets", bookedSummaries);
            return response;
        } catch (SeatOccupiedException e) {
            return Map.of(
                    "success", false,
                    "error", e.getMessage(),
                    "conflictingSeats", e.getConflictingSeats()
            );
        } catch (IllegalArgumentException e) {
            return Map.of(
                    "success", false,
                    "error", "Некоректний номер вагона або місця: " + e.getMessage()
            );
        }
    }

    private LocalDate resolveDefaultDepartureDate(Train train) {
        if (train.getDepartureTime() != null) {
            return train.getDepartureTime().toLocalDate();
        }
        return LocalDate.now();
    }

    private List<CheckoutPurchase.SeatBookingItem> allocateFreeSeats(
            Train train,
            LocalDate departureDate,
            Integer preferredCarriage,
            Integer preferredSeat,
            int quantity,
            Map<String, Set<String>> reservedInBatch) {

        TrainOccupancy occupancy = occupancyService.getTrainOccupancy(train.getId(), departureDate);
        int totalCarriages = occupancy.getTotalCarriages() != null && occupancy.getTotalCarriages() > 0
                ? occupancy.getTotalCarriages()
                : (train.getTotalCarriages() != null ? train.getTotalCarriages() : 5);
        int seatsPerCarriage = occupancy.getSeatsPerCarriage() != null && occupancy.getSeatsPerCarriage() > 0
                ? occupancy.getSeatsPerCarriage()
                : (train.getSeatsPerCarriage() != null ? train.getSeatsPerCarriage() : 36);

        String batchKey = train.getId() + "_" + departureDate;
        Set<String> occupied = new LinkedHashSet<>();
        if (occupancy.getOccupiedSeats() != null) {
            occupied.addAll(occupancy.getOccupiedSeats());
        }
        occupied.addAll(reservedInBatch.getOrDefault(batchKey, Set.of()));

        List<CheckoutPurchase.SeatBookingItem> result = new ArrayList<>();

        if (preferredCarriage != null && preferredCarriage >= 1 && preferredCarriage <= totalCarriages
                && preferredSeat != null && preferredSeat >= 1 && preferredSeat <= seatsPerCarriage) {
            String prefKey = preferredCarriage + "_" + preferredSeat;
            if (!occupied.contains(prefKey)) {
                occupied.add(prefKey);
                reservedInBatch.computeIfAbsent(batchKey, k -> new LinkedHashSet<>()).add(prefKey);
                result.add(CheckoutPurchase.SeatBookingItem.builder()
                        .trainId(train.getId())
                        .departureDate(departureDate)
                        .carriage(preferredCarriage)
                        .seatNumber(preferredSeat)
                        .build());
            }
        }

        int startCarriage = (preferredCarriage != null && preferredCarriage >= 1 && preferredCarriage <= totalCarriages)
                ? preferredCarriage
                : (train.getDefaultCarriage() != null && train.getDefaultCarriage() >= 1 ? train.getDefaultCarriage() : 1);

        for (int offset = 0; offset < totalCarriages && result.size() < quantity; offset++) {
            int carriage = ((startCarriage - 1 + offset) % totalCarriages) + 1;
            for (int seat = 1; seat <= seatsPerCarriage && result.size() < quantity; seat++) {
                String seatKey = carriage + "_" + seat;
                if (!occupied.contains(seatKey)) {
                    occupied.add(seatKey);
                    reservedInBatch.computeIfAbsent(batchKey, k -> new LinkedHashSet<>()).add(seatKey);
                    result.add(CheckoutPurchase.SeatBookingItem.builder()
                            .trainId(train.getId())
                            .departureDate(departureDate)
                            .carriage(carriage)
                            .seatNumber(seat)
                            .build());
                }
            }
        }

        return result;
    }
}
