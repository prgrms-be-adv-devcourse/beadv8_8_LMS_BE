package com.hapbang.appearance.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;

import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.observation.ObservationPredicate;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
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
 * </ul>
 * 업무 요청·배치 안에서 시작된 것은 그 trace 아래에 그대로 남는다.
 */
@Configuration(proxyBeanMethods = false)
public class ObservationConfig {

    @Bean
    public ObservationPredicate noRootInfrastructureObservation() {
        return (name, context) -> context.getParentObservation() != null || !name.startsWith("spring.security.");
    }

    @Bean
    public SpanExportingPredicate noRootRedisSpan() {
        return span -> SpanId.isValid(span.getParentId()) || !"redis".equals(span.getTags().get("db.system"));
    }

    /**
     * 비동기 작업에 작업을 넘긴 스레드의 trace를 이어 준다 (#630). Modulith 이벤트 외부화({@code @ApplicationModuleListener})가
     * 커밋 뒤 다른 스레드에서 Kafka로 보내므로, 이것이 없으면 발행이 요청·소비의 trace에서 끊긴다.
     * <p>
     * Boot가 앱의 {@code taskScheduler}(@Async가 쓰는 executor) builder에 적용한다. Observation만 넘긴다. Boot의
     * {@code spring.task.execution.propagate-context}는 등록된 전파기를 모두 넘겨 Spring Security의 인증 정보까지
     * 비동기 리스너로 넘어가므로 쓰지 않는다.
     */
    @Bean
    public TaskDecorator traceContextTaskDecorator() {
        return new ContextPropagatingTaskDecorator(ContextSnapshotFactory.builder()
                .captureKeyPredicate(ObservationThreadLocalAccessor.KEY::equals)
                .build());
    }
}
