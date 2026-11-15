package com.hapbang.coupon.in;

import java.util.List;

import com.hapbang.coupon.app.dto.RecoveryRunView;
import com.hapbang.coupon.global.response.RsData;
import com.hapbang.coupon.in.dto.RecoveryResumeRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** 관리자 쿠폰 발급 복구 API Swagger 명세. 매핑은 {@link ApiV1CouponRecoveryAdminController} */
@Tag(name = "쿠폰 발급 복구", description = "관리자의 Redis 유실 복구 절차: 차단 → 경계 확정 → 결과 확정 → 재구축 → 재개(재구축 전에는 취소 가능, 재개 중 Redis 유실이 확인되면 예외로 취소)")
public interface ApiV1CouponRecoveryAdminSpec {

    @Operation(summary = "복구 실행 목록", description = "관리자. 쿠폰의 복구 실행을 최근 것부터 돌려준다.")
    RsData<List<RecoveryRunView>> list(Long couponId);

    @Operation(summary = "1. 복구 시작(차단)",
            description = "관리자. 복구 실행과 새 epoch 후보를 기록하고 신규 발급 접수를 막는다(201). 발급을 연 적이 없는 쿠폰은 409 INVALID_COUPON_STATUS. "
                    + "끝나지 않은 복구가 있으면 409 COUPON_RECOVERY_IN_PROGRESS(시작 단계에 머문 복구면 차단만 다시 하고 그 복구를 돌려준다). "
                    + "발급 저장소 장애면 503이며 다시 보내면 차단을 다시 한다.")
    RsData<RecoveryRunView> start(Long adminId, Long couponId);

    @Operation(summary = "2. 경계 확정",
            description = "관리자. 접수 차단 시각(blockedAt)에서 진행 중이던 생산자 전송이 끝날 시간(전송 준비 대기 + max(전송 수명, 재발행 ACK 대기) + 여유)이 "
                    + "지나야 한다. 지나기 전이면 409 COUPON_RECOVERY_STEP_NOT_ALLOWED와 남은 시간이다. 남아 있는 접수 기록 중 DB 결과가 없는 요청을 같은 요청 ID·이전 epoch로 재발행하고(보상 완료·미처리 DLT(PENDING·REPLAYING) 제외) "
                    + "Kafka 발급 토픽·DLT의 끝 offset을 경계로 기록한다. 재발행이 실패하면 503 COUPON_EVENT_PUBLISH_FAILED, "
                    + "Kafka 경계를 읽지 못하면 503 COUPON_ISSUE_FAILED이고 경계는 기록하지 않는다. 다시 실행할 수 있다. 접수 기록이 유실됐으면 requestsLost=true이며 그 사이 접수는 결과가 UNKNOWN으로 남는다.")
    RsData<RecoveryRunView> fixBoundary(Long adminId, Long couponId, Long recoveryId);

    @Operation(summary = "3. 결과 확정",
            description = "관리자. 미처리(DB 결과가 없는 접수 기록, 경계까지 소비되지 않은 Kafka 메시지, 미처리 DLT(PENDING·REPLAYING))를 세어 기록한다. "
                    + "0이 될 때까지 다시 실행한다. 미처리 DLT는 DLT 재발행·영구 거절로 정리한다. 재구축을 선점한 뒤에는 409 COUPON_RECOVERY_STEP_NOT_ALLOWED.")
    RsData<RecoveryRunView> confirmResults(Long adminId, Long couponId, Long recoveryId);

    @Operation(summary = "4. 재구축",
            description = "관리자. 미처리가 0이면 DB 확정 결과로 Redis(재고 = 총수량 − 확정 발급, 회원 = 확정 발급 회원, 접수 기록 = DB 결과가 있는 요청, "
                    + "issue-control = 새 epoch)를 한 번에 다시 만든다. 접수는 막힌 채다. Redis를 바꾸기 전에 재구축을 선점한다(step=REBUILDING). "
                    + "미처리가 남았거나 순서가 맞지 않거나 같은 복구의 재구축·재개가 진행 중이거나 재개를 선점한 뒤(RESUMING)이거나 Redis가 이 복구의 차단이 아니면(재개 뒤 등) "
                    + "409 COUPON_RECOVERY_STEP_NOT_ALLOWED. "
                    + "Redis 결과를 알 수 없으면 503이고 REBUILDING에 남아 재구축만 다시 할 수 있다(취소·결과 확정 불가).")
    RsData<RecoveryRunView> rebuild(Long adminId, Long couponId, Long recoveryId);

    @Operation(summary = "복구 취소",
            description = "관리자. 재구축 선점 전(차단·경계 확정·결과 확정 단계)의 복구를 취소한다(step=ABORTED). 신규 발급 접수 차단은 풀지 않는다. "
                    + "취소한 복구는 끝난 복구라 새 복구를 시작할 수 있고, 유실 의심인 채 취소했으면 새 복구가 그 의심을 이어받는다. "
                    + "이미 취소한 복구면 200으로 그 복구를 그대로 돌려준다(첫 취소 기록 유지). "
                    + "재구축을 선점한 뒤(REBUILDING·REBUILT·RESUMING)이거나 재개한 복구면 409 COUPON_RECOVERY_STEP_NOT_ALLOWED(재구축 뒤에는 재개로만 끝낸다). "
                    + "예외: RESUMING이고 진행 중인 재개가 없고 Redis issue-control이 없거나 Redis 프로세스(runId)가 바뀐 것이 확인되면 먼저 이 복구로 접수를 다시 막고(막지 못하면 503, 취소 안 함) 취소한다(step=ABORTED, "
                    + "abortReason 기록, requestsLost=true). 새 복구가 유실 의심을 이어받아 처음부터 한다. 이 조건이 하나라도 맞지 않으면 409이고 재개만 다시 한다. "
                    + "복구 실행이 없으면 404 COUPON_RECOVERY_NOT_FOUND.")
    RsData<RecoveryRunView> abort(Long adminId, Long couponId, Long recoveryId);

    @Operation(summary = "5. 재개",
            description = "관리자. 먼저 재개를 선점하고(step=RESUMING, 그 사이 재구축·취소는 409) 수량 대사(총수량 = DB 확정 발급 + Redis 재고, 진행 중 0)가 맞으면 "
                    + "새 epoch를 활성화하고 접수 차단을 푼다. 재개 근거가 필요하다. 같은 복구의 재구축·재개가 진행 중이거나, 대사가 맞지 않거나, 재구축 뒤 Redis가 바뀌었거나, "
                    + "Redis가 다른 복구의 차단이면 409 COUPON_RECOVERY_STEP_NOT_ALLOWED이고 선점 전 단계로 돌아간다(REBUILT면 재구축을 다시 할 수 있다). "
                    + "Redis 결과를 알 수 없으면 503이고 RESUMING에 남아 재개만 다시 할 수 있다(이미 열렸으면 다시 보내도 재개로 끝난다). "
                    + "쿠폰이 중단·종료 상태면 epoch만 활성화하고 접수는 열지 않는다.")
    RsData<RecoveryRunView> resume(Long adminId, Long couponId, Long recoveryId, RecoveryResumeRequest request);
}
