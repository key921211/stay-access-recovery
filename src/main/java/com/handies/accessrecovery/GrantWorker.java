package com.handies.accessrecovery;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GrantWorker {

    private final AccessGrantJobRepository jobs;
    private final FakeDoorlockClient doorlock;
    private final Clock clock;
    private final long retryDelayMillis;

    public GrantWorker(AccessGrantJobRepository jobs, FakeDoorlockClient doorlock, Clock clock,
            @Value("${recovery.retry-delay-ms:5000}") long retryDelayMillis) {
        if (retryDelayMillis < 0) {
            throw new IllegalArgumentException("재시도 간격은 0 이상이어야 합니다.");
        }
        this.jobs = jobs;
        this.doorlock = doorlock;
        this.clock = clock;
        this.retryDelayMillis = retryDelayMillis;
    }

    @Transactional
    public void process(long jobId) {
        AccessGrantJob job = jobs.findByIdForUpdate(jobId).orElse(null);
        if (job == null || !job.isDue(clock.instant())) {
            return;
        }

        job.recordAttempt();
        try {
            var grant = doorlock.issue(job.getRequestId(), job.getReservation().getId(),
                    job.getReservation().getRoomNumber());
            job.markIssued(grant.grantId(), clock.instant());
        } catch (FakeDoorlockClient.DoorlockException failure) {
            job.recordFailure(failure.getMessage(), clock.instant().plusMillis(retryDelayMillis));
        }
    }
}
