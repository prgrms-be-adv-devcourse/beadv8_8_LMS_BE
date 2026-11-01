package com.hapbang.lecture.app;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.domain.LectureErrorCode;
import com.hapbang.lecture.domain.LectureException;
import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.out.LectureOrderRepository;

/**
 * 내용 다름 이의 제기 접수 (설계 6.4). 고객센터의 문의 저장과 같은 트랜잭션에서 항목을 FILED로 바꿔 지급·구매 취소를 멈춘다 (D9).
 * 주문을 닫지 않으므로 주문을 잠그지 않는다. 같은 항목을 바꾸는 지급 배치·구매 취소와는 항목의 낙관적 락으로 직렬화된다 (설계 10장).
 */
@Service
public class OrderObjectionFileUseCase {

    private final LectureOrderRepository lectureOrderRepository;
    private final Clock clock;

    public OrderObjectionFileUseCase(LectureOrderRepository lectureOrderRepository,
                                     @Qualifier(LectureConfig.CLOCK) Clock clock) {
        this.lectureOrderRepository = lectureOrderRepository;
        this.clock = clock;
    }

    public void fileObjection(Long orderItemId, Long buyerUserId, Long inquiryId, String reason) {
        // 없는 항목은 남의 항목과 같게 본다 (D32)
        LectureOrder order = lectureOrderRepository.findByItemId(orderItemId)
                .orElseThrow(() -> new LectureException(LectureErrorCode.OBJECTION_NOT_BUYER));
        order.fileObjection(orderItemId, buyerUserId, inquiryId, reason, LocalDateTime.now(clock));
        // 같은 항목을 동시에 바꾼 변경(지급 배치 등)과의 충돌을 이 호출 안에서 드러낸다
        lectureOrderRepository.flush();
    }
}
