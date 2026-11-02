package org.ipz.backend.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ipz.backend.exception.NotFoundException;
import org.ipz.backend.exception.SeatOccupiedException;
import org.ipz.backend.model.CheckoutPurchase;
import org.ipz.backend.model.Ticket;
import org.ipz.backend.model.entity.TicketEntity;
import org.ipz.backend.model.entity.TrainEntity;
import org.ipz.backend.model.entity.TrainSeatOccupancyEntity;
import org.ipz.backend.model.entity.UserEntity;
import org.ipz.backend.model.enums.PaymentMethod;
import org.ipz.backend.model.enums.PaymentStatus;
import org.ipz.backend.repository.TicketRepository;
import org.ipz.backend.repository.TrainRepository;
import org.ipz.backend.repository.TrainSeatOccupancyRepository;
import org.ipz.backend.repository.UserRepository;
import org.ipz.backend.service.CartService;
import org.ipz.backend.service.CheckoutService;
import org.ipz.backend.service.OccupancyService;
import org.ipz.backend.service.mapper.TicketServiceMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Transactional
@RequiredArgsConstructor
@Service
@Slf4j
public class CheckoutServiceImpl implements CheckoutService {

    private final UserRepository userRepository;
    private final TrainRepository trainRepository;
    private final TicketRepository ticketRepository;
    private final TrainSeatOccupancyRepository occupancyRepository;
    private final TicketServiceMapper ticketMapper;
    private final OccupancyService occupancyService;
    private final CartService cartService;

    @Override
    public List<Ticket> purchaseTickets(
            String firebaseUid,
            String userEmail,
            String userName,
            CheckoutPurchase purchaseRequest) {

        UserEntity user = userRepository.findById(firebaseUid)
                .orElseGet(() -> userRepository.save(UserEntity.builder()
                        .firebaseUid(firebaseUid)
                        .email(StringUtils.hasText(userEmail) ? userEmail : firebaseUid + "@firebase.local")
                        .name(userName)
                        .build()));

        Map<TrainDateKey, List<String>> requestedSeatsByTrainDate = new HashMap<>();
        Set<String> seenInRequest = new LinkedHashSet<>();
        List<String> duplicateInRequest = new ArrayList<>();

        for (CheckoutPurchase.SeatBookingItem item : purchaseRequest.getTickets()) {
            String seatKey = item.getCarriage() + "_" + item.getSeatNumber();
            String composite = item.getTrainId() + "_" + item.getDepartureDate() + "_" + seatKey;
            if (!seenInRequest.add(composite)) {
                duplicateInRequest.add(seatKey);
            }
            TrainDateKey trainDateKey = new TrainDateKey(item.getTrainId(), item.getDepartureDate());
            requestedSeatsByTrainDate.computeIfAbsent(trainDateKey, k -> new ArrayList<>()).add(seatKey);
        }

        if (!duplicateInRequest.isEmpty()) {
            throw buildSeatOccupiedException(duplicateInRequest);
        }

        List<String> allConflictingSeats = new ArrayList<>();
        for (Map.Entry<TrainDateKey, List<String>> entry : requestedSeatsByTrainDate.entrySet()) {
            TrainDateKey key = entry.getKey();
            List<String> conflicts = occupancyRepository.findConflictingSeatKeys(
                    key.trainId(), key.departureDate(), entry.getValue());
            allConflictingSeats.addAll(conflicts);
        }

        if (!allConflictingSeats.isEmpty()) {
            throw buildSeatOccupiedException(allConflictingSeats);
        }

        long timestamp = System.currentTimeMillis();
        OffsetDateTime now = OffsetDateTime.now();
        PaymentMethod paymentMethod = purchaseRequest.getPaymentMethod();
        PaymentStatus paymentStatus = paymentMethod == PaymentMethod.CARD ? PaymentStatus.PAID : PaymentStatus.PENDING;
        OffsetDateTime paidAt = paymentMethod == PaymentMethod.CARD ? now : null;

        List<TicketEntity> savedTickets = new ArrayList<>();
        List<TrainSeatOccupancyEntity> occupanciesToSave = new ArrayList<>();

        List<CheckoutPurchase.SeatBookingItem> items = purchaseRequest.getTickets();
        for (int index = 0; index < items.size(); index++) {
            CheckoutPurchase.SeatBookingItem item = items.get(index);
            TrainEntity train = trainRepository.findById(item.getTrainId())
                    .orElseThrow(() -> new NotFoundException("Train with id " + item.getTrainId() + " not found"));

            validateCarriageAndSeatBounds(train, item.getCarriage(), item.getSeatNumber());

            String ticketId = "TKT-" + timestamp + "-" + train.getId() + "-" + index;
            TicketEntity ticketEntity = TicketEntity.builder()
                    .ticketId(ticketId)
                    .user(user)
                    .train(train)
                    .departureDate(item.getDepartureDate())
                    .carriage(item.getCarriage())
                    .seatNumber(item.getSeatNumber())
                    .seatClass(train.getSeatClass())
                    .price(train.getBasePrice())
                    .paymentMethod(paymentMethod)
                    .paymentStatus(paymentStatus)
                    .contactPhone(purchaseRequest.getContactPhone())
                    .paidAt(paidAt)
                    .createdAt(now)
                    .build();

            TicketEntity savedTicket = ticketRepository.save(ticketEntity);
            savedTickets.add(savedTicket);

            String seatKey = item.getCarriage() + "_" + item.getSeatNumber();
            TrainSeatOccupancyEntity occupancyEntity = TrainSeatOccupancyEntity.builder()
                    .train(train)
                    .departureDate(item.getDepartureDate())
                    .carriage(item.getCarriage())
                    .seatNumber(item.getSeatNumber())
                    .seatKey(seatKey)
                    .ticket(savedTicket)
                    .occupiedAt(now)
                    .build();
            occupanciesToSave.add(occupancyEntity);
        }

        try {
            occupancyRepository.saveAllAndFlush(occupanciesToSave);
        } catch (DataIntegrityViolationException ex) {
            List<String> fallbackConflicts = items.stream()
                    .map(i -> i.getCarriage() + "_" + i.getSeatNumber())
                    .toList();
            throw buildSeatOccupiedException(fallbackConflicts);
        }

        for (Map.Entry<TrainDateKey, List<String>> entry : requestedSeatsByTrainDate.entrySet()) {
            TrainDateKey key = entry.getKey();
            occupancyService.recordOccupiedSeats(key.trainId(), key.departureDate(), entry.getValue());
        }

        cartService.clearCart(firebaseUid);

        log.info("Successfully purchased {} tickets for user {}", savedTickets.size(), firebaseUid);
        return ticketMapper.toDomainList(savedTickets);
    }

    private void validateCarriageAndSeatBounds(TrainEntity train, Integer carriage, Integer seatNumber) {
        if (carriage < 1 || carriage > train.getTotalCarriages()
                || seatNumber < 1 || seatNumber > train.getSeatsPerCarriage()) {
            throw new IllegalArgumentException(
                    String.format("Invalid carriage (%d) or seat (%d) for train %d", carriage, seatNumber, train.getId()));
        }
    }

    private SeatOccupiedException buildSeatOccupiedException(List<String> conflictingSeats) {
        String firstSeat = conflictingSeats.get(0);
        String[] parts = firstSeat.split("_");
        String message = parts.length == 2
                ? String.format("Вагон %s, місце %s вже зайняте іншим пасажиром.", parts[0], parts[1])
                : "Одне або декілька обраних місць вже зайняті.";
        return new SeatOccupiedException(message, conflictingSeats);
    }

    private record TrainDateKey(Integer trainId, LocalDate departureDate) {
    }
}
