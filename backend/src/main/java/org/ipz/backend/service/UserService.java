package org.ipz.backend.service;

import org.ipz.backend.model.User;

public interface UserService {

    User syncUser(String firebaseUid, String tokenEmail, String tokenName, String tokenPicture, User requestUser);

    User getUserWithTickets(String firebaseUid);
}
