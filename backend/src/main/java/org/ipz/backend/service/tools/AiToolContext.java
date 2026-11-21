package org.ipz.backend.service.tools;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.ipz.backend.model.Cart;
import org.ipz.backend.model.Ticket;
import org.ipz.backend.model.Train;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Getter
@RequiredArgsConstructor
public class AiToolContext {

    private static final int MAX_SUGGESTED_TRAINS = 6;

    private final String firebaseUid;
    private final String userEmail;
    private final String userName;

    private final Map<Integer, Train> collectedTrains = new LinkedHashMap<>();
    private final List<Ticket> bookedTickets = new ArrayList<>();

    @Setter
    private Cart updatedCart;

    public boolean isAuthenticated() {
        return StringUtils.hasText(firebaseUid);
    }

    public void addSuggestedTrain(Train train) {
        if (train != null && train.getId() != null && collectedTrains.size() < MAX_SUGGESTED_TRAINS) {
            collectedTrains.put(train.getId(), train);
        }
    }
}
