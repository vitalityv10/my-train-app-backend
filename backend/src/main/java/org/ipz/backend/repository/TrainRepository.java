package org.ipz.backend.repository;

import org.ipz.backend.model.entity.TrainEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TrainRepository extends JpaRepository<TrainEntity, Integer>, JpaSpecificationExecutor<TrainEntity> {

    @Query("""
            SELECT DISTINCT city FROM (
                SELECT t.fromCity AS city FROM TrainEntity t
                UNION
                SELECT t.toCity AS city FROM TrainEntity t
            ) cities
            ORDER BY city
            """)
    List<String> findAllDistinctCities();
}
