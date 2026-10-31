package com.handies.accessrecovery;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccessGrantJobRepository extends JpaRepository<AccessGrantJob, Long> {

    Optional<AccessGrantJob> findByReservationId(Long reservationId);

    List<AccessGrantJob> findTop20ByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscIdAsc(
            GrantStatus status, Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from AccessGrantJob j where j.id = :id")
    Optional<AccessGrantJob> findByIdForUpdate(@Param("id") Long id);
}
