package com.hapbang.lecture.app;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.out.LectureOrderRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 자동 구매 확정 (#149, D41). 구매일 다음 날부터 7일이 지난 {@code PURCHASED} 항목을 {@code AUTO}로 확정한다.
 * HP는 움직이지 않는다(지급은 구매일 D+30 배치).
 * <ul>
 *     <li>항목마다 새 트랜잭션을 연다. 같은 클래스 안 호출이라 {@code @Transactional} 대신 {@link TransactionTemplate}을 쓴다.</li>
 *     <li>예외는 트랜잭션 밖에서 항목별로 잡아 로그를 남기고 다음 항목으로 넘어간다. 실패한 항목은 아직 대상 조건에 맞으므로
 *     다음 실행에서 다시 처리된다 (설계 6.4).</li>
 * </ul>
 */
@Slf4j
@Service
public class OrderAutoConfirmUseCase {

    private final LectureOrderRepository lectureOrderRepository;
    private final LectureOrderSupport lectureOrderSupport;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public OrderAutoConfirmUseCase(LectureOrderRepository lectureOrderRepository,
                                   LectureOrderSupport lectureOrderSupport,
                                   PlatformTransactionManager transactionManager,
                                   @Qualifier(LectureConfig.CLOCK) Clock clock) {
        this.lectureOrderRepository = lectureOrderRepository;
        this.lectureOrderSupport = lectureOrderSupport;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public void confirmDueItems() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<Long> itemIds = lectureOrderRepository.findAutoConfirmDueItemIds(now);
        int failed = 0;
        for (Long itemId : itemIds) {
            try {
                transaction.executeWithoutResult(status ->
                        lectureOrderSupport.findOrderByItemId(itemId).autoConfirmItem(itemId, now));
            } catch (RuntimeException exception) {
                failed++;
                log.warn("자동 구매 확정 실패, 다음 실행에서 다시 처리한다 orderItemId={}", itemId, exception);
            }
        }
        if (!itemIds.isEmpty()) {
            log.info("자동 구매 확정 대상 {}건, 실패 {}건", itemIds.size(), failed);
        }
    }
}
