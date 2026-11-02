package org.ipz.backend.service;

import org.ipz.backend.model.CheckoutPurchase;
import org.ipz.backend.model.Ticket;

import java.util.List;

public interface CheckoutService {

    List<Ticket> purchaseTickets(String firebaseUid, String userEmail, String userName, CheckoutPurchase purchaseRequest);
}
