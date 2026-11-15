package com.hapbang.auxiliary.chatbot.app;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 싱크 설정 {@code hapbang.chatbot.sync} (#716, #717). 실행 시각(cron)은 배치 빈의 {@code @Scheduled} 플레이스홀더에 있다
 * ({@code hapbang.chatbot.sync.cron.<종류>}, 종류마다 매시).
 *
 * @param pageSize          원본 키셋 API 한 번에 읽는 개수 (출연 내부 API 최대 500)
 * @param warningRatio      경고 비율. {@code (넣은 건수 + 고친 건수) / 읽은 건수}가 이 값 이상이면 경고. 분모가 전체 스캔이라 실행 주기와 관계없다
 * @param warningMinChanged 경고 최소 건수. 넣은 건수 + 고친 건수가 이보다 작으면 비율이 커도 경고하지 않는다(작은 데이터의 소음)
 * @param mismatchMaxRows   회차당 남기는 어긋남 행 최대 개수. 넘는 것은 건수만 남는다
 * @param mismatchKeepRuns  종류마다 어긋남 행을 남기는 최근 회차 수(기본 168 = 매시 일주일)
 * @param runRetention      회차 기록 보관 기간. 지나면 그 회차와 어긋남 행을 지운다(기본 30일)
 * @param failureAlertAfter 그 종류가 이 시간 동안 성공하지 못했으면 실패를 ERROR로 알린다. 한두 번의 실패는 다음 정시가 다시 한다
 */
@ConfigurationProperties("hapbang.chatbot.sync")
public record ChatbotSyncProperties(
        @DefaultValue("500") int pageSize,
        @DefaultValue("0.3") double warningRatio,
        @DefaultValue("10") long warningMinChanged,
        @DefaultValue("100") int mismatchMaxRows,
        @DefaultValue("168") int mismatchKeepRuns,
        @DefaultValue("30d") Duration runRetention,
        @DefaultValue("3h") Duration failureAlertAfter
) {

    public ChatbotSyncProperties {
        if (pageSize < 1 || pageSize > 500) {
            throw new IllegalArgumentException("hapbang.chatbot.sync.page-size는 1~500이어야 합니다: " + pageSize);
        }
        if (warningRatio <= 0 || warningRatio > 1) {
            throw new IllegalArgumentException("hapbang.chatbot.sync.warning-ratio는 0보다 크고 1 이하여야 합니다: " + warningRatio);
        }
        if (warningMinChanged < 0 || mismatchMaxRows < 0 || mismatchKeepRuns < 1) {
            throw new IllegalArgumentException("hapbang.chatbot.sync 건수 설정이 올바르지 않습니다.");
        }
        if (runRetention.compareTo(Duration.ofDays(1)) < 0 || failureAlertAfter.isNegative() || failureAlertAfter.isZero()) {
            throw new IllegalArgumentException("hapbang.chatbot.sync 기간 설정이 올바르지 않습니다(run-retention 1일 이상, "
                    + "failure-alert-after 0보다 큼).");
        }
    }
}
