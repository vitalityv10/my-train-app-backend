package org.ipz.backend.service;

import org.ipz.backend.model.Cart;

public interface CartService {

    Cart getCart(String firebaseUid);

    Cart replaceCart(String firebaseUid, Cart cart);

    Cart addItem(String firebaseUid, Integer trainId, Integer quantity);

    Cart updateItemQuantity(String firebaseUid, Integer trainId, Integer quantity);

    Cart removeItem(String firebaseUid, Integer trainId);

    void clearCart(String firebaseUid);
}
