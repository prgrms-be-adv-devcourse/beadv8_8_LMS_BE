package com.hapbang.lecture.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.hapbang.shared.billing.dto.HoldQueryCommand;
import com.hapbang.shared.billing.dto.HoldStatePageDto;

/**
 * 서비스 간 HP 대사 설정 ({@code lecture.hp-reconciliation.*}, #580, D74). 값을 주지 않으면 기본값이다.
 * 실행 주기는 {@code lecture.batch.hp-reconciliation-cron}(기본 매일 04:30:25)으로 배치가 읽는다.
 *
 * @param settleGrace 진행 중으로 보고 빼는 시간. 주문의 HP 작업이나 항목의 구매·취소·반환·지급 시각이 이 시간 안이면 비교하지 않는다.
 *                    HP 작업 재시도 최대 간격(1시간)보다 길게 둔다. 기본 2시간
 * @param lookback    강의 쪽 비교 범위. 이 기간 안에 HP 작업이 바뀐 주문 항목을 본다. 지갑 쪽에서 바뀐 보류는 커서로 따로 모은다. 기본 7일
 * @param pageSize    지갑 조회 한 번의 건수 (1~500). 기본 500
 */
@ConfigurationProperties("lecture.hp-reconciliation")
public record LectureHpReconciliationProperties(Duration settleGrace, Duration lookback, Integer pageSize) {

    public LectureHpReconciliationProperties {
        settleGrace = settleGrace == null ? Duration.ofHours(2) : settleGrace;
        lookback = lookback == null ? Duration.ofDays(7) : lookback;
        pageSize = pageSize == null ? 500 : pageSize;
        if (settleGrace.isNegative()) {
            throw new IllegalArgumentException("대사 진행 중 유예 시간은 0 이상이어야 합니다.");
        }
        if (lookback.compareTo(settleGrace) <= 0) {
            throw new IllegalArgumentException("대사 비교 범위는 진행 중 유예 시간보다 길어야 합니다.");
        }
        int max = Math.min(HoldQueryCommand.MAX_REFERENCE_IDS, HoldStatePageDto.MAX_SIZE);
        if (pageSize < 1 || pageSize > max) {
            throw new IllegalArgumentException("대사 지갑 조회 건수는 1~" + max + "이어야 합니다.");
        }
    }
}
