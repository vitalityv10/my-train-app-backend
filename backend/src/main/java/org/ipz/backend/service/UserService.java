package org.ipz.backend.service;

import org.ipz.backend.model.Ticket;
import org.ipz.backend.model.User;

import java.util.List;

public interface UserService {

    User syncUser(String firebaseUid, String tokenEmail, String tokenName, String tokenPicture, User requestUser);

    User getUserWithTickets(String firebaseUid);

    List<Ticket> getUserTickets(String firebaseUid);
}
