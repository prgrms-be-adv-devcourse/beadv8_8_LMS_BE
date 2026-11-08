package com.hapbang.coupon.in;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.hapbang.coupon.app.dto.CouponDltLogView;
import com.hapbang.coupon.domain.CouponDltStatus;
import com.hapbang.coupon.global.response.RsData;
import com.hapbang.coupon.in.dto.CouponDltRejectRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/** 관리자 쿠폰 발급 DLT API Swagger 명세. 매핑은 {@link ApiV1CouponDltAdminController} */
@Tag(name = "쿠폰 발급 DLT", description = "제한 재시도 뒤에도 처리하지 못해 격리된 발급 요청의 관리자 재발행·영구 거절")
public interface ApiV1CouponDltAdminSpec {

    @Operation(summary = "DLT 격리 기록 목록",
            description = "관리자. 최신순(기본). 상태(PENDING·REPLAYING·REPLAYED·REJECTED)를 주면 그 상태만. PENDING·REPLAYING(재발행 선점 중)이 "
                    + "남아 있으면 미처리로 보고 재발행 배치가 그 요청을 다시 보내지 않으며 Redis 유실 복구도 재구축으로 넘어가지 않는다.")
    RsData<Page<CouponDltLogView>> list(@Parameter(description = "상태. 없으면 전체") CouponDltStatus status,
                                        Pageable pageable);

    @Operation(summary = "DLT 재발행",
            description = "관리자. 전송 전에 PENDING 기록을 REPLAYING으로 선점한 뒤 원문을 같은 요청 ID로 원 토픽에 다시 보내고, ACK를 받으면 REPLAYED로 "
                    + "끝낸다. 재발행은 처리 완료가 아니다(결과는 발급 요청 상태로 확인하고, 다시 실패하면 새 PENDING 기록이 생긴다). 선점한 채 멈춘 "
                    + "REPLAYING은 선점 lease(hapbang.coupon.dlt-replay-lease)가 지나면 다시 재발행·영구 거절할 수 있다. PENDING·lease가 지난 "
                    + "REPLAYING이 아니거나 동시에 처리되면 409 "
                    + "COUPON_DLT_ALREADY_RESOLVED, 원문을 읽을 수 없으면 409 INVALID_COUPON_STATUS, 그 쿠폰의 복구가 진행 중이면 409 "
                    + "COUPON_RECOVERY_IN_PROGRESS. Kafka 전송 실패는 503 COUPON_EVENT_PUBLISH_FAILED이고 기록을 PENDING으로 되돌린다(다시 보내면 된다). "
                    + "그 사이 같은 요청이 다시 격리됐으면 이 기록은 REPLAYED로 끝나고 새 PENDING 기록을 처리한다.")
    RsData<CouponDltLogView> replay(Long adminId, Long dltId);

    @Operation(summary = "DLT 영구 거절",
            description = "관리자. 발급 요청 결과를 거절(사유)로 먼저 확정한 뒤 Redis 수량을 요청당 한 번 되돌린다(재고 +1, 회원 접수 기록 삭제). "
                    + "이전 epoch의 요청이면 재고를 바꾸지 않는다. 결과는 compensationResult. Redis 장애면 503이고 거절은 확정된 채다. "
                    + "같은 요청을 다시 보내면 보상만 다시 하며 재고는 다시 바뀌지 않는다. 이미 발급이 확정된 요청, 재발행한 기록, 선점 lease 안의 "
                    + "REPLAYING 기록은 409 COUPON_DLT_ALREADY_RESOLVED.")
    RsData<CouponDltLogView> reject(Long adminId, Long dltId, CouponDltRejectRequest request);
}
