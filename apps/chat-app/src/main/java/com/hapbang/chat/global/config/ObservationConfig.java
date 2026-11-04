package com.hapbang.chat.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;

import com.hapbang.chat.app.ChatPollingTasks;

import io.micrometer.observation.ObservationPredicate;
import io.micrometer.tracing.exporter.SpanExportingPredicate;
import io.opentelemetry.api.trace.SpanId;

/**
 * 업무 진입점이 아닌 곳에서 부모 없이 시작되는 observation·span이 trace를 만들지 않게 한다 (#631).
 * <ul>
 *     <li>{@code spring.security.*}: 관리 포트(actuator)에는 HTTP 요청 observation이 없어 Spring Security filter chain
 *     observation이 부모 없이 시작된다. 그대로 두면 probe(5초마다 3종)와 Prometheus 수집마다 root trace가 Tempo에 쌓인다.</li>
 *     <li>Redis 명령: Boot가 Lettuce 명령마다 span을 만든다(LettuceObservationAutoConfiguration). 요청·배치 밖의 명령(연결
 *     handshake, 구독·스트림 읽기 등)은 root span이 되므로 내보내지 않는다. Lettuce는 observation을 만든 뒤에 부모를 붙여서
 *     {@link ObservationPredicate}로는 부모 유무를 볼 수 없다. 그래서 끝난 span의 부모로 내보낼 때 거른다.</li>
 *     <li>짧은 주기 polling({@link ChatPollingTasks}): 1초·30초마다 도는 {@code @Scheduled} 실행(#631, #663)이 샘플링 비율만큼 root trace가
 *     되지 않게 한다. 1분 이상 주기의 배치는 그대로 trace를 만든다.</li>
 * </ul>
 * 업무 요청·배치 안에서 시작된 것은 그 trace 아래에 그대로 남는다.
 */
@Configuration(proxyBeanMethods = false)
public class ObservationConfig {

    @Bean
    public ObservationPredicate noRootInfrastructureObservation() {
        return (name, context) -> context.getParentObservation() != null
                || !(name.startsWith("spring.security.")
                || context instanceof ScheduledTaskObservationContext task && ChatPollingTasks.isPolling(task.getTargetClass()));
    }

    @Bean
    public SpanExportingPredicate noRootRedisSpan() {
        return span -> SpanId.isValid(span.getParentId()) || !"redis".equals(span.getTags().get("db.system"));
    }
}
