package com.handies.accessrecovery;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "recovery.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class GrantScheduler {

    private static final Logger log = LoggerFactory.getLogger(GrantScheduler.class);

    private final AccessGrantJobRepository jobs;
    private final GrantWorker worker;
    private final Clock clock;

    public GrantScheduler(AccessGrantJobRepository jobs, GrantWorker worker, Clock clock) {
        this.jobs = jobs;
        this.worker = worker;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${recovery.poll-delay-ms:1000}")
    public void poll() {
        for (AccessGrantJob job : jobs
                .findTop20ByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscIdAsc(
                        GrantStatus.PENDING, clock.instant())) {
            try {
                // 별도 빈을 호출해 작업별 트랜잭션을 적용한다.
                worker.process(job.getId());
            } catch (RuntimeException failure) {
                log.error("발급 작업 처리 중 예기치 않은 오류: jobId={}", job.getId(), failure);
            }
        }
    }
}
