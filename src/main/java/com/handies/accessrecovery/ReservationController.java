package com.handies.accessrecovery;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reservations")
@Tag(name = "예약 출입 권한", description = "예약 체크인과 출입 권한 발급 상태 조회")
public class ReservationController {

    private final CheckInService checkInService;

    public ReservationController(CheckInService checkInService) {
        this.checkInService = checkInService;
    }

    @PostMapping("/{reservationId}/check-in")
    @Operation(summary = "예약 체크인", description = "체크인과 발급 작업을 함께 저장합니다. 중복 요청은 기존 작업을 반환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "체크인과 발급 작업 저장 완료, 발급 대기",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = GrantStatusResponse.class))),
            @ApiResponse(responseCode = "200", description = "중복 체크인 요청, 기존 작업의 현재 상태 반환",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = GrantStatusResponse.class))),
            @ApiResponse(responseCode = "400", description = "예약 ID가 숫자가 아님",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "예약이 존재하지 않음",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<GrantStatusResponse> checkIn(
            @Parameter(description = "사전 등록된 예약 ID", example = "1001") @PathVariable long reservationId) {
        var result = checkInService.checkIn(reservationId);
        return ResponseEntity.status(result.created() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .body(result.status());
    }

    @GetMapping("/{reservationId}/access-grant")
    @Operation(summary = "출입 권한 발급 상태 조회",
            description = "DB에 저장된 발급 확인 상태를 조회합니다. ISSUED일 때만 canEnter=true를 반환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "발급 상태 조회 성공: PENDING, ISSUED 또는 FAILED",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = GrantStatusResponse.class))),
            @ApiResponse(responseCode = "400", description = "예약 ID가 숫자가 아님",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "예약이 존재하지 않음",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "아직 체크인하지 않은 예약",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    })
    public GrantStatusResponse getStatus(
            @Parameter(description = "조회할 예약 ID", example = "1001") @PathVariable long reservationId) {
        return checkInService.getStatus(reservationId);
    }
}
