package org.ipz.backend.repository;

import org.ipz.backend.model.entity.TrainSeatOccupancyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface TrainSeatOccupancyRepository extends JpaRepository<TrainSeatOccupancyEntity, Integer> {

    @Query("""
            SELECT o.seatKey FROM TrainSeatOccupancyEntity o
            WHERE o.train.id = :trainId AND o.departureDate = :departureDate
            """)
    List<String> findOccupiedSeatKeysByTrainIdAndDepartureDate(
            @Param("trainId") Integer trainId,
            @Param("departureDate") LocalDate departureDate
    );

    @Query("""
            SELECT o.seatKey FROM TrainSeatOccupancyEntity o
            WHERE o.train.id = :trainId
              AND o.departureDate = :departureDate
              AND o.seatKey IN :seatKeys
            """)
    List<String> findConflictingSeatKeys(
            @Param("trainId") Integer trainId,
            @Param("departureDate") LocalDate departureDate,
            @Param("seatKeys") List<String> seatKeys
    );
}
