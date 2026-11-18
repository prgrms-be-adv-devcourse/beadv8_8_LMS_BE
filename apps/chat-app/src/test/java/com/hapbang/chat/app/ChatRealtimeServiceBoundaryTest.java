package com.hapbang.chat.app;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.chat.app.port.ChatRoomMemberships;
import com.hapbang.chat.app.port.LocalChatConnections;
import com.hapbang.chat.out.cache.CachedChatRoomMemberships;
import com.hapbang.chat.out.cache.ChatRoomMembershipLoader;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

// 서비스 경계 규칙(decisions.md, 2026-10-29): 모든 Facade는 app 서비스·app/port 계약 인터페이스만 주입받고, 실시간 경로의 Facade는
// Redis·STOMP 구현에 직접 의존하지 않는다. app/port는 app 업무 클래스와 in·out에 기대지 않는다.
class ChatRealtimeServiceBoundaryTest {

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.hapbang.chat");

    @Test
    @DisplayName("ChatRealtimeFacade의 생성자·필드는 app 서비스·app/port 계약의 인터페이스 타입만 쓴다")
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

    /**
     * 서비스 경계 규칙 1: 모든 Facade는 업무 서비스를 {@code app}의 인터페이스로, 바깥 기능을 {@code app/port}의 인터페이스로
     * 주입받는다. 설정값({@code config}의 {@code @ConfigurationProperties})은 값이라 함께 받을 수 있다.
     */
    @Test
    @DisplayName("모든 Facade의 생성자·필드는 app·app/port 인터페이스와 config 설정값만 쓴다")
    void allFacadesInjectOnlyAppInterfaces() {
        List<Class<?>> facades = classes.stream()
                .filter(type -> type.getPackageName().equals("com.hapbang.chat.app"))
                .filter(type -> type.getSimpleName().endsWith("Facade"))
                .<Class<?>>map(JavaClass::reflect)
                .toList();
        assertThat(facades).hasSizeGreaterThanOrEqualTo(11);
        for (Class<?> facade : facades) {
            for (Constructor<?> constructor : facade.getDeclaredConstructors()) {
                assertThat(constructor.getParameterTypes()).as("%s 생성자", facade.getSimpleName())
                        .allSatisfy(this::assertInjectable);
            }
            assertThat(Arrays.stream(facade.getDeclaredFields())
                    .filter(field -> !Modifier.isStatic(field.getModifiers()))
                    .map(Field::getType)
                    .toList())
                    .as("%s 필드", facade.getSimpleName())
                    .allSatisfy(this::assertInjectable);
        }
    }

    @Test
    @DisplayName("Facade는 app 바로 아래의 구현 클래스(업무 구현·조정 클래스)에 의존하지 않는다")
    void facadesDoNotDependOnAppImplementations() {
        DescribedPredicate<JavaClass> appImplementation = JavaClass.Predicates.resideInAPackage("com.hapbang.chat.app")
                .and(DescribedPredicate.not(JavaClass.Predicates.INTERFACES))
                // 서비스 계약 안에 둔 입출력 record(예: ChatGroupRoomService.InvitePreparation)는 계약의 일부다
                .and(DescribedPredicate.describe("인터페이스 안에 둔 타입이 아닌",
                        type -> type.getEnclosingClass().map(enclosing -> !enclosing.isInterface()).orElse(true)))
                .and(DescribedPredicate.not(JavaClass.Predicates.simpleNameEndingWith("Facade")))
                .as("app 구현 클래스");
        noClasses().that().resideInAPackage("com.hapbang.chat.app").and().haveSimpleNameEndingWith("Facade")
                .should().dependOnClassesThat(appImplementation)
                .check(classes);
    }

    /**
     * 서비스 경계 규칙: Facade뿐 아니라 {@code app}의 모든 빈 클래스는 다른 업무 서비스를 그 인터페이스로 주입받는다. 업무 서비스
     * 구현({@code app}의 인터페이스를 구현한 {@code DefaultXxx})을 생성자·필드 타입으로 쓰지 않는다. 인터페이스가 없는 조정 클래스
     * (예: {@code ChatUserUseCase}, {@code LocalRoomSubscriptions})와, Facade용 좁은 역할 계약({@code ChatUserEligibility})만
     * 구현하고 서비스들이 도메인 타입 검증에 함께 쓰는 {@code ChatValidationService}는 구현이 아니라 조정 클래스로 보고 그대로
     * 주입받을 수 있다. 빈이 아닌 중첩 클래스는 보지 않는다.
     */
    @Test
    @DisplayName("app 클래스는 업무 서비스 구현(app 인터페이스를 구현한 클래스)을 주입받지 않는다")
    void appClassesDoNotInjectServiceImplementations() {
        List<Class<?>> appClasses = classes.stream()
                .filter(type -> type.getPackageName().equals("com.hapbang.chat.app"))
                .filter(type -> type.getEnclosingClass().isEmpty())
                .<Class<?>>map(JavaClass::reflect)
                .toList();
        assertThat(appClasses).hasSizeGreaterThan(50);

        assertThat(appClasses.stream().flatMap(type -> injectedServiceImplementations(type).stream()).toList())
                .isEmpty();
    }

    @Test
    @DisplayName("업무 서비스 구현을 주입받는 app 클래스가 있으면 규칙 검사가 잡는다")
    void serviceImplementationInjectionIsDetected() {
        assertThat(injectedServiceImplementations(InjectsQueryImplementation.class))
                .containsExactly("InjectsQueryImplementation 생성자 → DefaultChatRoomQueryService",
                        "InjectsQueryImplementation 필드 → DefaultChatRoomQueryService");
        assertThat(injectedServiceImplementations(InjectsQueryInterface.class)).isEmpty();
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
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.hapbang.chat.in..", "com.hapbang.chat.out..",
                        "org.springframework.data.redis..", "io.lettuce..", "org.springframework.kafka..",
                        "org.springframework.messaging..", "org.springframework.web.socket..")
                .check(classes);
    }

    @Test
    @DisplayName("in/scheduler의 트리거는 app 호출만 하고 app/port·out 구현과 Redis·Kafka·STOMP 기술 타입에 의존하지 않는다")
    void schedulerTriggersOnlyCallApp() {
        classes().that().resideInAPackage("com.hapbang.chat.in.scheduler..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "com.hapbang.chat.app..", "com.hapbang.chat.global..", "com.hapbang.chat.in.scheduler..",
                        "java..", "", "org.springframework.scheduling..", "org.springframework.stereotype..",
                        "lombok..")
                .check(classes);
        // app 아래라도 port(저장소·외부 연동 계약)는 트리거가 직접 부르지 않는다. 일은 app 서비스가 한다
        noClasses().that().resideInAPackage("com.hapbang.chat.in.scheduler..")
                .should().dependOnClassesThat().resideInAPackage("com.hapbang.chat.app.port..")
                .check(classes);
    }

    @Test
    @DisplayName("app/port는 in·out 구현과 app의 업무 클래스에 의존하지 않는다(app/dto·app/exception 값은 쓸 수 있다)")
    void portsDoNotDependOnAdapters() {
        noClasses().that().resideInAPackage("com.hapbang.chat.app.port..")
                .should().dependOnClassesThat().resideInAnyPackage("com.hapbang.chat.in..", "com.hapbang.chat.out..")
                .check(classes);
        // app 바로 아래(서비스·조정 클래스·Facade)는 port를 쓰는 쪽이다. port가 거꾸로 기대지 않는다
        noClasses().that().resideInAPackage("com.hapbang.chat.app.port..")
                .should().dependOnClassesThat().resideInAPackage("com.hapbang.chat.app")
                .check(classes);
    }

    @Test
    @DisplayName("방 참여 캐시(out/cache)는 app 클래스를 부르지 않고 app/port의 repository로 읽기 전용 트랜잭션 하나에서 읽는다")
    void membershipCacheReadsThroughRepositoryPorts() throws NoSuchMethodException {
        noClasses().that().resideInAPackage("com.hapbang.chat.out.cache..")
                .should().dependOnClassesThat().resideInAPackage("com.hapbang.chat.app")
                .check(classes);
        // 캐시와 다른 빈이어야 캐시가 프록시를 거쳐 불러 트랜잭션이 걸린다
        Transactional transactional = ChatRoomMembershipLoader.class.getMethod("load", Long.class)
                .getAnnotation(Transactional.class);
        assertThat(transactional).isNotNull();
        assertThat(transactional.readOnly()).isTrue();
        assertThat(ChatRoomMemberships.class).isAssignableFrom(CachedChatRoomMemberships.class);
        assertThat(ChatRoomMembershipLoader.class).isNotEqualTo(CachedChatRoomMemberships.class);
    }

    /** 그 클래스가 생성자·필드로 받는 업무 서비스 구현. 비어 있어야 한다 */
    private static List<String> injectedServiceImplementations(Class<?> type) {
        List<String> found = new ArrayList<>();
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            for (Class<?> parameter : constructor.getParameterTypes()) {
                if (isServiceImplementation(parameter)) {
                    found.add(type.getSimpleName() + " 생성자 → " + parameter.getSimpleName());
                }
            }
        }
        for (Field field : type.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()
                    && isServiceImplementation(field.getType())) {
                found.add(type.getSimpleName() + " 필드 → " + field.getType().getSimpleName());
            }
        }
        return found;
    }

    /** {@code app} 바로 아래의 {@code DefaultXxx} 클래스로, 같은 패키지의 인터페이스(업무 서비스 계약)를 구현한다 */
    private static boolean isServiceImplementation(Class<?> type) {
        return !type.isInterface() && type.getPackageName().equals("com.hapbang.chat.app")
                && type.getSimpleName().startsWith("Default")
                && Arrays.stream(type.getInterfaces())
                        .anyMatch(contract -> contract.getPackageName().equals("com.hapbang.chat.app"));
    }

    /** 규칙 위반 예: 조회 계약 대신 구현을 받는다 */
    @SuppressWarnings("unused")
    private static final class InjectsQueryImplementation {

        private final DefaultChatRoomQueryService chatRoomQueryService;

        InjectsQueryImplementation(DefaultChatRoomQueryService chatRoomQueryService) {
            this.chatRoomQueryService = chatRoomQueryService;
        }
    }

    /** 규칙을 지킨 예: 조회 계약을 받는다 */
    @SuppressWarnings("unused")
    private static final class InjectsQueryInterface {

        private final ChatRoomQueryService chatRoomQueryService;

        InjectsQueryInterface(ChatRoomQueryService chatRoomQueryService) {
            this.chatRoomQueryService = chatRoomQueryService;
        }
    }

    /** Facade가 주입받을 수 있는 타입: app·app/port의 인터페이스, config의 설정값 */
    private void assertInjectable(Class<?> type) {
        if (type.getPackageName().equals("com.hapbang.chat.config")) {
            return;
        }
        assertThat(type.isInterface()).as("%s는 인터페이스", type.getName()).isTrue();
        assertThat(type.getPackageName()).as(type.getName())
                .isIn("com.hapbang.chat.app", "com.hapbang.chat.app.port");
    }

    /** 업무 서비스 계약은 app, 이 JVM의 연결 목록 같은 바깥 계약(port)은 app/port의 인터페이스다 */
    private void assertAppInterface(Class<?> type) {
        assertThat(type.isInterface()).as("%s는 인터페이스", type.getName()).isTrue();
        assertThat(type.getPackageName()).as(type.getName())
                .isEqualTo(type == LocalChatConnections.class ? "com.hapbang.chat.app.port" : "com.hapbang.chat.app");
    }
}
