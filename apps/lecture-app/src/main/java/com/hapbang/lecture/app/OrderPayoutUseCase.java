package com.hapbang.lecture.app;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.domain.LectureHpTask;
import com.hapbang.lecture.domain.LectureHpTaskPurpose;
import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.out.LectureOrderRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 지급 시작 (#150, D50·D59 (5)). 지급 예정 시각(구매일 다음 날부터 30일 경과)이 지나고 이의 제기가 없거나 기각된
 * {@code CONFIRMED} 항목을 지급 처리 중으로 바꾸고 PAYOUT 작업을 등록한다. 지갑은 부르지 않는다.
 * <ul>
 *     <li>항목마다 짧은 새 트랜잭션에서 상태 변경과 작업 등록을 함께 한다.</li>
 *     <li>지급(판매자 수익 HP로 옮김)과 재시도는 HP 작업 배치가 하고, 지급이 끝나야 {@code PAID_OUT}이다.
 *     실패는 작업에 남고 자동 처리가 멈추면 관리자가 HP 작업 관리자 API로 다시 시도한다 (D57, D70 (5)).</li>
 *     <li>항목 하나가 실패해도(같은 항목을 동시에 바꾼 경우 등) 다음 항목으로 넘어간다. 그 항목은 다음 실행에서 다시 고른다.</li>
 * </ul>
 */
@Slf4j
@Service
public class OrderPayoutUseCase {

    /** 지급 요청 ID. 항목은 한 번만 지급되므로 항목 ID로 정해진다 (D43, D50) */
    static final String PAYOUT_REQUEST_ID_PREFIX = "LECTURE-PAYOUT-";

    private final LectureOrderRepository lectureOrderRepository;
    private final LectureOrderSupport lectureOrderSupport;
    private final LectureHpTaskSupport lectureHpTaskSupport;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public OrderPayoutUseCase(LectureOrderRepository lectureOrderRepository,
                              LectureOrderSupport lectureOrderSupport,
                              LectureHpTaskSupport lectureHpTaskSupport,
                              PlatformTransactionManager transactionManager,
                              @Qualifier(LectureConfig.CLOCK) Clock clock) {
        this.lectureOrderRepository = lectureOrderRepository;
        this.lectureOrderSupport = lectureOrderSupport;
        this.lectureHpTaskSupport = lectureHpTaskSupport;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public void payOutDueItems() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<Long> itemIds = lectureOrderRepository.findPayoutDueItemIds(now);
        int failed = 0;
        for (Long itemId : itemIds) {
            try {
                transaction.executeWithoutResult(status -> startPayout(itemId, now));
            } catch (RuntimeException exception) {
                failed++;
                log.warn("지급을 시작하지 못했다, 다음 실행에서 다시 고른다 orderItemId={}", itemId, exception);
            }
        }
        if (!itemIds.isEmpty()) {
            log.info("지급 시작 대상 {}건, 실패 {}건", itemIds.size(), failed);
        }
    }

    private void startPayout(Long itemId, LocalDateTime now) {
        // 조회한 뒤 이의 제기가 먼저 접수되는 등 대상이 아니게 되었으면 그대로 둔다
        LectureOrder order = lectureOrderSupport.findOrderByItemId(itemId);
        order.startPayoutItem(itemId, now).ifPresent(item -> {
            // 같은 항목을 동시에 바꾼 요청(낙관적 락 충돌)을 작업 등록 전에 확인한다 (설계 6.2)
            lectureOrderRepository.flush();
            lectureHpTaskSupport.register(LectureHpTask.create(LectureHpTaskPurpose.PAYOUT,
                    PAYOUT_REQUEST_ID_PREFIX + item.getId(), order.getId(), item.getId(), item.getBuyerUserId(),
                    item.getSellerUserId(), item.getPriceHp(), now));
        });
    }
}
