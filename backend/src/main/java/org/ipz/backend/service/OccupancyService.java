package org.ipz.backend.service;

import org.ipz.backend.model.TrainOccupancy;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDate;
import java.util.List;

public interface OccupancyService {

    TrainOccupancy getTrainOccupancy(Integer trainId, LocalDate date);

    SseEmitter subscribeOccupancyStream(Integer trainId, LocalDate date);

    void recordOccupiedSeats(Integer trainId, LocalDate date, List<String> seatKeys);

    boolean tryLockSeat(Integer trainId, LocalDate date, Integer carriage, Integer seatNumber, String firebaseUid);
}
