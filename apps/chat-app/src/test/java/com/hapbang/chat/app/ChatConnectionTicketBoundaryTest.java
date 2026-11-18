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

import com.hapbang.chat.app.port.ChatConnectionTicketStore;
import com.hapbang.chat.app.port.LocalChatConnections;
import com.hapbang.chat.in.ApiV1ChatController;
import com.hapbang.chat.in.ChatConnectionStompController;
import com.hapbang.chat.in.websocket.ChatConnectionTicketHandshakeInterceptor;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

// 서비스 경계 규칙(decisions.md, 2026-10-29): 연결 티켓의 진입 어댑터는 Facade를 거치고, Facade는 app 서비스·app/port 계약
// 인터페이스만 주입받으며, 업무 구현은 저장소를 app/port 계약으로만 부른다
class ChatConnectionTicketBoundaryTest {

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.hapbang.chat");

    @Test
    @DisplayName("ChatConnectionTicketFacade의 생성자·필드는 app 서비스·app/port 계약의 인터페이스 타입만 쓴다")
    void facadeInjectsOnlyAppInterfaces() {
        for (Constructor<?> constructor : ChatConnectionTicketFacade.class.getDeclaredConstructors()) {
            assertThat(constructor.getParameterTypes()).allSatisfy(this::assertAppInterface);
        }
        assertThat(Arrays.stream(ChatConnectionTicketFacade.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(Field::getType)
                .toList())
                .containsExactly(ChatConnectionTicketService.class, LocalChatConnections.class);
        assertThat(ChatConnectionTicketStore.class.isInterface()).isTrue();
        assertThat(ChatConnectionTicketStore.class.getPackageName()).isEqualTo("com.hapbang.chat.app.port");
    }

    @Test
    @DisplayName("Facade·업무 구현은 웹소켓·Redis·out 구현에 의존하지 않는다")
    void appDoesNotDependOnAdapters() {
        noClasses().that().haveFullyQualifiedName(ChatConnectionTicketFacade.class.getName())
                .or().haveFullyQualifiedName(DefaultChatConnectionTicketService.class.getName())
                .or().haveFullyQualifiedName(ChatPresenceHeartbeat.class.getName())
                .should().dependOnClassesThat().resideInAnyPackage("com.hapbang.chat.in..", "com.hapbang.chat.out..",
                        "org.springframework.data.redis..", "io.lettuce..", "org.springframework.web.socket..",
                        "org.springframework.messaging..")
                .check(classes);
    }

    @Test
    @DisplayName("핸드셰이크 인터셉터·REST 컨트롤러·재인증 STOMP 컨트롤러는 티켓 계약·저장소·연결 목록을 직접 쓰지 않고 Facade를 거친다")
    void adaptersGoThroughFacade() {
        noClasses().that().haveFullyQualifiedName(ChatConnectionTicketHandshakeInterceptor.class.getName())
                .or().haveFullyQualifiedName(ApiV1ChatController.class.getName())
                .or().haveFullyQualifiedName(ChatConnectionStompController.class.getName())
                .should().dependOnClassesThat().haveFullyQualifiedName(ChatConnectionTicketService.class.getName())
                .orShould().dependOnClassesThat().haveFullyQualifiedName(ChatConnectionTicketStore.class.getName())
                .orShould().dependOnClassesThat().haveFullyQualifiedName(LocalChatConnections.class.getName())
                .orShould().dependOnClassesThat().resideInAnyPackage("com.hapbang.chat.out..")
                .check(classes);
        classes().that().haveFullyQualifiedName(ChatConnectionTicketHandshakeInterceptor.class.getName())
                .or().haveFullyQualifiedName(ApiV1ChatController.class.getName())
                .or().haveFullyQualifiedName(ChatConnectionStompController.class.getName())
                .should().dependOnClassesThat().haveFullyQualifiedName(ChatConnectionTicketFacade.class.getName())
                .check(classes);
    }

    /** 업무 서비스 계약은 app, 이 JVM의 연결 목록 같은 바깥 계약(port)은 app/port의 인터페이스다 */
    private void assertAppInterface(Class<?> type) {
        assertThat(type.isInterface()).as("%s는 인터페이스", type.getName()).isTrue();
        assertThat(type.getPackageName()).as(type.getName())
                .isEqualTo(type == LocalChatConnections.class ? "com.hapbang.chat.app.port" : "com.hapbang.chat.app");
    }
}
