package com.hapbang.lecture.app;

import org.springframework.stereotype.Service;

import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.shared.lecture.exception.LectureObjectionRefusedException;
import com.hapbang.shared.lecture.exception.LectureObjectionRefusedException.Reason;

import lombok.RequiredArgsConstructor;

/**
 * 판단 전 이의 제기 철회 (#203, D52). 고객센터의 문의 삭제와 같은 트랜잭션에서 항목을 접수 전 상태(NONE)로 되돌려 지급 중단을 푼다.
 * HP는 움직이지 않고 주문을 닫지 않으므로 주문을 잠그지 않는다. 같은 항목을 바꾸는 판단·구매 취소와는 항목의 낙관적 락으로
 * 직렬화된다 (설계 10장).
 */
@Service
@RequiredArgsConstructor
public class OrderObjectionWithdrawUseCase {

    private final LectureOrderRepository lectureOrderRepository;

    public void withdrawObjection(Long orderItemId, Long buyerUserId, Long inquiryId) {
        // 없는 항목은 남의 항목과 같게 본다 (D32)
        LectureOrder order = lectureOrderRepository.findByItemId(orderItemId)
                .orElseThrow(() -> new LectureObjectionRefusedException(Reason.NOT_BUYER));
        order.withdrawObjection(orderItemId, buyerUserId, inquiryId);
        // 같은 항목을 동시에 바꾼 변경(판단 등)과의 충돌을 이 호출 안에서 드러낸다
        lectureOrderRepository.flush();
    }
}
