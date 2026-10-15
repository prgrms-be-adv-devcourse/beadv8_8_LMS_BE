package com.hapbang.lecture.app;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 지급 격리 해제 (관리자, D54). 격리 시각만 비우고 바로 지급하지 않는다. 다음 지급 배치가 같은 지급 요청 ID로 다시 시도한다.
 * 원인(남은 지급 예정 건 등)은 billing 데이터에서 먼저 고친 뒤 해제한다. 주문을 닫지 않으므로 항목 낙관적 락만 쓴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderPayoutReleaseUseCase {

    private final LectureOrderSupport lectureOrderSupport;

    public void release(Long adminUserId, Long orderItemId) {
        lectureOrderSupport.findOrderByItemId(orderItemId).releasePayoutQuarantine(orderItemId);
        log.info("지급 격리를 해제했다. 다음 지급 배치에서 다시 시도한다 orderItemId={}, adminUserId={}", orderItemId,
                adminUserId);
    }
}
