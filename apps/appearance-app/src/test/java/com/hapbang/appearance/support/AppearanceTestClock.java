package com.hapbang.appearance.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import com.hapbang.appearance.shared.time.AppearanceTime;

/**
 * 출연 통합 테스트의 시계 (#297). 운영과 같이 실제 시각으로 흐르되, 테스트가 시각을 앞으로 돌릴 수 있다.
 * <ul>
 *     <li>멈춘 시계가 아니라 실제 시각 + 앞당긴 만큼으로 흐른다. 연달아 만든 행의 시각이 같아져 순서가 흔들리지 않게 하기 위해서다.</li>
 *     <li><b>주의</b>: 생성 시각({@code created_at}, JPA Auditing)은 이 시계가 아니라 실제 시각으로 기록된다 (Auditing 설정은 팀 공통
 *     test-support). 그래서 시계를 앞당긴 <b>뒤에</b> 만든 행은 만들자마자 앞당긴 만큼 "오래된 행"이 되어, 생성 시각으로 거르는
 *     배치 대기 조건(보류 재확인·HP 작업 처음 시도의 1분)을 바로 넘는다. 대기 시간을 확인하는 테스트는 행을 먼저 만들고 시계를 돌린다.</li>
 *     <li>앞으로만 돌린다. 뒤로 돌리면 이미 기록한 시각보다 과거가 되어 운영에서 일어날 수 없는 상태가 된다.</li>
 *     <li>테스트마다 처음 시각(앞당김 없음)으로 돌아간다 ({@link ResetExtension}, {@link AppearanceIntegrationTest}에 등록).</li>
 * </ul>
 * 운영 Clock({@code AppearanceConfig.CLOCK})은 그대로이고, 테스트 설정({@link AppearanceTestClockConfig})이 이 시계를 우선 주입한다.
 */
public class AppearanceTestClock extends Clock {

    private final Clock base;
    private volatile Duration offset = Duration.ZERO;

    public AppearanceTestClock() {
        this(Clock.system(AppearanceTime.ZONE));
    }

    AppearanceTestClock(Clock base) {
        this.base = base;
    }

    /** 지금 시각 (출연 시간대) */
    public LocalDateTime now() {
        return LocalDateTime.now(this);
    }

    /** 시각을 앞으로 돌린다 */
    public void advance(Duration duration) {
        if (duration.isNegative()) {
            throw new IllegalArgumentException("테스트 시계는 앞으로만 돌릴 수 있습니다: " + duration);
        }
        offset = offset.plus(duration);
    }

    /** 지금이 target 바로 뒤(1밀리초 뒤)가 되도록 앞으로 돌린다. 이미 지났으면 그대로 둔다 */
    public void advanceTo(LocalDateTime target) {
        Duration remaining = Duration.between(now(), target);
        if (!remaining.isNegative()) {
            advance(remaining.plusMillis(1));
        }
    }

    /** 앞당긴 만큼을 없앤다 (테스트 시작 때 자동으로 부른다) */
    public void reset() {
        offset = Duration.ZERO;
    }

    @Override
    public ZoneId getZone() {
        return base.getZone();
    }

    /** 시간대만 바꾼 같은 시계. 앞당김은 원래 시계와 함께 움직인다 */
    @Override
    public Clock withZone(ZoneId zone) {
        AppearanceTestClock source = this;
        return new Clock() {
            @Override
            public ZoneId getZone() {
                return zone;
            }

            @Override
            public Clock withZone(ZoneId other) {
                return source.withZone(other);
            }

            @Override
            public Instant instant() {
                return source.instant();
            }
        };
    }

    @Override
    public Instant instant() {
        return base.instant().plus(offset);
    }

    /** 테스트마다 시계를 처음 시각으로 돌린다 (테스트의 {@code @BeforeEach}보다 먼저 실행된다) */
    public static class ResetExtension implements BeforeEachCallback {

        @Override
        public void beforeEach(ExtensionContext context) {
            SpringExtension.getApplicationContext(context).getBean(AppearanceTestClock.class).reset();
        }
    }
}
