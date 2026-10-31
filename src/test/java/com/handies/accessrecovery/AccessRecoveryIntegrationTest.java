package com.handies.accessrecovery;

import static com.handies.accessrecovery.FakeDoorlockClient.Scenario.ALWAYS_FAIL;
import static com.handies.accessrecovery.FakeDoorlockClient.Scenario.ALWAYS_TIMEOUT_AFTER_ISSUE;
import static com.handies.accessrecovery.FakeDoorlockClient.Scenario.SUCCESS;
import static com.handies.accessrecovery.FakeDoorlockClient.Scenario.TIMEOUT_AFTER_ISSUE;
import static com.handies.accessrecovery.FakeDoorlockClient.Scenario.TRANSIENT_THEN_SUCCESS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
@Import(AccessRecoveryIntegrationTest.ClockConfiguration.class)
class AccessRecoveryIntegrationTest {

    private static final Instant START = Instant.parse("2026-10-30T00:00:00Z");
    private static final Duration RETRY_DELAY = Duration.ofSeconds(5);

    @Autowired MockMvc mvc;
    @Autowired CheckInService checkIns;
    @Autowired ReservationRepository reservations;
    @Autowired AccessGrantJobRepository jobs;
    @Autowired GrantWorker worker;
    @Autowired FakeDoorlockClient doorlock;
    @Autowired MutableClock clock;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach
    void setUp() {
        jobs.deleteAll();
        reservations.deleteAll();
        reservations.saveAll(List.of(new Reservation(1001L, "101"), new Reservation(1002L, "102")));
        doorlock.reset(SUCCESS);
        clock.reset();
    }

    @Test
    void normalIssuanceIsVisibleOnlyAfterConfirmation() throws Exception {
        mvc.perform(post("/api/reservations/1001/check-in"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.canEnter").value(false))
                .andExpect(jsonPath("$.attempts").value(0));
        var pending = checkIns.getStatus(1001L);
        assertThat(doorlock.issuedGrantCount()).isZero();

        worker.process(jobId(1001L));

        mvc.perform(get("/api/reservations/1001/access-grant"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ISSUED"))
                .andExpect(jsonPath("$.canEnter").value(true))
                .andExpect(jsonPath("$.requestId").value(pending.requestId().toString()))
                .andExpect(jsonPath("$.attempts").value(1))
                .andExpect(jsonPath("$.maxAttempts").value(4))
                .andExpect(jsonPath("$.issuedAt").exists())
                .andExpect(jsonPath("$.grantId").exists())
                .andExpect(jsonPath("$.nextAttemptAt").doesNotExist());
        mvc.perform(post("/api/reservations/1001/check-in"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ISSUED"));
        worker.process(jobId(1001L));
        assertThat(doorlock.callCount(pending.requestId())).isEqualTo(1);
        assertThat(doorlock.issuedGrantCount()).isEqualTo(1);
        assertThat(jobs.count()).isEqualTo(1);
    }

    @Test
    void transientFailureRetriesOnlyWhenDueAndKeepsRequestId() {
        doorlock.reset(TRANSIENT_THEN_SUCCESS);
        var initial = checkIns.checkIn(1001L).status();
        long jobId = jobId(1001L);

        worker.process(jobId);

        var pending = checkIns.getStatus(1001L);
        assertThat(pending.status()).isEqualTo(GrantStatus.PENDING);
        assertThat(pending.canEnter()).isFalse();
        assertThat(pending.attempts()).isEqualTo(1);
        assertThat(pending.lastError()).isEqualTo("TEMPORARY_ERROR");
        assertThat(pending.nextAttemptAt()).isEqualTo(START.plus(RETRY_DELAY));
        worker.process(jobId);
        assertThat(doorlock.callCount(initial.requestId())).isEqualTo(1);

        clock.advance(RETRY_DELAY);
        worker.process(jobId);

        var issued = checkIns.getStatus(1001L);
        assertThat(issued.status()).isEqualTo(GrantStatus.ISSUED);
        assertThat(issued.requestId()).isEqualTo(initial.requestId());
        assertThat(issued.attempts()).isEqualTo(2);
        assertThat(issued.lastError()).isNull();
        assertThat(issued.nextAttemptAt()).isNull();
        assertThat(doorlock.callCount(initial.requestId())).isEqualTo(2);
    }

    @Test
    void stopsAfterInitialAttemptAndThreeRetries() throws Exception {
        doorlock.reset(ALWAYS_FAIL);
        var initial = checkIns.checkIn(1001L).status();
        long jobId = jobId(1001L);

        exhaustAttempts(jobId);

        mvc.perform(get("/api/reservations/1001/access-grant"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.canEnter").value(false))
                .andExpect(jsonPath("$.attempts").value(4))
                .andExpect(jsonPath("$.lastError").value("TEMPORARY_ERROR"))
                .andExpect(jsonPath("$.nextAttemptAt").doesNotExist())
                .andExpect(jsonPath("$.grantId").doesNotExist());
        clock.advance(Duration.ofDays(1));
        worker.process(jobId);
        var duplicate = checkIns.checkIn(1001L);
        assertThat(duplicate.created()).isFalse();
        assertThat(duplicate.status().status()).isEqualTo(GrantStatus.FAILED);
        assertThat(duplicate.status().requestId()).isEqualTo(initial.requestId());
        assertThat(jobs.count()).isEqualTo(1);
        assertThat(doorlock.callCount(initial.requestId())).isEqualTo(4);
        assertThat(doorlock.issuedGrantCount()).isZero();
    }

    @Test
    void duplicateCheckInReturnsSameJobWithoutCallingDoorlock() throws Exception {
        var first = checkIns.checkIn(1001L);

        mvc.perform(post("/api/reservations/1001/check-in"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(first.status().requestId().toString()))
                .andExpect(jsonPath("$.status").value("PENDING"));

        assertThat(jobs.count()).isEqualTo(1);
        assertThat(doorlock.callCount(first.status().requestId())).isZero();
        assertThat(reservations.findById(1001L).orElseThrow().getCheckedInAt()).isEqualTo(START);
    }

    @Test
    void concurrentCheckInsCreateExactlyOneJob() throws Exception {
        var results = concurrently(6, () -> checkIns.checkIn(1001L));

        assertThat(results.stream().filter(CheckInService.CheckInResult::created).count()).isEqualTo(1);
        assertThat(results.stream().map(result -> result.status().requestId()).distinct().count()).isEqualTo(1);
        assertThat(jobs.count()).isEqualTo(1);
        assertThat(doorlock.issuedGrantCount()).isZero();
    }

    @Test
    void concurrentWorkersRecheckStatusUnderJobLock() throws Exception {
        var initial = checkIns.checkIn(1001L).status();
        long jobId = jobId(1001L);

        concurrently(4, () -> {
            worker.process(jobId);
            return true;
        });

        assertThat(checkIns.getStatus(1001L).status()).isEqualTo(GrantStatus.ISSUED);
        assertThat(doorlock.callCount(initial.requestId())).isEqualTo(1);
        assertThat(doorlock.issuedGrantCount()).isEqualTo(1);
    }

    @Test
    void timeoutAfterExternalIssuanceReusesRequestIdWithoutDuplicateGrant() {
        doorlock.reset(TIMEOUT_AFTER_ISSUE);
        var initial = checkIns.checkIn(1001L).status();
        long jobId = jobId(1001L);

        worker.process(jobId);

        var uncertain = checkIns.getStatus(1001L);
        assertThat(uncertain.status()).isEqualTo(GrantStatus.PENDING);
        assertThat(uncertain.canEnter()).isFalse();
        assertThat(uncertain.lastError()).isEqualTo("TIMEOUT");
        assertThat(uncertain.grantId()).isNull();
        assertThat(doorlock.issuedGrantCount()).isEqualTo(1);

        clock.advance(RETRY_DELAY);
        worker.process(jobId);

        var confirmed = checkIns.getStatus(1001L);
        assertThat(confirmed.status()).isEqualTo(GrantStatus.ISSUED);
        assertThat(confirmed.canEnter()).isTrue();
        assertThat(confirmed.requestId()).isEqualTo(initial.requestId());
        assertThat(confirmed.attempts()).isEqualTo(2);
        assertThat(doorlock.callCount(initial.requestId())).isEqualTo(2);
        assertThat(doorlock.issuedGrantCount()).isEqualTo(1);
    }

    @Test
    void exhaustedTimeoutsNeverClaimEntryEvenIfExternalGrantExists() throws Exception {
        doorlock.reset(ALWAYS_TIMEOUT_AFTER_ISSUE);
        var initial = checkIns.checkIn(1001L).status();

        exhaustAttempts(jobId(1001L));

        mvc.perform(get("/api/reservations/1001/access-grant"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.canEnter").value(false))
                .andExpect(jsonPath("$.lastError").value("TIMEOUT"));
        assertThat(doorlock.issuedGrantCount()).isEqualTo(1);
        assertThat(doorlock.callCount(initial.requestId())).isEqualTo(4);
    }

    @Test
    void checkInAndJobBothRollBackOnTransactionFailure() {
        var transaction = new TransactionTemplate(transactions);

        assertThatThrownBy(() -> transaction.executeWithoutResult(ignored -> {
            checkIns.checkIn(1001L);
            throw new IllegalStateException("의도한 트랜잭션 실패");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(reservations.findById(1001L).orElseThrow().getCheckedInAt()).isNull();
        assertThat(jobs.count()).isZero();
        assertThat(doorlock.issuedGrantCount()).isZero();
    }

    @Test
    void externalSuccessFollowedByLocalRollbackIsRecoveredWithSameRequestId() {
        var initial = checkIns.checkIn(1001L).status();
        long jobId = jobId(1001L);
        var transaction = new TransactionTemplate(transactions);

        assertThatThrownBy(() -> transaction.executeWithoutResult(ignored -> {
            worker.process(jobId);
            throw new IllegalStateException("외부 성공 후 DB 커밋 실패를 모사");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(checkIns.getStatus(1001L).status()).isEqualTo(GrantStatus.PENDING);
        assertThat(checkIns.getStatus(1001L).attempts()).isZero();
        assertThat(doorlock.issuedGrantCount()).isEqualTo(1);

        worker.process(jobId);

        assertThat(checkIns.getStatus(1001L).status()).isEqualTo(GrantStatus.ISSUED);
        assertThat(checkIns.getStatus(1001L).requestId()).isEqualTo(initial.requestId());
        assertThat(doorlock.callCount(initial.requestId())).isEqualTo(2);
        assertThat(doorlock.issuedGrantCount()).isEqualTo(1);
    }

    @Test
    void schedulerSelectsDueJobsAndRecoversMultipleReservations() {
        doorlock.reset(TRANSIENT_THEN_SUCCESS);
        var first = checkIns.checkIn(1001L).status();
        var second = checkIns.checkIn(1002L).status();
        var scheduler = new GrantScheduler(jobs, worker, clock);

        scheduler.poll();
        scheduler.poll();
        assertThat(doorlock.callCount(first.requestId())).isEqualTo(1);
        assertThat(doorlock.callCount(second.requestId())).isEqualTo(1);

        clock.advance(RETRY_DELAY);
        scheduler.poll();
        scheduler.poll();

        assertThat(checkIns.getStatus(1001L).status()).isEqualTo(GrantStatus.ISSUED);
        assertThat(checkIns.getStatus(1002L).status()).isEqualTo(GrantStatus.ISSUED);
        assertThat(doorlock.callCount(first.requestId())).isEqualTo(2);
        assertThat(doorlock.callCount(second.requestId())).isEqualTo(2);
        assertThat(doorlock.issuedGrantCount()).isEqualTo(2);
    }

    @Test
    void databaseAlsoEnforcesOneJobPerReservation() {
        checkIns.checkIn(1001L);
        Reservation reservation = reservations.findById(1001L).orElseThrow();

        assertThatThrownBy(() -> jobs.saveAndFlush(new AccessGrantJob(reservation, clock.instant())))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jobs.count()).isEqualTo(1);
    }

    @Test
    void unknownReservationReturns404WithoutCreatingJob() throws Exception {
        mvc.perform(post("/api/reservations/9999/check-in"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404));
        mvc.perform(get("/api/reservations/9999/access-grant"))
                .andExpect(status().isNotFound());
        assertThat(jobs.count()).isZero();
    }

    @Test
    void statusBeforeCheckInReturns409AndCreatesNothing() throws Exception {
        mvc.perform(get("/api/reservations/1001/access-grant"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("먼저 체크인해야 합니다."));
        assertThat(jobs.count()).isZero();
        assertThat(reservations.findById(1001L).orElseThrow().getCheckedInAt()).isNull();
    }

    @Test
    void malformedReservationIdReturns400() throws Exception {
        mvc.perform(post("/api/reservations/not-a-number/check-in"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/reservations/not-a-number/access-grant"))
                .andExpect(status().isBadRequest());
        assertThat(jobs.count()).isZero();
    }

    private long jobId(long reservationId) {
        return jobs.findByReservationId(reservationId).orElseThrow().getId();
    }

    private void exhaustAttempts(long jobId) {
        for (int attempt = 0; attempt < AccessGrantJob.MAX_ATTEMPTS; attempt++) {
            worker.process(jobId);
            clock.advance(RETRY_DELAY);
        }
    }

    private <T> List<T> concurrently(int threadCount, Callable<T> task) throws Exception {
        var executor = Executors.newFixedThreadPool(threadCount);
        var ready = new CountDownLatch(threadCount);
        var start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("동시 실행 시작 대기 시간 초과");
                    }
                    return task.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            start.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @TestConfiguration
    static class ClockConfiguration {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    static class MutableClock extends Clock {
        private volatile Instant now = START;

        void reset() {
            now = START;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
