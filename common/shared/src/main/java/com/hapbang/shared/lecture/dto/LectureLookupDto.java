package com.hapbang.shared.lecture.dto;

import java.util.Objects;

/**
 * 강의 조회 내부 API({@code GET /api/v1/internal/lectures/{lectureId}})의 응답. 쿠폰이 판매자 소유 확인과 가격 snapshot에 쓴다.
 * 삭제된 강의는 이 API에서 없는 강의(404)다.
 *
 * @param lectureId  강의 ID
 * @param sellerId   판매자 회원 ID
 * @param price      지금 가격(HP)
 * @param saleStatus 강의 검수 상태 이름. {@code PENDING_INSPECTION}(검수 대기), {@code REJECTED}(반려), {@code ON_SALE}(판매 중).
 *                   장바구니 담기·주문은 {@code ON_SALE}에서만 받는다
 */
public record LectureLookupDto(
        Long lectureId,
        Long sellerId,
        long price,
        String saleStatus
) {

    public LectureLookupDto {
        Objects.requireNonNull(lectureId, "lectureId는 필수입니다.");
        Objects.requireNonNull(sellerId, "sellerId는 필수입니다.");
        Objects.requireNonNull(saleStatus, "saleStatus는 필수입니다.");
    }
}
