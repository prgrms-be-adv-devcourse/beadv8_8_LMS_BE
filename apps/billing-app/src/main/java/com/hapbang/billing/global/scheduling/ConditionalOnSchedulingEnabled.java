package com.hapbang.billing.global.scheduling;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * {@code @Scheduled} 배치 빈은 {@code hapbang.scheduling.enabled=true}일 때만 만든다 (#157).
 * <p>
 * spring-modulith-moments가 {@code @EnableScheduling}을 이미 켜 두었기 때문에, 배치 빈이 있으면 테스트에서도 실행된다.
 * 그래서 배치마다 이 애노테이션으로 실행 여부를 정한다. 앱(main {@code application.yaml})에서는 켜고,
 * apps 테스트({@code src/test/resources/config/application.yaml})에서는 끈다. 모듈 테스트는 apps yaml을 읽지 않으므로 꺼진다.
 * <ul>
 *     <li>이 애노테이션을 붙인 얇은 클래스에만 {@code @Scheduled}를 두고, 처리는 다른 빈에 맡긴다. 테스트는 그 빈의 메서드를 직접 호출한다.</li>
 *     <li>주기는 플레이스홀더에 코드 기본값을 함께 쓴다. 예: {@code @Scheduled(cron = "${lecture.batch.payout-cron:0 0 1 * * *}", zone = "Asia/Seoul")}</li>
 *     <li>cron에는 {@code zone = "Asia/Seoul"}을 반드시 쓴다. 모든 기간·일시는 KST 기준이다.</li>
 *     <li>항목마다 별도 트랜잭션으로 처리하고, 예외는 트랜잭션 밖에서 항목별로 잡는다(강의 설계 6.4).</li>
 * </ul>
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnProperty(name = "hapbang.scheduling.enabled", havingValue = "true")
public @interface ConditionalOnSchedulingEnabled {
}
