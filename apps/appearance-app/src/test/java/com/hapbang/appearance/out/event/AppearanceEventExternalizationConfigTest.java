package com.hapbang.appearance.out.event;

import static org.assertj.core.api.Assertions.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.modulith.events.EventExternalizationConfiguration;
import org.springframework.modulith.events.Externalized;

import com.hapbang.appearance.app.AppearanceExternalEvents;
import com.hapbang.shared.appearance.event.AppearanceEventTopic;

/**
 * 내보내는 출연 이벤트마다 {@code eventType}·{@code eventVersion} 헤더가 붙는지 확인한다 (#871).
 * 받는 쪽은 {@code eventType}으로 종류를 가르는데, shared 레코드에 {@code @Externalized}만 붙이고 헤더 등록을 빠뜨리면
 * 헤더 없이 나간다. 새 이벤트를 더할 때 이 테스트가 빠뜨린 것을 잡는다.
 */
class AppearanceEventExternalizationConfigTest {

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

    private static List<Class<?>> externalizedEventTypes() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Externalized.class));
        List<Class<?>> types = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents(AppearanceEventTopic.class.getPackageName())) {
            types.add(Class.forName(candidate.getBeanClassName()));
        }
        return types;
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
