package com.hapbang.lecture.config;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;

import org.junit.jupiter.api.Test;

// 서비스 간 HP 대사 설정 (#580, D74): 기본값과 기동 시 검증
class LectureHpReconciliationPropertiesTest {

    @Test
    void 값을_주지_않으면_유예_2시간_비교_범위_7일_조회_500건이다() {
        LectureHpReconciliationProperties properties = new LectureHpReconciliationProperties(null, null, null);

        assertThat(properties.settleGrace()).isEqualTo(Duration.ofHours(2));
        assertThat(properties.lookback()).isEqualTo(Duration.ofDays(7));
        assertThat(properties.pageSize()).isEqualTo(500);
    }

    @Test
    void 유예보다_짧은_비교_범위나_1_500_밖의_조회_건수로는_기동하지_않는다() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new LectureHpReconciliationProperties(Duration.ofHours(2), Duration.ofHours(2), 500));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new LectureHpReconciliationProperties(Duration.ofHours(-1), null, 500));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new LectureHpReconciliationProperties(null, null, 0));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new LectureHpReconciliationProperties(null, null, 501));
    }
}
