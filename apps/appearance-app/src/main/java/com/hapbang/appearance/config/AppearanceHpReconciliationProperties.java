package com.hapbang.appearance.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.hapbang.shared.billing.dto.HoldQueryCommand;
import com.hapbang.shared.billing.dto.HoldStatePageDto;

/**
 * 서비스 간 HP 대사 설정 ({@code appearance.hp-reconciliation.*}, #581). 실행 주기는 {@code appearance.batch.hp-reconciliation-cron}이다.
 *
 * @param settleGrace 진행 중으로 보고 빼는 시간. 요청서·모집글, 그 출연, 그 HP 작업 중 하나라도 이 시간 안에 바뀌었으면 비교하지 않는다.
 *                    HP 작업 재시도 최대 간격(1시간)과 보류 포기 기준(1시간)보다 길게 둔다
 * @param lookback    출연 쪽 비교 범위. 이 기간 안에 바뀐 요청서·모집글(그 출연·HP 작업 포함)을 본다. 지갑 쪽에서 바뀐 보류는 커서로 따로 모은다
 * @param pageSize    지갑 조회 한 번의 건수 (1~500)
 * @param abandonAfter 실행 중({@code RUNNING})으로 이보다 오래 남은 실행은 앱이 멈춰 끝나지 못한 것으로 보고 다음 실행이 시작될 때 정리한다(#821).
 *                    배포 중에는 옛 pod와 새 pod가 잠깐 함께 떠 있으므로(maxSurge 1), 옛 pod가 하고 있는 실행을 정리하지 않도록 대사 한 번의 최대 소요 시간보다 길게 둔다
 */
@ConfigurationProperties(prefix = "appearance.hp-reconciliation")
public record AppearanceHpReconciliationProperties(
        Duration settleGrace,
        Duration lookback,
        int pageSize,
        Duration abandonAfter
) {

    public AppearanceHpReconciliationProperties {
        if (settleGrace == null || settleGrace.isNegative()) {
            throw new IllegalArgumentException("대사 진행 중 유예 시간은 0 이상이어야 합니다.");
        }
        if (lookback == null || lookback.compareTo(settleGrace) <= 0) {
            throw new IllegalArgumentException("대사 비교 범위는 진행 중 유예 시간보다 길어야 합니다.");
        }
        int max = Math.min(HoldQueryCommand.MAX_REFERENCE_IDS, HoldStatePageDto.MAX_SIZE);
        if (pageSize < 1 || pageSize > max) {
            throw new IllegalArgumentException("대사 지갑 조회 건수는 1~" + max + "이어야 합니다.");
        }
        if (abandonAfter == null || abandonAfter.isNegative() || abandonAfter.isZero()) {
            throw new IllegalArgumentException("멈춘 대사 실행을 정리하는 기준 시간은 0보다 커야 합니다.");
        }
    }
}
