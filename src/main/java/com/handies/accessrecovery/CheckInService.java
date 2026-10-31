package com.handies.accessrecovery;

import java.time.Clock;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CheckInService {

    private final ReservationRepository reservations;
    private final AccessGrantJobRepository jobs;
    private final Clock clock;

    public CheckInService(ReservationRepository reservations, AccessGrantJobRepository jobs, Clock clock) {
        this.reservations = reservations;
        this.jobs = jobs;
        this.clock = clock;
    }

    @Transactional
    public CheckInResult checkIn(long reservationId) {
        // 작업이 아직 없을 때도 중복 요청을 직렬화하려고 예약 행을 먼저 잠근다.
        Reservation reservation = reservations.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "예약을 찾을 수 없습니다."));
        var existingJob = jobs.findByReservationId(reservationId);
        if (existingJob.isPresent()) {
            return new CheckInResult(GrantStatusResponse.from(existingJob.get()), false);
        }

        Instant now = clock.instant();
        reservation.checkIn(now);
        AccessGrantJob job = jobs.save(new AccessGrantJob(reservation, now));
        return new CheckInResult(GrantStatusResponse.from(job), true);
    }

    @Transactional(readOnly = true)
    public GrantStatusResponse getStatus(long reservationId) {
        if (!reservations.existsById(reservationId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "예약을 찾을 수 없습니다.");
        }
        return jobs.findByReservationId(reservationId)
                .map(GrantStatusResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "먼저 체크인해야 합니다."));
    }

    public record CheckInResult(GrantStatusResponse status, boolean created) {
    }
}
