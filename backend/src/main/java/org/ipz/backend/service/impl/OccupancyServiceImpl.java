package org.ipz.backend.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ipz.backend.exception.NotFoundException;
import org.ipz.backend.model.TrainOccupancy;
import org.ipz.backend.model.dto.OccupancyStreamEventDto;
import org.ipz.backend.model.entity.TrainEntity;
import org.ipz.backend.repository.TrainRepository;
import org.ipz.backend.repository.TrainSeatOccupancyRepository;
import org.ipz.backend.service.OccupancyService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Transactional
@RequiredArgsConstructor
@Service
@Slf4j
public class OccupancyServiceImpl implements OccupancyService {

    private static final String OCCUPANCY_KEY_PREFIX = "occupancy:";
    private static final String SEAT_LOCK_KEY_PREFIX = "seat_lock:";
    private static final Duration OCCUPANCY_TTL = Duration.ofHours(24);
    private static final Duration SEAT_LOCK_TTL = Duration.ofMinutes(10);
    private static final long SSE_TIMEOUT_MS = 30 * 60 * 1000L;

    private final TrainRepository trainRepository;
    private final TrainSeatOccupancyRepository occupancyRepository;
    private final StringRedisTemplate redisTemplate;

    private final Map<String, CopyOnWriteArrayList<SseEmitter>> emittersByOccupancyId = new ConcurrentHashMap<>();

    @Override
    @Transactional(readOnly = true)
    public TrainOccupancy getTrainOccupancy(Integer trainId, LocalDate date) {
        TrainEntity train = trainRepository.findById(trainId)
                .orElseThrow(() -> new NotFoundException("Train with id " + trainId + " not found"));

        String occupancyId = buildOccupancyId(trainId, date);
        String redisKey = OCCUPANCY_KEY_PREFIX + occupancyId;

        Set<String> occupiedSet = new LinkedHashSet<>();
        boolean cacheHit = false;

        try {
            Boolean exists = redisTemplate.hasKey(redisKey);
            if (Boolean.TRUE.equals(exists)) {
                Set<String> cachedMembers = redisTemplate.opsForSet().members(redisKey);
                if (cachedMembers != null) {
                    occupiedSet.addAll(cachedMembers);
                    cacheHit = true;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to read occupancy from Redis key {}: {}", redisKey, e.getMessage());
        }

        if (!cacheHit) {
            List<String> dbOccupied = occupancyRepository.findOccupiedSeatKeysByTrainIdAndDepartureDate(trainId, date);
            occupiedSet.addAll(dbOccupied);
            try {
                if (!dbOccupied.isEmpty()) {
                    redisTemplate.opsForSet().add(redisKey, dbOccupied.toArray(new String[0]));
                    redisTemplate.expire(redisKey, OCCUPANCY_TTL);
                }
            } catch (Exception e) {
                log.warn("Failed to populate Redis occupancy set {}: {}", redisKey, e.getMessage());
            }
        }

        mergeActiveSeatLocks(occupancyId, occupiedSet);

        return TrainOccupancy.builder()
                .occupancyId(occupancyId)
                .trainId(trainId)
                .date(date)
                .totalCarriages(train.getTotalCarriages())
                .seatsPerCarriage(train.getSeatsPerCarriage())
                .occupiedSeats(new ArrayList<>(occupiedSet))
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public SseEmitter subscribeOccupancyStream(Integer trainId, LocalDate date) {
        TrainOccupancy initialOccupancy = getTrainOccupancy(trainId, date);
        String occupancyId = initialOccupancy.getOccupancyId();

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        CopyOnWriteArrayList<SseEmitter> emitters = emittersByOccupancyId.computeIfAbsent(
                occupancyId, k -> new CopyOnWriteArrayList<>());
        emitters.add(emitter);

        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));

        try {
            emitter.send(SseEmitter.event()
                    .name("occupancy")
                    .data(OccupancyStreamEventDto.builder()
                            .occupiedSeats(initialOccupancy.getOccupiedSeats())
                            .build()));
        } catch (IOException e) {
            emitters.remove(emitter);
        }

        return emitter;
    }

    @Override
    public void recordOccupiedSeats(Integer trainId, LocalDate date, List<String> seatKeys) {
        if (seatKeys == null || seatKeys.isEmpty()) {
            return;
        }
        String occupancyId = buildOccupancyId(trainId, date);
        String redisKey = OCCUPANCY_KEY_PREFIX + occupancyId;

        try {
            redisTemplate.opsForSet().add(redisKey, seatKeys.toArray(new String[0]));
            redisTemplate.expire(redisKey, OCCUPANCY_TTL);

            List<String> lockKeysToDelete = seatKeys.stream()
                    .map(seatKey -> SEAT_LOCK_KEY_PREFIX + occupancyId + ":" + seatKey)
                    .toList();
            redisTemplate.delete(lockKeysToDelete);
        } catch (Exception e) {
            log.warn("Failed to update Redis occupancy set {}: {}", redisKey, e.getMessage());
        }

        broadcastOccupancyUpdate(trainId, date);
    }

    @Override
    public boolean tryLockSeat(Integer trainId, LocalDate date, Integer carriage, Integer seatNumber, String firebaseUid) {
        String occupancyId = buildOccupancyId(trainId, date);
        String seatKey = carriage + "_" + seatNumber;
        String lockKey = SEAT_LOCK_KEY_PREFIX + occupancyId + ":" + seatKey;

        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, firebaseUid, SEAT_LOCK_TTL);
            if (Boolean.TRUE.equals(acquired)) {
                broadcastOccupancyUpdate(trainId, date);
                return true;
            }
            String currentHolder = redisTemplate.opsForValue().get(lockKey);
            return firebaseUid.equals(currentHolder);
        } catch (Exception e) {
            log.warn("Failed to acquire Redis seat lock {}: {}", lockKey, e.getMessage());
            return true;
        }
    }

    private void mergeActiveSeatLocks(String occupancyId, Set<String> occupiedSet) {
        String pattern = SEAT_LOCK_KEY_PREFIX + occupancyId + ":*";
        String prefix = SEAT_LOCK_KEY_PREFIX + occupancyId + ":";
        try {
            Set<String> lockKeys = redisTemplate.keys(pattern);
            if (lockKeys != null) {
                for (String lockKey : lockKeys) {
                    if (lockKey.startsWith(prefix)) {
                        occupiedSet.add(lockKey.substring(prefix.length()));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to scan active seat locks in Redis for {}: {}", occupancyId, e.getMessage());
        }
    }

    private void broadcastOccupancyUpdate(Integer trainId, LocalDate date) {
        String occupancyId = buildOccupancyId(trainId, date);
        CopyOnWriteArrayList<SseEmitter> emitters = emittersByOccupancyId.get(occupancyId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }

        TrainOccupancy current = getTrainOccupancy(trainId, date);
        OccupancyStreamEventDto payload = OccupancyStreamEventDto.builder()
                .occupiedSeats(current.getOccupiedSeats())
                .build();

        List<SseEmitter> deadEmitters = new ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("occupancy").data(payload));
            } catch (Exception e) {
                deadEmitters.add(emitter);
            }
        }
        emitters.removeAll(deadEmitters);
    }

    private String buildOccupancyId(Integer trainId, LocalDate date) {
        return trainId + "_" + date;
    }
}
