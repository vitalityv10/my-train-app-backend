package org.ipz.backend.service.tools;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.ipz.backend.model.Cart;
import org.ipz.backend.model.Train;
import org.ipz.backend.service.CartService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component("add_to_cart")
@RequiredArgsConstructor
public class AddToCartTool implements AiTool {

    private final CartService cartService;
    private final AiToolSupport toolSupport;

    @Override
    public Map<String, Object> getDefinition() {
        Map<String, Object> addToCartProps = new LinkedHashMap<>();
        addToCartProps.put("trainId", Map.of(
                "type", "integer",
                "description", "Числовий ID потяга, який потрібно додати до кошика"
        ));
        addToCartProps.put("trainNumber", Map.of(
                "type", "string",
                "description", "Номер потяга (наприклад, '705К'), якщо числовий trainId невідомий"
        ));
        addToCartProps.put("quantity", Map.of(
                "type", "integer",
                "description", "Кількість квитків для додавання в кошик (за замовчуванням 1)"
        ));

        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", "add_to_cart",
                        "description", "Додає обраний потяг (або вказану кількість квитків) до кошика авторизованого користувача.",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", addToCartProps
                        )
                )
        );
    }

    @Override
    public Object execute(JsonNode args, AiToolContext context) {
        if (!context.isAuthenticated()) {
            return Map.of(
                    "authenticated", false,
                    "message", "Користувач не авторизований. Попросіть його увійти в акаунт, щоб додавати квитки до кошика."
            );
        }

        Train train = toolSupport.resolveTrainFromArgs(args);
        if (train == null) {
            return Map.of(
                    "success", false,
                    "error", "Потяг не знайдено. Перевірте trainId або номер потяга через search_trains."
            );
        }

        int quantity = args.path("quantity").asInt(1);
        if (quantity < 1) {
            quantity = 1;
        }

        Cart updatedCart = cartService.addItem(context.getFirebaseUid(), train.getId(), quantity);
        context.setUpdatedCart(updatedCart);
        context.addSuggestedTrain(train);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "Потяг успішно додано до кошика");
        result.put("addedTrain", toolSupport.toCompactTrainMap(train));
        result.put("addedQuantity", quantity);
        result.put("cart", toolSupport.buildCartSummaryMap(updatedCart));
        return result;
    }
}
