package org.ipz.backend.repository;

import org.ipz.backend.model.entity.TicketEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TicketRepository extends JpaRepository<TicketEntity, String> {

    List<TicketEntity> findAllByUserFirebaseUidOrderByCreatedAtDesc(String firebaseUid);
}
