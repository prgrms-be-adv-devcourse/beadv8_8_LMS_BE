package com.hapbang.chat.app;

import static org.assertj.core.api.Assertions.*;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 회원 이벤트·백필 전환의 서비스 경계(테스트 기준 "서비스 경계", 결정 "서비스 경계 규칙")를 리플렉션으로 확인한다.
 * Facade와 업무 서비스 구현이 {@code app} 계약(인터페이스)에만 의존하고 Kafka·Redis·HTTP Client·out 구현체를 직접 쓰지 않는다.
 */
class ChatUserServiceBoundaryTest {

    private static final List<String> FORBIDDEN_PACKAGE_PREFIXES = List.of(
            "org.springframework.kafka", "org.apache.kafka", "org.springframework.data.redis",
            "com.hapbang.chat.out", "com.hapbang.chat.in", "com.hapbang.shared.member.out",
            "org.springframework.web.client");

    @Test
    void 전환한_계약은_app_패키지의_인터페이스다() {
        for (Class<?> contract : List.of(ChatUserSyncService.class, ChatUserBackfillService.class,
                ChatMemberSnapshotSource.class, ChatConnectionControl.class)) {
            assertThat(contract.isInterface()).as(contract.getSimpleName()).isTrue();
            assertThat(contract.getPackageName()).isEqualTo("com.hapbang.chat.app");
        }
    }

    @Test
    void 회원_Facade는_동기화_계약만_주입받고_구현_기술에_의존하지_않는다() {
        assertThat(dependencyTypes(ChatUserFacade.class)).contains(ChatUserSyncService.class)
                .doesNotContain(DefaultChatUserSyncService.class, ChatUserUseCase.class);
        assertThat(dependencyTypes(ChatUserBackfillFacade.class)).containsExactly(ChatUserBackfillService.class);
        assertNoTechnologyDependency(ChatUserFacade.class);
        assertNoTechnologyDependency(ChatUserBackfillFacade.class);
    }

    @Test
    void 업무_서비스_구현은_외부_기능을_app_계약으로만_부른다() {
        assertThat(dependencyTypes(DefaultChatUserSyncService.class)).contains(ChatConnectionControl.class);
        assertThat(dependencyTypes(DefaultChatUserBackfillService.class))
                .contains(ChatMemberSnapshotSource.class, ChatUserSyncService.class);
        assertNoTechnologyDependency(DefaultChatUserSyncService.class);
        assertNoTechnologyDependency(DefaultChatUserBackfillService.class);
    }

    @Test
    void 회원_Facade의_채팅_자격_확인도_app_계약에만_의존한다() {
        assertThat(ChatUserEligibility.class.isInterface()).isTrue();
        assertThat(ChatUserEligibility.class.getPackageName()).isEqualTo("com.hapbang.chat.app");
        assertThat(dependencyTypes(ChatUserFacade.class))
                .containsExactlyInAnyOrder(ChatUserSyncService.class, ChatUserEligibility.class)
                .doesNotContain(ChatValidationService.class);
        assertThat(ChatUserEligibility.class).isAssignableFrom(ChatValidationService.class);
    }

    private static List<Class<?>> dependencyTypes(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(Field::getType)
                .filter(fieldType -> !fieldType.isPrimitive() && !fieldType.getName().startsWith("java."))
                .toList();
    }

    private static void assertNoTechnologyDependency(Class<?> type) {
        for (Class<?> dependency : dependencyTypes(type)) {
            assertThat(FORBIDDEN_PACKAGE_PREFIXES).as("%s → %s", type.getSimpleName(), dependency.getName())
                    .noneMatch(prefix -> dependency.getName().startsWith(prefix + "."));
        }
    }
}
