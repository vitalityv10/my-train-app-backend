package org.ipz.backend.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ipz.backend.exception.NotFoundException;
import org.ipz.backend.model.Cart;
import org.ipz.backend.model.CartItem;
import org.ipz.backend.model.Train;
import org.ipz.backend.service.CartService;
import org.ipz.backend.service.TrainService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Service
@Slf4j
public class CartServiceImpl implements CartService {

    private static final String CART_KEY_PREFIX = "cart:";
    private static final Duration CART_TTL = Duration.ofDays(7);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final TrainService trainService;

    @Override
    public Cart getCart(String firebaseUid) {
        String key = buildCartKey(firebaseUid);
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (StringUtils.hasText(json)) {
                Cart cart = objectMapper.readValue(json, Cart.class);
                if (cart.getItems() == null) {
                    cart.setItems(new ArrayList<>());
                }
                return cart;
            }
        } catch (Exception e) {
            log.warn("Failed to read cart from Redis key {}: {}", key, e.getMessage());
        }
        return Cart.builder().items(new ArrayList<>()).build();
    }

    @Override
    public Cart replaceCart(String firebaseUid, Cart cart) {
        Cart sanitized = Cart.builder()
                .items(cart != null && cart.getItems() != null ? new ArrayList<>(cart.getItems()) : new ArrayList<>())
                .build();
        saveCart(firebaseUid, sanitized);
        return sanitized;
    }

    @Override
    public Cart addItem(String firebaseUid, Integer trainId, Integer quantity) {
        int qtyToAdd = (quantity != null && quantity > 0) ? quantity : 1;
        Cart cart = getCart(firebaseUid);
        List<CartItem> items = new ArrayList<>(cart.getItems());

        Optional<CartItem> existingItem = items.stream()
                .filter(item -> trainId.equals(item.getId()))
                .findFirst();

        if (existingItem.isPresent()) {
            CartItem item = existingItem.get();
            item.setQuantity((item.getQuantity() != null ? item.getQuantity() : 0) + qtyToAdd);
        } else {
            Train train = trainService.findById(trainId);
            CartItem newItem = CartItem.builder()
                    .id(train.getId())
                    .number(train.getNumber())
                    .title(train.getTitle())
                    .type(train.getType())
                    .hasWifi(train.getHasWifi())
                    .hasAirConditioning(train.getHasAirConditioning())
                    .fromCity(train.getFromCity())
                    .fromStation(train.getFromStation())
                    .departureTime(train.getDepartureTime())
                    .toCity(train.getToCity())
                    .toStation(train.getToStation())
                    .arrivalTime(train.getArrivalTime())
                    .duration(train.getDuration())
                    .seatClass(train.getSeatClass())
                    .carriage(train.getDefaultCarriage())
                    .seatNumber(train.getDefaultSeatNumber())
                    .basePrice(train.getBasePrice())
                    .quantity(qtyToAdd)
                    .build();
            items.add(newItem);
        }

        Cart updated = Cart.builder().items(items).build();
        saveCart(firebaseUid, updated);
        return updated;
    }

    @Override
    public Cart updateItemQuantity(String firebaseUid, Integer trainId, Integer quantity) {
        Cart cart = getCart(firebaseUid);
        List<CartItem> items = new ArrayList<>(cart.getItems());

        CartItem existing = items.stream()
                .filter(item -> trainId.equals(item.getId()))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Cart item for trainId " + trainId + " not found"));

        existing.setQuantity(quantity);

        Cart updated = Cart.builder().items(items).build();
        saveCart(firebaseUid, updated);
        return updated;
    }

    @Override
    public Cart removeItem(String firebaseUid, Integer trainId) {
        Cart cart = getCart(firebaseUid);
        List<CartItem> items = new ArrayList<>(cart.getItems());
        items.removeIf(item -> trainId.equals(item.getId()));

        Cart updated = Cart.builder().items(items).build();
        saveCart(firebaseUid, updated);
        return updated;
    }

    @Override
    public void clearCart(String firebaseUid) {
        String key = buildCartKey(firebaseUid);
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("Failed to delete Redis cart key {}: {}", key, e.getMessage());
        }
    }

    private void saveCart(String firebaseUid, Cart cart) {
        String key = buildCartKey(firebaseUid);
        try {
            String json = objectMapper.writeValueAsString(cart);
            redisTemplate.opsForValue().set(key, json, CART_TTL);
        } catch (Exception e) {
            log.warn("Failed to write cart to Redis key {}: {}", key, e.getMessage());
        }
    }

    private String buildCartKey(String firebaseUid) {
        return CART_KEY_PREFIX + firebaseUid;
    }
}
