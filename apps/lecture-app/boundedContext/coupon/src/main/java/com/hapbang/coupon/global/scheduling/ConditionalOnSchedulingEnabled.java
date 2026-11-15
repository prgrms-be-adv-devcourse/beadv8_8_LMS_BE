package com.hapbang.coupon.global.scheduling;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * 쿠폰의 {@code @Scheduled} 배치 빈은 {@code hapbang.scheduling.enabled=true}일 때만 만든다. 강의 앱의 같은 이름 애노테이션과 같은
 * 설정을 보지만, 쿠폰은 강의 코드를 참조하지 않으므로 쿠폰 global에 따로 둔다.
 * <ul>
 *     <li>이 애노테이션을 붙인 얇은 클래스에만 {@code @Scheduled}를 두고, 처리는 UseCase 빈에 맡긴다. 테스트는 UseCase를 직접 호출한다.</li>
 *     <li>주기는 플레이스홀더에 코드 기본값을 함께 쓴다. 예: {@code @Scheduled(fixedDelayString = "${hapbang.coupon.republish.interval:PT1M}")}</li>
 *     <li>cron을 쓰면 {@code zone = "Asia/Seoul"}을 반드시 쓴다.</li>
 *     <li>항목(쿠폰)마다 예외를 잡아 한 항목의 실패가 회차 전체를 멈추지 않게 한다.</li>
 * </ul>
 * 앱(lecture-app {@code application.yaml})에서는 켜고, 모듈 테스트는 설정이 없어 꺼진다.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnProperty(name = "hapbang.scheduling.enabled", havingValue = "true")
public @interface ConditionalOnSchedulingEnabled {
}
