package com.hapbang.chat.app;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

// 서비스 경계 규칙(decisions.md, 2026-10-29): 실시간 경로의 Facade는 app 인터페이스만 주입받고 Redis·STOMP 구현에 직접 의존하지 않는다.
class ChatRealtimeServiceBoundaryTest {

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.hapbang.chat");

    @Test
    @DisplayName("ChatRealtimeFacade의 생성자·필드는 app 패키지의 인터페이스 타입만 쓴다")
    void facadeInjectsOnlyAppInterfaces() {
        for (Constructor<?> constructor : ChatRealtimeFacade.class.getDeclaredConstructors()) {
            assertThat(constructor.getParameterTypes()).allSatisfy(this::assertAppInterface);
        }
        assertThat(Arrays.stream(ChatRealtimeFacade.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(Field::getType)
                .toList())
                .isNotEmpty()
                .allSatisfy(this::assertAppInterface);
    }

    @Test
    @DisplayName("ChatRealtimeFacade는 app·domain과 Spring 스테레오타입·로깅(lombok 생성 표시 포함) 외에 의존하지 않는다")
    void facadeDependsOnlyOnAppLayer() {
        classes().that().haveFullyQualifiedName(ChatRealtimeFacade.class.getName())
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "com.hapbang.chat.app..", "com.hapbang.chat.domain..", "java..", "",
                        "org.springframework.stereotype..", "org.slf4j..", "lombok..")
                .check(classes);
    }

    @Test
    @DisplayName("실시간 전달·재검사 app 구현은 in·out 구현과 Redis·Kafka·STOMP 기술 타입에 의존하지 않는다")
    void realtimeAppServicesDoNotDependOnAdapters() {
        noClasses().that().haveFullyQualifiedName(ChatRealtimeFacade.class.getName())
                .or().haveFullyQualifiedName(DefaultChatFanoutDeliveryService.class.getName())
                .or().haveFullyQualifiedName(DefaultChatConnectionRecheckService.class.getName())
                .or().haveFullyQualifiedName(ChatConnectionRecheckBatch.class.getName())
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.hapbang.chat.in..", "com.hapbang.chat.out..",
                        "org.springframework.data.redis..", "io.lettuce..", "org.springframework.kafka..",
                        "org.springframework.messaging..", "org.springframework.web.socket..")
                .check(classes);
    }

    private void assertAppInterface(Class<?> type) {
        assertThat(type.isInterface()).as("%s는 인터페이스", type.getName()).isTrue();
        assertThat(type.getPackageName()).isEqualTo("com.hapbang.chat.app");
    }
}
