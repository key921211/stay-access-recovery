package com.handies.accessrecovery;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(indexes = @Index(name = "idx_grant_job_due", columnList = "status,next_attempt_at"))
public class AccessGrantJob {

    public static final int MAX_RETRIES = 3;
    public static final int MAX_ATTEMPTS = 1 + MAX_RETRIES;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false, unique = true, updatable = false)
    private Reservation reservation;

    @Column(nullable = false, unique = true, updatable = false)
    private UUID requestId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private GrantStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant nextAttemptAt;
    private Instant issuedAt;
    private String grantId;
    private String lastError;

    protected AccessGrantJob() {
    }

    public AccessGrantJob(Reservation reservation, Instant now) {
        this.reservation = reservation;
        this.requestId = UUID.randomUUID();
        this.status = GrantStatus.PENDING;
        this.createdAt = now;
        this.nextAttemptAt = now;
    }

    public boolean isDue(Instant now) {
        return status == GrantStatus.PENDING && !nextAttemptAt.isAfter(now);
    }

    public void recordAttempt() {
        attempts++;
    }

    public void markIssued(String grantId, Instant now) {
        this.status = GrantStatus.ISSUED;
        this.grantId = grantId;
        this.issuedAt = now;
        this.nextAttemptAt = null;
        this.lastError = null;
    }

    public void recordFailure(String error, Instant retryAt) {
        this.lastError = error;
        if (attempts >= MAX_ATTEMPTS) {
            this.status = GrantStatus.FAILED;
            this.nextAttemptAt = null;
        } else {
            this.nextAttemptAt = retryAt;
        }
    }

    public Long getId() {
        return id;
    }

    public Reservation getReservation() {
        return reservation;
    }

    public UUID getRequestId() {
        return requestId;
    }

    public GrantStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public String getGrantId() {
        return grantId;
    }

    public String getLastError() {
        return lastError;
    }
}
