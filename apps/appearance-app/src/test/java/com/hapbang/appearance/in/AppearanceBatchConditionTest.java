package com.hapbang.appearance.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.hapbang.appearance.app.AppearanceAutoConfirmUseCase;
import com.hapbang.appearance.app.AppearanceCancelAutoRejectUseCase;
import com.hapbang.appearance.app.AppearanceHoldRecheckUseCase;
import com.hapbang.appearance.app.AppearanceEventRepublishUseCase;
import com.hapbang.appearance.app.AppearanceRecruitDeadlineCloseUseCase;
import com.hapbang.appearance.app.AppearanceRequestExpireUseCase;
import com.hapbang.appearance.app.HpTaskRetryUseCase;

/**
 * 출연 배치 빈은 팀 공통 스위치({@code hapbang.scheduling.enabled=true})일 때만 생긴다 (#157).
 * 모듈·apps 테스트에서는 꺼져 있어 배치가 저절로 돌지 않는다.
 */
class AppearanceBatchConditionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(AppearanceHoldRecheckUseCase.class, () -> mock(AppearanceHoldRecheckUseCase.class))
            .withBean(AppearanceRecruitDeadlineCloseUseCase.class, () -> mock(AppearanceRecruitDeadlineCloseUseCase.class))
            .withBean(AppearanceRequestExpireUseCase.class, () -> mock(AppearanceRequestExpireUseCase.class))
            .withBean(AppearanceCancelAutoRejectUseCase.class, () -> mock(AppearanceCancelAutoRejectUseCase.class))
            .withBean(HpTaskRetryUseCase.class, () -> mock(HpTaskRetryUseCase.class))
            .withBean(AppearanceAutoConfirmUseCase.class, () -> mock(AppearanceAutoConfirmUseCase.class))
            .withBean(AppearanceEventRepublishUseCase.class, () -> mock(AppearanceEventRepublishUseCase.class))
            .withUserConfiguration(AppearanceBatch.class);

    @Test
    @DisplayName("스위치가 없거나 false면 배치 빈이 없다")
    void disabledByDefault() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(AppearanceBatch.class));
        contextRunner.withPropertyValues("hapbang.scheduling.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(AppearanceBatch.class));
    }

    @Test
    @DisplayName("스위치가 true면 배치 빈이 생긴다")
    void enabled() {
        contextRunner.withPropertyValues("hapbang.scheduling.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(AppearanceBatch.class));
    }
}
