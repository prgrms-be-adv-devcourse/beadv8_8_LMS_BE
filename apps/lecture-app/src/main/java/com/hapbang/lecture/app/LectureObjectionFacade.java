package com.hapbang.lecture.app;

import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * 내용 다름 이의 제기 접수·판단·철회 진입점 (설계 6.2). 거절은 {@link com.hapbang.lecture.domain.LectureException}(OBJECTION_*)이다.
 * 고객센터 연동(내부 HTTP·이벤트)은 아직 없다 (#278). 호출한 쪽 트랜잭션에 참여하고(REQUIRED), 권한은 호출한 쪽이 확인한다.
 */
@Service
@RequiredArgsConstructor
public class LectureObjectionFacade {

    private final OrderObjectionFileUseCase orderObjectionFileUseCase;
    private final OrderObjectionResolveUseCase orderObjectionResolveUseCase;
    private final OrderObjectionWithdrawUseCase orderObjectionWithdrawUseCase;

    @Transactional
    public void fileObjection(Long orderItemId, Long buyerUserId, Long inquiryId, String reason) {
        Objects.requireNonNull(orderItemId, "주문 항목 ID는 필수입니다.");
        Objects.requireNonNull(buyerUserId, "구매자 ID는 필수입니다.");
        Objects.requireNonNull(inquiryId, "문의 ID는 필수입니다.");
        orderObjectionFileUseCase.fileObjection(orderItemId, buyerUserId, inquiryId, reason);
    }

    @Transactional
    public void resolveObjection(Long orderItemId, Long inquiryId, boolean accepted, Long adminUserId) {
        Objects.requireNonNull(orderItemId, "주문 항목 ID는 필수입니다.");
        Objects.requireNonNull(inquiryId, "문의 ID는 필수입니다.");
        Objects.requireNonNull(adminUserId, "판단한 관리자 ID는 필수입니다.");
        orderObjectionResolveUseCase.resolveObjection(orderItemId, inquiryId, accepted, adminUserId);
    }

    @Transactional
    public void withdrawObjection(Long orderItemId, Long buyerUserId, Long inquiryId) {
        Objects.requireNonNull(orderItemId, "주문 항목 ID는 필수입니다.");
        Objects.requireNonNull(buyerUserId, "구매자 ID는 필수입니다.");
        Objects.requireNonNull(inquiryId, "문의 ID는 필수입니다.");
        orderObjectionWithdrawUseCase.withdrawObjection(orderItemId, buyerUserId, inquiryId);
    }
}
