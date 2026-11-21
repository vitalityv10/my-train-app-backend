package org.ipz.backend.service.tools;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.ipz.backend.model.Cart;
import org.ipz.backend.service.CartService;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component("get_cart")
@RequiredArgsConstructor
public class GetCartTool implements AiTool {

    private final CartService cartService;
    private final AiToolSupport toolSupport;

    @Override
    public Map<String, Object> getDefinition() {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", "get_cart",
                        "description", "Повертає поточний вміст кошика авторизованого користувача.",
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
                    "message", "Користувач не авторизований. Попросіть його увійти в акаунт, щоб переглядати кошик."
            );
        }

        Cart cart = cartService.getCart(context.getFirebaseUid());
        context.setUpdatedCart(cart);
        return toolSupport.buildCartSummaryMap(cart);
    }
}
