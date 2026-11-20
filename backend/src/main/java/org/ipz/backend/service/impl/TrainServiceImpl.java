package org.ipz.backend.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ipz.backend.exception.NotFoundException;
import org.ipz.backend.model.Train;
import org.ipz.backend.model.dto.TrainFilter;
import org.ipz.backend.model.entity.TrainEntity;
import org.ipz.backend.model.enums.TrainType;
import org.ipz.backend.repository.TrainRepository;
import org.ipz.backend.service.TrainService;
import org.ipz.backend.service.mapper.TrainServiceMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Transactional
@RequiredArgsConstructor
@Service
@Slf4j
public class TrainServiceImpl implements TrainService {

    private static final String CACHE_KEY_ALL_TRAINS = "trains:list:all";
    private static final String CACHE_KEY_CITIES = "trains:cities";
    private static final Duration ALL_TRAINS_TTL = Duration.ofMinutes(10);
    private static final Duration CITIES_TTL = Duration.ofHours(1);

    private final TrainRepository trainRepository;
    private final TrainServiceMapper mapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(readOnly = true)
    public List<Train> findTrains(TrainFilter filter) {
        if (isUnfiltered(filter)) {
            List<Train> cached = getFromCache(CACHE_KEY_ALL_TRAINS, new TypeReference<>() {});
            if (cached != null) {
                log.debug("Returning {} trains from Redis cache ({})", cached.size(), CACHE_KEY_ALL_TRAINS);
                return cached;
            }

            List<TrainEntity> entities = trainRepository.findAll(Sort.by(Sort.Direction.ASC, "id"));
            List<Train> trains = mapper.toDomainList(entities);
            putInCache(CACHE_KEY_ALL_TRAINS, trains, ALL_TRAINS_TTL);
            return trains;
        }

        Specification<TrainEntity> spec = buildSpecification(filter);
        Sort sort = Sort.by(Sort.Direction.ASC, "id");

        List<TrainEntity> entities;
        if (filter.getPage() != null && filter.getLimit() != null) {
            PageRequest pageRequest = PageRequest.of(filter.getPage() - 1, filter.getLimit(), sort);
            entities = trainRepository.findAll(spec, pageRequest).getContent();
        } else {
            entities = trainRepository.findAll(spec, sort);
        }

        return mapper.toDomainList(entities);
    }

    @Override
    @Transactional(readOnly = true)
    public Train findById(Integer id) {
        TrainEntity entity = trainRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Train with id " + id + " not found"));
        return mapper.toDomain(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> findAllCities() {
        List<String> cached = getFromCache(CACHE_KEY_CITIES, new TypeReference<>() {});
        if (cached != null) {
            log.debug("Returning {} cities from Redis cache ({})", cached.size(), CACHE_KEY_CITIES);
            return cached;
        }

        List<String> cities = trainRepository.findAllDistinctCities();
        putInCache(CACHE_KEY_CITIES, cities, CITIES_TTL);
        return cities;
    }

    @Override
    public List<Train> seedInitialTrains() {
        List<TrainEntity> existing = trainRepository.findAll(Sort.by(Sort.Direction.ASC, "id"));
        Set<String> existingNumbers = existing.stream()
                .map(TrainEntity::getNumber)
                .collect(Collectors.toSet());

        List<TrainEntity> toInsert = buildInitialTrainsData().stream()
                .filter(train -> !existingNumbers.contains(train.getNumber()))
                .toList();

        if (!toInsert.isEmpty()) {
            trainRepository.saveAll(toInsert);
            log.info("Seeded {} new trains into database.", toInsert.size());
        } else {
            log.info("All seed trains already exist in database, skipping insert.");
        }

        evictTrainCaches();
        return mapper.toDomainList(trainRepository.findAll(Sort.by(Sort.Direction.ASC, "id")));
    }

    private boolean isUnfiltered(TrainFilter filter) {
        if (filter == null) {
            return true;
        }
        return !StringUtils.hasText(filter.getSearch())
                && !StringUtils.hasText(filter.getCity())
                && filter.getDate() == null
                && filter.getMaxPrice() == null
                && !Boolean.TRUE.equals(filter.getWifiOnly())
                && filter.getPage() == null
                && filter.getLimit() == null;
    }

    private Specification<TrainEntity> buildSpecification(TrainFilter filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (StringUtils.hasText(filter.getSearch())) {
                String pattern = "%" + filter.getSearch().trim().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("number")), pattern),
                        cb.like(cb.lower(root.get("fromCity")), pattern),
                        cb.like(cb.lower(root.get("toCity")), pattern)
                ));
            }

            if (StringUtils.hasText(filter.getCity())) {
                String city = filter.getCity().trim().toLowerCase();
                predicates.add(cb.or(
                        cb.equal(cb.lower(root.get("fromCity")), city),
                        cb.equal(cb.lower(root.get("toCity")), city)
                ));
            }

            if (filter.getDate() != null) {
                OffsetDateTime endOfDay = filter.getDate().plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
                predicates.add(cb.lessThanOrEqualTo(root.get("departureTime"), endOfDay));
            }

            if (filter.getMaxPrice() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("basePrice"), filter.getMaxPrice()));
            }

            if (Boolean.TRUE.equals(filter.getWifiOnly())) {
                predicates.add(cb.isTrue(root.get("hasWifi")));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private <T> T getFromCache(String key, TypeReference<T> typeReference) {
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (StringUtils.hasText(json)) {
                return objectMapper.readValue(json, typeReference);
            }
        } catch (Exception e) {
            log.warn("Failed to read from Redis cache key {}: {}", key, e.getMessage());
        }
        return null;
    }

    private void putInCache(String key, Object value, Duration ttl) {
        try {
            String json = objectMapper.writeValueAsString(value);
            redisTemplate.opsForValue().set(key, json, ttl);
        } catch (Exception e) {
            log.warn("Failed to write to Redis cache key {}: {}", key, e.getMessage());
        }
    }

    private void evictTrainCaches() {
        try {
            redisTemplate.delete(List.of(CACHE_KEY_ALL_TRAINS, CACHE_KEY_CITIES));
        } catch (Exception e) {
            log.warn("Failed to evict Redis train caches: {}", e.getMessage());
        }
    }

    private List<TrainEntity> buildInitialTrainsData() {
        return List.of(
                TrainEntity.builder()
                        .number("715К")
                        .title("Інтерсіті+ Київ — Львів")
                        .description("Швидкісний денний поїзд підвищеного комфорту з кав'ярнею на борту.")
                        .type(TrainType.DAY)
                        .imageUrl("https://images.unsplash.com/photo-1474487548417-781cb71495f3?auto=format&fit=crop&w=800&q=80")
                        .hasWifi(true)
                        .hasAirConditioning(true)
                        .fromCity("Київ")
                        .fromStation("Київ-Пасажирський")
                        .departureTime(OffsetDateTime.parse("2026-11-05T06:50:00Z"))
                        .toCity("Львів")
                        .toStation("Львів-Головний")
                        .arrivalTime(OffsetDateTime.parse("2026-11-05T12:15:00Z"))
                        .duration("5 год 25 хв")
                        .seatClass("1 клас")
                        .defaultCarriage(1)
                        .defaultSeatNumber(14)
                        .totalCarriages(3)
                        .seatsPerCarriage(36)
                        .basePrice(new BigDecimal("220.00"))
                        .build(),
                TrainEntity.builder()
                        .number("105К")
                        .title("Чорноморець Київ — Одеса")
                        .description("Фірмовий нічний експрес зі зручними спальними купе.")
                        .type(TrainType.NIGHT)
                        .imageUrl("https://images.unsplash.com/photo-1515165562839-978bbcf18277?auto=format&fit=crop&w=800&q=80")
                        .hasWifi(true)
                        .hasAirConditioning(true)
                        .fromCity("Київ")
                        .fromStation("Київ-Пасажирський")
                        .departureTime(OffsetDateTime.parse("2026-11-06T21:15:00Z"))
                        .toCity("Одеса")
                        .toStation("Одеса-Головна")
                        .arrivalTime(OffsetDateTime.parse("2026-11-07T06:30:00Z"))
                        .duration("9 год 15 хв")
                        .seatClass("Купе")
                        .defaultCarriage(4)
                        .defaultSeatNumber(8)
                        .totalCarriages(3)
                        .seatsPerCarriage(36)
                        .basePrice(new BigDecimal("450.00"))
                        .build(),
                TrainEntity.builder()
                        .number("722К")
                        .title("Інтерсіті+ Київ — Харків")
                        .description("Сучасний швидкісний електропоїзд із просторими салонами та розетками біля кожного місця.")
                        .type(TrainType.DAY)
                        .imageUrl("https://images.unsplash.com/photo-1532105956626-9569c03602f6?auto=format&fit=crop&w=800&q=80")
                        .hasWifi(true)
                        .hasAirConditioning(true)
                        .fromCity("Київ")
                        .fromStation("Київ-Пасажирський")
                        .departureTime(OffsetDateTime.parse("2026-11-07T13:00:00Z"))
                        .toCity("Харків")
                        .toStation("Харків-Пасажирський")
                        .arrivalTime(OffsetDateTime.parse("2026-11-07T18:10:00Z"))
                        .duration("5 год 10 хв")
                        .seatClass("2 клас")
                        .defaultCarriage(2)
                        .defaultSeatNumber(22)
                        .totalCarriages(3)
                        .seatsPerCarriage(36)
                        .basePrice(new BigDecimal("310.00"))
                        .build(),
                TrainEntity.builder()
                        .number("732К")
                        .title("Інтерсіті+ Київ — Дніпро")
                        .description("Швидкісний денний поїзд до Дніпра з Wi-Fi, кондиціонером та буфетом.")
                        .type(TrainType.DAY)
                        .imageUrl("https://images.unsplash.com/photo-1474487548417-781cb71495f3?auto=format&fit=crop&w=800&q=80")
                        .hasWifi(true)
                        .hasAirConditioning(true)
                        .fromCity("Київ")
                        .fromStation("Київ-Пасажирський")
                        .departureTime(OffsetDateTime.parse("2026-11-08T07:15:00Z"))
                        .toCity("Дніпро")
                        .toStation("Дніпро-Головний")
                        .arrivalTime(OffsetDateTime.parse("2026-11-08T12:55:00Z"))
                        .duration("5 год 40 хв")
                        .seatClass("2 клас")
                        .defaultCarriage(1)
                        .defaultSeatNumber(10)
                        .totalCarriages(3)
                        .seatsPerCarriage(36)
                        .basePrice(new BigDecimal("340.00"))
                        .build(),
                TrainEntity.builder()
                        .number("043К")
                        .title("Стефанія Експрес Київ — Івано-Франківськ")
                        .description("Нічний фірмовий поїзд до Карпат зі зручними купейними вагонами.")
                        .type(TrainType.NIGHT)
                        .imageUrl("https://images.unsplash.com/photo-1515165562839-978bbcf18277?auto=format&fit=crop&w=800&q=80")
                        .hasWifi(false)
                        .hasAirConditioning(true)
                        .fromCity("Київ")
                        .fromStation("Київ-Пасажирський")
                        .departureTime(OffsetDateTime.parse("2026-11-08T19:05:00Z"))
                        .toCity("Івано-Франківськ")
                        .toStation("Івано-Франківськ")
                        .arrivalTime(OffsetDateTime.parse("2026-11-09T05:45:00Z"))
                        .duration("10 год 40 хв")
                        .seatClass("Купе")
                        .defaultCarriage(3)
                        .defaultSeatNumber(12)
                        .totalCarriages(3)
                        .seatsPerCarriage(36)
                        .basePrice(new BigDecimal("490.00"))
                        .build(),
                TrainEntity.builder()
                        .number("029К")
                        .title("Нічний експрес Київ — Ужгород")
                        .description("Комфортний нічний поїзд через Карпати до Закарпаття з вагонами люкс і купе.")
                        .type(TrainType.NIGHT)
                        .imageUrl("https://images.unsplash.com/photo-1515165562839-978bbcf18277?auto=format&fit=crop&w=800&q=80")
                        .hasWifi(true)
                        .hasAirConditioning(true)
                        .fromCity("Київ")
                        .fromStation("Київ-Пасажирський")
                        .departureTime(OffsetDateTime.parse("2026-11-09T20:30:00Z"))
                        .toCity("Ужгород")
                        .toStation("Ужгород")
                        .arrivalTime(OffsetDateTime.parse("2026-11-10T09:15:00Z"))
                        .duration("12 год 45 хв")
                        .seatClass("Люкс")
                        .defaultCarriage(2)
                        .defaultSeatNumber(4)
                        .totalCarriages(3)
                        .seatsPerCarriage(36)
                        .basePrice(new BigDecimal("720.00"))
                        .build(),
                TrainEntity.builder()
                        .number("012Л")
                        .title("Західний експрес Львів — Одеса")
                        .description("Прямий нічний поїзд між Львовом та узбережжям Чорного моря.")
                        .type(TrainType.NIGHT)
                        .imageUrl("https://images.unsplash.com/photo-1515165562839-978bbcf18277?auto=format&fit=crop&w=800&q=80")
                        .hasWifi(false)
                        .hasAirConditioning(true)
                        .fromCity("Львів")
                        .fromStation("Львів-Головний")
                        .departureTime(OffsetDateTime.parse("2026-11-10T22:10:00Z"))
                        .toCity("Одеса")
                        .toStation("Одеса-Головна")
                        .arrivalTime(OffsetDateTime.parse("2026-11-11T08:40:00Z"))
                        .duration("10 год 30 хв")
                        .seatClass("Купе")
                        .defaultCarriage(1)
                        .defaultSeatNumber(16)
                        .totalCarriages(3)
                        .seatsPerCarriage(36)
                        .basePrice(new BigDecimal("540.00"))
                        .build(),
                TrainEntity.builder()
                        .number("702Л")
                        .title("Регіональний експрес Львів — Чернівці")
                        .description("Бюджетний денний регіональний поїзд мальовничим маршрутом Прикарпаття.")
                        .type(TrainType.DAY)
                        .imageUrl("https://images.unsplash.com/photo-1532105956626-9569c03602f6?auto=format&fit=crop&w=800&q=80")
                        .hasWifi(false)
                        .hasAirConditioning(false)
                        .fromCity("Львів")
                        .fromStation("Львів-Головний")
                        .departureTime(OffsetDateTime.parse("2026-11-11T08:00:00Z"))
                        .toCity("Чернівці")
                        .toStation("Чернівці")
                        .arrivalTime(OffsetDateTime.parse("2026-11-11T12:20:00Z"))
                        .duration("4 год 20 хв")
                        .seatClass("2 клас")
                        .defaultCarriage(2)
                        .defaultSeatNumber(18)
                        .totalCarriages(3)
                        .seatsPerCarriage(36)
                        .basePrice(new BigDecimal("185.00"))
                        .build(),
                TrainEntity.builder()
                        .number("038К")
                        .title("Нічний експрес Київ — Запоріжжя")
                        .description("Швидкий нічний поїзд із плацкартними та купейними вагонами.")
                        .type(TrainType.NIGHT)
                        .imageUrl("https://images.unsplash.com/photo-1515165562839-978bbcf18277?auto=format&fit=crop&w=800&q=80")
                        .hasWifi(false)
                        .hasAirConditioning(true)
                        .fromCity("Київ")
                        .fromStation("Київ-Пасажирський")
                        .departureTime(OffsetDateTime.parse("2026-11-12T21:45:00Z"))
                        .toCity("Запоріжжя")
                        .toStation("Запоріжжя-1")
                        .arrivalTime(OffsetDateTime.parse("2026-11-13T07:05:00Z"))
                        .duration("9 год 20 хв")
                        .seatClass("Плацкарт")
                        .defaultCarriage(3)
                        .defaultSeatNumber(25)
                        .totalCarriages(3)
                        .seatsPerCarriage(36)
                        .basePrice(new BigDecimal("260.00"))
                        .build(),
                TrainEntity.builder()
                        .number("724К")
                        .title("Інтерсіті+ Харків — Київ")
                        .description("Зворотний швидкісний денний рейс із Wi-Fi та кав'ярнею.")
                        .type(TrainType.DAY)
                        .imageUrl("https://images.unsplash.com/photo-1474487548417-781cb71495f3?auto=format&fit=crop&w=800&q=80")
                        .hasWifi(true)
                        .hasAirConditioning(true)
                        .fromCity("Харків")
                        .fromStation("Харків-Пасажирський")
                        .departureTime(OffsetDateTime.parse("2026-11-13T07:20:00Z"))
                        .toCity("Київ")
                        .toStation("Київ-Пасажирський")
                        .arrivalTime(OffsetDateTime.parse("2026-11-13T12:25:00Z"))
                        .duration("5 год 05 хв")
                        .seatClass("1 клас")
                        .defaultCarriage(1)
                        .defaultSeatNumber(6)
                        .totalCarriages(3)
                        .seatsPerCarriage(36)
                        .basePrice(new BigDecimal("390.00"))
                        .build()
        );
    }
}
