package com.handies.accessrecovery;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "발급을 확인한 ISSUED 상태에서만 canEnter가 true입니다.", example = """
        {
          "reservationId": 1001,
          "requestId": "7851f1ee-a46c-4aa5-a479-d77ca51d0804",
          "status": "PENDING",
          "canEnter": false,
          "attempts": 0,
          "maxAttempts": 4,
          "nextAttemptAt": "2026-10-31T00:00:00Z"
        }
        """)
public record GrantStatusResponse(
        long reservationId,
        UUID requestId,
        GrantStatus status,
        boolean canEnter,
        int attempts,
        int maxAttempts,
        Instant nextAttemptAt,
        Instant issuedAt,
        String grantId,
        String lastError) {

    public static GrantStatusResponse from(AccessGrantJob job) {
        return new GrantStatusResponse(
                job.getReservation().getId(), job.getRequestId(), job.getStatus(),
                job.getStatus() == GrantStatus.ISSUED, job.getAttempts(), AccessGrantJob.MAX_ATTEMPTS,
                job.getNextAttemptAt(), job.getIssuedAt(), job.getGrantId(), job.getLastError());
    }
}
