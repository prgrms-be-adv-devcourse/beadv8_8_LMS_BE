package com.hapbang.appearance.out.event;

import static org.assertj.core.api.Assertions.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.expression.ParserContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.modulith.events.EventExternalizationConfiguration;
import org.springframework.modulith.events.Externalized;

import com.hapbang.appearance.app.AppearanceExternalEvents;
import com.hapbang.shared.appearance.event.AppearanceAbsentEvent;
import com.hapbang.shared.appearance.event.AppearanceApplicationNotSelectedEvent;
import com.hapbang.shared.appearance.event.AppearanceApplicationSelectedEvent;
import com.hapbang.shared.appearance.event.AppearanceCanceledByHostEvent;
import com.hapbang.shared.appearance.event.AppearanceConfirmedEvent;
import com.hapbang.shared.appearance.event.AppearanceEventTopic;
import com.hapbang.shared.appearance.event.AppearanceRecruitCanceledEvent;
import com.hapbang.shared.appearance.event.AppearanceRecruitClosedEvent;
import com.hapbang.shared.appearance.event.AppearanceRecruitOpenedEvent;
import com.hapbang.shared.appearance.event.AppearanceRequestAcceptedEvent;
import com.hapbang.shared.appearance.event.AppearanceRequestCanceledEvent;
import com.hapbang.shared.appearance.event.AppearanceRequestExpiredEvent;
import com.hapbang.shared.appearance.event.AppearanceRequestRejectedEvent;

/**
 * 내보내는 출연 이벤트마다 {@code eventType}·{@code eventVersion} 헤더가 붙는지 확인한다 (#871).
 * 받는 쪽은 {@code eventType}으로 종류를 가르는데, shared 레코드에 {@code @Externalized}만 붙이고 헤더 등록을 빠뜨리면
 * 헤더 없이 나간다. 새 이벤트를 더할 때 이 테스트가 빠뜨린 것을 잡는다.
 * Kafka key 식({@code @Externalized}의 {@code ::} 뒤)도 Kafka 없이 평가한다. 접근자 이름이 틀리면 컴파일은 되고
 * 커밋 뒤 발행 단계에서야 실패하기 때문이다.
 */
class AppearanceEventExternalizationConfigTest {

    /** key 식이 읽는 대상 ID. 이벤트 안의 모든 Long 값을 이 값으로 채운다 */
    private static final long TARGET_ID = 7L;

    /** 알림용 12종(#871)의 key 접두어. 알림센터가 예약 짝 키를 이 key·ID로 맞춘다 */
    private static final Map<Class<?>, String> NOTIFICATION_KEY_PREFIXES = Map.ofEntries(
            Map.entry(AppearanceRequestAcceptedEvent.class, "appearance-request:"),
            Map.entry(AppearanceRequestRejectedEvent.class, "appearance-request:"),
            Map.entry(AppearanceRequestCanceledEvent.class, "appearance-request:"),
            Map.entry(AppearanceRequestExpiredEvent.class, "appearance-request:"),
            Map.entry(AppearanceApplicationSelectedEvent.class, "appearance-application:"),
            Map.entry(AppearanceApplicationNotSelectedEvent.class, "appearance-application:"),
            Map.entry(AppearanceRecruitOpenedEvent.class, "appearance-recruit:"),
            Map.entry(AppearanceRecruitClosedEvent.class, "appearance-recruit:"),
            Map.entry(AppearanceRecruitCanceledEvent.class, "appearance-recruit:"),
            Map.entry(AppearanceCanceledByHostEvent.class, "appearance:"),
            Map.entry(AppearanceConfirmedEvent.class, "appearance:"),
            Map.entry(AppearanceAbsentEvent.class, "appearance:"));

    @Test
    @DisplayName("shared 출연 이벤트 중 @Externalized가 붙은 모든 레코드에 eventType(레코드 이름)·eventVersion 헤더가 붙는다")
    void everyExternalizedEventHasHeaders() throws Exception {
        EventExternalizationConfiguration configuration =
                new AppearanceEventExternalizationConfig().appearanceEventExternalization();
        List<Class<?>> types = externalizedEventTypes();

        // 지금까지 19종: 요청 도착·지원 도착·리뷰 공개·출연 변경·취소 요청 3종 + 알림용 12종(#871)
        assertThat(types).hasSize(19);
        for (Class<?> type : types) {
            Object event = emptyRecord(type);
            assertThat(AppearanceExternalEvents.isExternalized(event)).as(type.getSimpleName()).isTrue();
            assertThat(configuration.getHeadersFor(event)).as(type.getSimpleName()).containsEntry(
                    AppearanceEventExternalizationConfig.EVENT_TYPE, type.getSimpleName())
                    .containsEntry(AppearanceEventExternalizationConfig.EVENT_VERSION,
                            AppearanceEventExternalizationConfig.VERSION);
        }
    }

    @Test
    @DisplayName("@Externalized 출연 이벤트 19종의 key 식이 모두 평가되고 대상 ID로 끝난다. 알림용 12종은 정한 접두어를 쓴다")
    void everyExternalizedEventKeyEvaluates() throws Exception {
        SpelExpressionParser parser = new SpelExpressionParser();
        List<Class<?>> types = externalizedEventTypes();

        assertThat(types).containsAll(NOTIFICATION_KEY_PREFIXES.keySet());
        for (Class<?> type : types) {
            String target = type.getAnnotation(Externalized.class).value();
            assertThat(target).as(type.getSimpleName()).startsWith(AppearanceEventTopic.NAME + "::");
            String keyExpression = target.substring(target.indexOf("::") + 2);

            String key = parser.parseExpression(keyExpression, ParserContext.TEMPLATE_EXPRESSION)
                    .getValue(new StandardEvaluationContext(filledRecord(type)), String.class);

            assertThat(key).as(type.getSimpleName()).endsWith(":" + TARGET_ID);
            if (NOTIFICATION_KEY_PREFIXES.containsKey(type)) {
                assertThat(key).as(type.getSimpleName()).isEqualTo(NOTIFICATION_KEY_PREFIXES.get(type) + TARGET_ID);
            }
        }
    }

    private static List<Class<?>> externalizedEventTypes() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Externalized.class));
        List<Class<?>> types = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents(AppearanceEventTopic.class.getPackageName())) {
            types.add(Class.forName(candidate.getBeanClassName()));
        }
        return types;
    }

    /**
     * key 식을 평가할 값을 채운 레코드. ID(Long)는 {@link #TARGET_ID}, 안에 든 레코드(DTO)는 같은 방식으로 채운다.
     * 그 밖의 값은 key와 관계없으므로 문자열·시각·enum만 채우고 나머지는 비워 둔다
     */
    private static Object filledRecord(Class<?> type) throws ReflectiveOperationException {
        RecordComponent[] components = type.getRecordComponents();
        Class<?>[] parameterTypes = new Class<?>[components.length];
        Object[] arguments = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            Class<?> componentType = components[i].getType();
            parameterTypes[i] = componentType;
            arguments[i] = filledValue(componentType);
        }
        return type.getDeclaredConstructor(parameterTypes).newInstance(arguments);
    }

    private static Object filledValue(Class<?> type) throws ReflectiveOperationException {
        if (type == Long.class || type == long.class) {
            return TARGET_ID;
        }
        if (type == Integer.class || type == int.class) {
            return 0;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == String.class) {
            return "제목";
        }
        if (type == LocalDateTime.class) {
            return LocalDateTime.of(2026, 11, 12, 20, 0);
        }
        if (type.isEnum()) {
            return type.getEnumConstants()[0];
        }
        if (type.isRecord()) {
            return filledRecord(type);
        }
        return null;
    }

    /** 헤더는 타입으로만 정하므로 값은 비워 둔다 (원시 타입은 0·false) */
    private static Object emptyRecord(Class<?> type) throws ReflectiveOperationException {
        RecordComponent[] components = type.getRecordComponents();
        Class<?>[] parameterTypes = new Class<?>[components.length];
        Object[] arguments = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            Class<?> componentType = components[i].getType();
            parameterTypes[i] = componentType;
            if (componentType == boolean.class) {
                arguments[i] = false;
            } else if (componentType == int.class) {
                arguments[i] = 0;
            } else if (componentType == long.class) {
                arguments[i] = 0L;
            }
        }
        Constructor<?> constructor = type.getDeclaredConstructor(parameterTypes);
        return constructor.newInstance(arguments);
    }
}
