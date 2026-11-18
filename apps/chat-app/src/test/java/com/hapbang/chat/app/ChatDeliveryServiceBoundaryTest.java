package com.hapbang.chat.app;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.*;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.hapbang.chat.app.dto.ChatJobStream;
import com.hapbang.chat.app.port.ChatDeliveryBackpressureException;
import com.hapbang.chat.app.port.ChatDeliveryQueue;
import com.hapbang.chat.app.port.ChatDeliveryUnavailableException;
import com.hapbang.chat.app.port.ChatQueueGroupMissingException;
import com.hapbang.chat.app.port.ControlJob;
import com.hapbang.chat.app.port.DeliveryJob;
import com.hapbang.chat.app.port.OutboxJob;
import com.hapbang.chat.app.port.QueuePositions;
import com.hapbang.chat.config.ChatDeliveryProperties;
import com.hapbang.chat.out.redis.RedisChatDeliveryQueue;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/**
 * 서비스 경계 규칙(decisions.md, 2026-10-29)과 데이터 접근 구조(2026-11-17): 전달 파이프라인의 app 조정 서비스는 DB를
 * {@code app/port}의 repository port로, 큐를 {@code app/port} 계약으로 쓰고, Redis 구현(out)과 진입 어댑터(in), 기술 타입에 의존하지 않는다.
 */
class ChatDeliveryServiceBoundaryTest {

    /** 전달 파이프라인의 app 조정 서비스와 그 계약·보조 클래스 */
    private static final List<Class<?>> DELIVERY_APP_CLASSES = List.of(ChatOutboxRelay.class,
            ChatStreamJobProcessor.class, ChatStreamReconstructor.class, ChatStreamTrimmer.class,
            ChatFanoutPayloadLoader.class, QueuePositions.class, OutboxJob.class, DeliveryJob.class, ControlJob.class,
            ChatJobStream.class, ChatRetryBackoff.class,
            ChatDeliveryClock.class, ChatDeliveryErrors.class, ChatDeliveryProperties.class, ChatDeliveryQueue.class,
            ChatOutboxStore.class, ChatPublishCheckpointStore.class, ChatDeliverySourceReader.class,
            ChatQueueGroupMissingException.class, ChatDeliveryUnavailableException.class,
            ChatDeliveryBackpressureException.class,
            ChatDeliveryAvailability.class, ChatDeliveryAdminService.class, DefaultChatDeliveryAdminService.class,
            ChatDeliveryAdminFacade.class);

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.hapbang.chat");

    @Test
    void 전달_조정_서비스는_out_in_구현과_Redis_JDBC_기술_타입에_의존하지_않는다() {
        String[] names = DELIVERY_APP_CLASSES.stream().map(Class::getName).toArray(String[]::new);
        noClasses().that().haveNameMatching(String.join("|", Arrays.stream(names)
                        .map(name -> name.replace(".", "\\.") + "(\\$.*)?").toList()))
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.hapbang.chat.in..", "com.hapbang.chat.out..",
                        "org.springframework.data.redis..", "io.lettuce..", "org.springframework.jdbc..",
                        "org.springframework.data.jpa..", "jakarta.persistence..")
                .check(classes);
    }

    @Test
    void 전달_조정_서비스의_생성자는_out_구현_대신_app_계약을_받는다() {
        for (Class<?> service : List.of(ChatOutboxRelay.class, ChatStreamJobProcessor.class,
                ChatStreamReconstructor.class, ChatStreamTrimmer.class, ChatFanoutPayloadLoader.class,
                ChatDeliveryAvailability.class, DefaultChatDeliveryAdminService.class,
                ChatDeliveryAdminFacade.class)) {
            for (Constructor<?> constructor : service.getDeclaredConstructors()) {
                assertThat(constructor.getParameterTypes())
                        .as("%s 생성자", service.getSimpleName())
                        .allSatisfy(type -> assertThat(type.getPackageName()).doesNotStartWith("com.hapbang.chat.out")
                                .doesNotStartWith("com.hapbang.chat.in"));
            }
        }
    }

    @Test
    void DB_조정은_app_클래스가_repository_port로_하고_Redis_큐만_out_구현이다() {
        // DB는 app/port의 repository port를 직접 쓰므로 DB 위에 계약 인터페이스와 out 구현을 따로 두지 않는다
        assertThat(List.of(ChatOutboxStore.class, ChatPublishCheckpointStore.class, ChatDeliverySourceReader.class))
                .allSatisfy(type -> {
                    assertThat(type.isInterface()).isFalse();
                    assertThat(type.getPackageName()).isEqualTo("com.hapbang.chat.app");
                });
        assertThat(ChatDeliveryQueue.class.isInterface()).isTrue();
        assertThat(ChatDeliveryQueue.class.getPackageName()).isEqualTo("com.hapbang.chat.app.port");
        assertThat(ChatDeliveryQueue.class).isAssignableFrom(RedisChatDeliveryQueue.class);
        assertThat(ChatDeliveryAdminService.class).isAssignableFrom(DefaultChatDeliveryAdminService.class);
    }

    /**
     * 진입 어댑터는 Facade·app 서비스 계약(인터페이스)·값 타입으로 app을 쓴다. 전달 파이프라인 실행기({@code in/stream})는
     * {@link ChatDeliveryPipelineService}로만 일을 맡기고, app 조정 구현 클래스나 {@code app/port} 계약을 직접 쓰지 않는다.
     */
    @Test
    void 전달_파이프라인_실행기는_app_조정_구현_대신_app_서비스_계약에_의존한다() {
        DescribedPredicate<JavaClass> appConcreteOrPort = JavaClass.Predicates.resideInAPackage("com.hapbang.chat.app")
                .and(DescribedPredicate.not(JavaClass.Predicates.INTERFACES))
                .and(DescribedPredicate.not(JavaClass.Predicates.ENUMS))
                .or(JavaClass.Predicates.resideInAPackage("com.hapbang.chat.app.port.."))
                .as("app 구현 클래스나 app/port 계약");
        noClasses().that().resideInAPackage("com.hapbang.chat.in.stream..")
                .should().dependOnClassesThat(appConcreteOrPort)
                .check(classes);

        assertThat(ChatDeliveryPipelineService.class.isInterface()).isTrue();
        assertThat(ChatDeliveryPipelineService.class).isAssignableFrom(DefaultChatDeliveryPipelineService.class);
        for (Constructor<?> constructor : DefaultChatDeliveryPipelineService.class.getDeclaredConstructors()) {
            assertThat(constructor.getParameterTypes()).allSatisfy(type -> assertThat(type.getPackageName())
                    .doesNotStartWith("com.hapbang.chat.out").doesNotStartWith("com.hapbang.chat.in"));
        }
    }
}
