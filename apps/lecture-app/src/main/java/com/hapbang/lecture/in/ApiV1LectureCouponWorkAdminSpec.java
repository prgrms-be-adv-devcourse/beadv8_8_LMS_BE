package com.hapbang.lecture.in;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;

import com.hapbang.lecture.app.dto.OrderCouponWorkDetailResponse;
import com.hapbang.lecture.app.dto.OrderCouponWorkFailResponse;
import com.hapbang.lecture.app.dto.OrderCouponWorkResponse;
import com.hapbang.lecture.domain.OrderCouponWorkKind;
import com.hapbang.lecture.domain.OrderCouponWorkStatus;
import com.hapbang.lecture.global.response.RsData;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/** 강의 주문 쿠폰 작업 관리 API Swagger 명세 (관리자). 매핑은 {@link ApiV1LectureCouponWorkAdminController} */
@Tag(name = "강의 쿠폰 작업", description = "관리자의 주문 쿠폰 예약·해제 작업 확인과, 자동 처리가 멈춘(DEAD) 작업의 다시 시도·결제 실패 확정·"
        + "해제 확인 완료. 예약 작업이 DEAD면 주문이 결제 처리 중(HOLD_PENDING)으로 남아 같은 강의를 다시 살 수 없다. "
        + "고르는 기준: 쿠폰 모듈이 정상으로 돌아왔으면 다시 시도(같은 작업 ID로 다시 보내 보통 흐름으로 결제까지 잇는다), "
        + "주문을 끝내야 하면 결제 실패 확정(쿠폰 쪽 예약을 확인해 남아 있으면 해제한다). 둘 다 시간만으로 결정하지 않는다.")
public interface ApiV1LectureCouponWorkAdminSpec {

    @Operation(summary = "쿠폰 작업 목록",
            description = "관리자. 최신 작업순으로 고정한다(sort 무시). 필터는 모두 선택이다. 주문 상태를 함께 준다. "
                    + "DEAD뿐 아니라 오래 막힌 PENDING(쿠폰 회로 열림 등, nextAttemptAt이 계속 미뤄짐)도 여기서 본다.")
    RsData<Page<OrderCouponWorkResponse>> getCouponWorks(
            @Parameter(description = "작업 상태") OrderCouponWorkStatus status,
            @Parameter(description = "작업 종류(RESERVE 예약, RELEASE 해제)") OrderCouponWorkKind kind,
            Pageable pageable);

    @Operation(summary = "쿠폰 작업 상세",
            description = "관리자. 작업, 주문 항목별 쿠폰 계산 근거(기대·예약 generation), 쿠폰 쪽 예약 상태(이 주문의 예약 작업 ID로 "
                    + "조회). 판단은 couponReservation.items[].coupons[].status로 한다(최상위 status는 결제 완료·취소된 사용 시도도 "
                    + "RESERVED로 준다). 쿠폰을 부르지 못하면 couponStatus=UNAVAILABLE로 주고 조회는 실패시키지 않는다. "
                    + "없는 작업은 404 COUPON_WORK_NOT_FOUND.")
    RsData<OrderCouponWorkDetailResponse> getCouponWork(String workId);

    @Operation(summary = "DEAD 작업 다시 시도",
            description = "관리자. DEAD 작업을 대기(PENDING)로 돌린다(204). 시도 횟수 0, 다음 시도 시각 지금이고, 쿠폰 작업 재전송 배치"
                    + "(기본 1분 안)가 같은 작업 ID로 보낸다. 쿠폰이 이미 예약했으면 같은 작업 ID 재응답으로 보통 흐름(대조 → HP 보류 → "
                    + "결제)이 이어진다. 예약 작업은 주문이 결제 처리 중(HOLD_PENDING), 해제 작업은 결제 실패(HOLD_FAILED)일 때만. "
                    + "없는 작업 404 COUPON_WORK_NOT_FOUND, DEAD가 아니면 409 COUPON_WORK_NOT_DEAD, 주문 상태가 맞지 않으면 "
                    + "409 COUPON_WORK_ORDER_STATE_MISMATCH.")
    ResponseEntity<Void> retry(Long adminUserId, String workId);

    @Operation(summary = "결제 실패 확정",
            description = "관리자. DEAD 예약 작업의 주문(결제 처리 중)을 결제 실패로 끝낸다. 쿠폰 예약 상태를 먼저 조회해 결과가 "
                    + "갈린다(outcome): RESERVED_RELEASING(쿠폰 쪽 예약이 남아 있음 → 예약 generation을 남기고 예약 작업 RESERVED, "
                    + "해제 작업을 만들고 한 번 보낸다. 결과를 모르면 재전송 배치가 잇는다), REJECTED(쿠폰이 거절했었음 → 예약 작업 "
                    + "REJECTED), NOT_RESERVED(기록 없음·모두 해제 → 예약 작업 REJECTED, rejectCode ADMIN_FAILED_NOT_RESERVED). 셋 다 주문은 HOLD_FAILED가 되어 같은 "
                    + "강의를 다시 살 수 있다. 쿠폰 사용 시도가 이미 결제 완료·취소로 보이면 409 COUPON_RESERVATION_SETTLED(아무것도 "
                    + "바꾸지 않음, 쿠폰·주문을 직접 확인), 쿠폰 예약 상태를 조회할 수 없으면 503 COUPON_UNAVAILABLE(아무것도 바꾸지 "
                    + "않음, 잠시 후 다시). 예약 작업이 아니면 409 COUPON_WORK_ACTION_NOT_ALLOWED, DEAD가 아니면 409 "
                    + "COUPON_WORK_NOT_DEAD, 주문이 결제 처리 중이 아니면 409 COUPON_WORK_ORDER_STATE_MISMATCH, 같은 작업을 동시에 "
                    + "바꿨으면 409 COUPON_WORK_CHANGED.")
    RsData<OrderCouponWorkFailResponse> fail(Long adminUserId, String workId);

    @Operation(summary = "해제 확인 완료",
            description = "관리자. DEAD 해제 작업을, 쿠폰 쪽에 이 주문의 예약된 사용 시도가 남지 않았음을 확인하고 RELEASED로 닫는다"
                    + "(204). 쿠폰이 해제를 거절해 멈췄지만 실제로는 이미 해제된 경우에 쓴다. 예약이 남아 있으면 409 "
                    + "COUPON_RESERVATION_STILL_HELD(다시 시도로 해제를 보낸다), 결제 완료·취소된 사용 시도가 보이면 409 "
                    + "COUPON_RESERVATION_SETTLED, 조회할 수 없으면 503 COUPON_UNAVAILABLE(아무것도 바꾸지 않음). 해제 작업이 아니면 "
                    + "409 COUPON_WORK_ACTION_NOT_ALLOWED, DEAD가 아니면 409 COUPON_WORK_NOT_DEAD.")
    ResponseEntity<Void> resolve(Long adminUserId, String workId);
}
