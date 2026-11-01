package com.hapbang.lecture.app;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.domain.LectureErrorCode;
import com.hapbang.lecture.domain.LectureException;
import com.hapbang.lecture.domain.LectureHpTask;
import com.hapbang.lecture.domain.LectureHpTaskPurpose;
import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.domain.OrderItem;
import com.hapbang.lecture.out.LectureOrderRepository;

/**
 * 이의 제기 판단 (설계 6.4, D59 (1)). 호출한 쪽(고객센터) 트랜잭션에서 판단을 기록한다.
 * 인정하면 항목을 반환 처리 중으로 바꾸고 RELEASE 작업을 함께 등록한다. 반환은 HP 작업 재시도 배치가 하고,
 * 반환이 끝나면 항목이 반환이 된다 (D70 (1)). 반환은 billing이 차감했던 HP 종류 그대로 한다 (D42).
 * 기각은 항목을 그대로 두고, 지급 예정 시각이 지나면 지급 배치가 지급을 시작한다.
 */
@Service
public class OrderObjectionResolveUseCase {

    /** 반환 요청 ID. 항목은 한 번만 반환되므로 항목 ID로 정해진다 (D43) */
    static final String RELEASE_REQUEST_ID_PREFIX = "LECTURE-RETURN-";

    private final LectureOrderSupport lectureOrderSupport;
    private final LectureOrderRepository lectureOrderRepository;
    private final LectureHpTaskSupport lectureHpTaskSupport;
    private final Clock clock;

    public OrderObjectionResolveUseCase(LectureOrderSupport lectureOrderSupport,
                                        LectureOrderRepository lectureOrderRepository,
                                        LectureHpTaskSupport lectureHpTaskSupport,
                                        @Qualifier(LectureConfig.CLOCK) Clock clock) {
        this.lectureOrderSupport = lectureOrderSupport;
        this.lectureOrderRepository = lectureOrderRepository;
        this.lectureHpTaskSupport = lectureHpTaskSupport;
        this.clock = clock;
    }

    public void resolveObjection(Long orderItemId, Long inquiryId, boolean accepted, Long adminUserId) {
        LocalDateTime now = LocalDateTime.now(clock);
        // 인정은 반환 완료 때 주문을 닫으므로 구매 취소처럼 주문을 잠근다 (D46). 기각은 주문을 닫지 않아 항목의 낙관적 락만 쓴다 (D49)
        Optional<LectureOrder> found = accepted
                ? lectureOrderSupport.findOrderForUpdateByItemId(orderItemId)
                : lectureOrderRepository.findByItemId(orderItemId);
        // 없는 항목은 판단할 이의 제기가 없는 것이다
        LectureOrder order = found.orElseThrow(() -> new LectureException(LectureErrorCode.OBJECTION_NOT_FILED));
        OrderItem item = order.resolveObjection(orderItemId, inquiryId, accepted, adminUserId, now);
        // 같은 항목을 동시에 바꾼 변경(낙관적 락 충돌)을 작업 등록 전에 확인한다 (설계 6.2)
        lectureOrderRepository.flush();
        if (!accepted) {
            return;
        }
        lectureHpTaskSupport.register(LectureHpTask.create(LectureHpTaskPurpose.RETURN,
                RELEASE_REQUEST_ID_PREFIX + item.getId(), order.getId(), item.getId(), item.getBuyerUserId(), null,
                item.getPriceHp(), now));
    }
}
