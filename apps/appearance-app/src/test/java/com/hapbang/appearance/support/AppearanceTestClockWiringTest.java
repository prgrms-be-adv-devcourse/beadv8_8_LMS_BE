package com.hapbang.appearance.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.AopTestUtils;
import org.springframework.util.ReflectionUtils;

import com.hapbang.appearance.config.AppearanceConfig;
import com.hapbang.appearance.shared.time.AppearanceTime;

/**
 * 출연 통합 테스트에 테스트 시계가 제대로 끼워지는지 (#297).
 * 운영 Clock 빈은 그대로 있고, {@code @Qualifier(CLOCK)}로 주입받는 곳(UseCase·테스트)은 모두 테스트 시계를 받는다.
 * 테스트 순서를 정해, 앞 테스트에서 앞당긴 시계가 다음 테스트에 이어지지 않는지도 본다.
 */
@AppearanceIntegrationTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AppearanceTestClockWiringTest {

    @Autowired
    ApplicationContext applicationContext;

    @Autowired
    @Qualifier(AppearanceConfig.CLOCK)
    Clock qualifiedClock;

    @Autowired
    AppearanceTestClock testClock;

    @Test
    @Order(1)
    @DisplayName("한정자로 주입받는 Clock은 테스트 시계이고, 시각을 쓰는 출연 빈(UseCase 20개 이상)도 모두 같은 시계를 받는다. 운영 Clock 빈은 그대로 남아 있다")
    void injected() {
        assertThat(qualifiedClock).isSameAs(testClock);
        List<String> beansWithClock = new ArrayList<>();
        for (String name : applicationContext.getBeanDefinitionNames()) {
            Class<?> type = applicationContext.getType(name);
            if (type == null || !type.getPackageName().startsWith("com.hapbang.appearance")) {
                continue;
            }
            Object bean = AopTestUtils.getUltimateTargetObject(applicationContext.getBean(name));
            Field field = ReflectionUtils.findField(bean.getClass(), "clock", Clock.class);
            if (field != null) {
                ReflectionUtils.makeAccessible(field);
                assertThat(ReflectionUtils.getField(field, bean)).as(name).isSameAs(testClock);
                beansWithClock.add(name);
            }
        }
        assertThat(beansWithClock).hasSizeGreaterThanOrEqualTo(20); // 시각을 쓰는 UseCase 전부
        assertThat(applicationContext.getBean(AppearanceConfig.CLOCK, Clock.class)).isNotSameAs(testClock);
    }

    @Test
    @Order(2)
    @DisplayName("앞당기면 실제 시각보다 그만큼 뒤다 (그 뒤로도 계속 흐른다)")
    void advance() {
        testClock.advance(Duration.ofDays(10));

        LocalDateTime real = LocalDateTime.now(AppearanceTime.ZONE);
        assertThat(testClock.now()).isBetween(real.plusDays(10).minusSeconds(1), real.plusDays(10).plusSeconds(5));
    }

    @Test
    @Order(3)
    @DisplayName("다음 테스트가 시작되면 처음 시각으로 돌아가 있다 (앞 테스트의 10일이 이어지지 않음)")
    void resetBetweenTests() {
        LocalDateTime real = LocalDateTime.now(AppearanceTime.ZONE);
        assertThat(testClock.now()).isBetween(real.minusSeconds(1), real.plusSeconds(5));
    }
}
