package com.hapbang.auxiliary.chatbot.app;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 주간 싱크 설정 {@code hapbang.chatbot.sync} (#716). 실행 시각(cron)은 배치 빈의 {@code @Scheduled} 플레이스홀더에 있다
 * ({@code hapbang.chatbot.sync.weekly-cron}, {@code hapbang.chatbot.sync.retry-cron}).
 *
 * @param pageSize          원본 키셋 API 한 번에 읽는 개수 (출연 내부 API 최대 500)
 * @param warningRatio      경고 비율. {@code (넣은 건수 + 고친 건수) / 읽은 건수}가 이 값 이상이면 경고
 * @param warningMinChanged 경고 최소 건수. 넣은 건수 + 고친 건수가 이보다 작으면 비율이 커도 경고하지 않는다(작은 데이터의 소음)
 * @param mismatchMaxRows   회차당 남기는 어긋남 행 최대 개수. 넘는 것은 건수만 남는다
 * @param mismatchKeepRuns  종류마다 어긋남 행을 남기는 최근 회차 수
 */
@ConfigurationProperties("hapbang.chatbot.sync")
public record ChatbotSyncProperties(
        @DefaultValue("500") int pageSize,
        @DefaultValue("0.3") double warningRatio,
        @DefaultValue("10") long warningMinChanged,
        @DefaultValue("100") int mismatchMaxRows,
        @DefaultValue("10") int mismatchKeepRuns
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
    }
}
