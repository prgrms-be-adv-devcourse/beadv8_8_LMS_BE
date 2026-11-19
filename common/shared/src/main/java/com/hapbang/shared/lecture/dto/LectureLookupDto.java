package com.hapbang.shared.lecture.dto;

import java.util.Objects;

/**
 * 강의 조회 내부 API({@code GET /api/v1/internal/lectures/{lectureId}}, 일괄 {@code GET /api/v1/internal/lectures?ids=})의 응답.
 * 쿠폰이 판매자 소유 확인·가격 snapshot(하나)과 쿠폰 받기 화면의 강의 제목·판매 중 거르기(일괄)에 쓴다.
 * 삭제된 강의는 이 API에서 없는 강의다(하나는 404, 일괄은 값 {@code null}).
 *
 * @param lectureId  강의 ID
 * @param sellerId   판매자 회원 ID
 * @param price      지금 가격(HP)
 * @param saleStatus 강의 검수 상태 이름. {@code PENDING_INSPECTION}(검수 대기), {@code REJECTED}(반려), {@code ON_SALE}(판매 중).
 *                   장바구니 담기·주문은 {@code ON_SALE}에서만 받는다
 * @param title      지금 강의 제목. 강의는 늘 채운다. 나중에 더한 필드라 레코드에서 {@code null}을 막지 않는다
 */
public record LectureLookupDto(
        Long lectureId,
        Long sellerId,
        long price,
        String saleStatus,
        String title
) {

    /** 일괄 조회 한 번에 물을 수 있는 강의 ID 수(중복 포함). 넘으면 강의가 400으로 거절하고, 부르는 쪽은 이만큼씩 나눠 부른다 */
    public static final int MAX_BATCH_SIZE = 100;

    public LectureLookupDto {
        Objects.requireNonNull(lectureId, "lectureId는 필수입니다.");
        Objects.requireNonNull(sellerId, "sellerId는 필수입니다.");
        Objects.requireNonNull(saleStatus, "saleStatus는 필수입니다.");
    }
}
