package com.hapbang.chat.in.event;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.support.KafkaNull;

import com.hapbang.chat.app.ChatUserFacade;
import com.hapbang.chat.in.event.kafka.ChatKafkaConstants;
import com.hapbang.chat.in.event.kafka.ChatKafkaConsumerSettings;
import com.hapbang.chat.in.event.kafka.MissingTypeEvent;
import com.hapbang.chat.in.event.kafka.RejectedEventException;
import com.hapbang.chat.in.event.kafka.SkippedEventLog;
import com.hapbang.shared.member.event.UserLoggedOutEvent;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * 처리기가 없는 회원 이벤트 종류와 빈 값을 예외 없이 건너뛰고, 같은 종류가 이어져도 INFO 로그는 1·2·4·8…번째만 남기는지 본다.
 */
class ChatUserEventListenerSkipTest {

    private final ChatUserFacade facade = mock(ChatUserFacade.class);
    private final ChatUserEventListener listener = new ChatUserEventListener(facade, new ChatKafkaConsumerSettings(
            ChatKafkaConstants.MEMBER_EVENTS_TOPIC, ChatKafkaConstants.MEMBER_REPLICA_GROUP,
            ChatKafkaConstants.MEMBER_EVENTS_DLT, ChatKafkaConstants.MAX_RETRIES, ChatKafkaConstants.RETRY_INITIAL_INTERVAL,
            ChatKafkaConstants.RETRY_MAX_INTERVAL, ChatKafkaConstants.DEAD_LETTER_SEND_TIMEOUT,
            ChatKafkaConstants.DEAD_LETTER_PARTITIONS, ChatKafkaConstants.DEAD_LETTER_REPLICAS));
    private final Logger logger = (Logger) LoggerFactory.getLogger(ChatUserEventListener.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level previousLevel;

    @BeforeEach
    void attach() {
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
        logger.setLevel(previousLevel);
    }

    @Test
    void 처리기가_없는_종류는_예외_없이_건너뛰고_INFO는_2의_거듭제곱_번째만_남긴다() {
        UserLoggedOutEvent loggedOut = new UserLoggedOutEvent(1L, "session");

        for (long offset = 0; offset < 9; offset++) {
            assertThatNoException().isThrownBy(() -> listener.onUnsupported(loggedOut, "1", 0, 0L));
        }

        List<ILoggingEvent> infos = appender.list.stream().filter(e -> e.getLevel() == Level.INFO).toList();
        assertThat(infos).extracting(e -> e.getArgumentArray()[4]).containsExactly(1L, 2L, 4L, 8L);
        assertThat(infos).allSatisfy(e -> assertThat(e.getArgumentArray()[0]).isEqualTo(UserLoggedOutEvent.class.getName()));
        assertThat(appender.list).filteredOn(e -> e.getLevel() == Level.DEBUG).hasSize(5);
        // 회원 정보(세션 ID 등)는 로그에 남기지 않는다
        assertThat(appender.list).noneSatisfy(e -> assertThat(e.getFormattedMessage()).contains("session"));
        verifyNoInteractions(facade);
    }

    @Test
    void 빈_값은_종류를_따로_세어_건너뛴다() {
        listener.onUnsupported(KafkaNull.INSTANCE, "2", 0, 5L);
        listener.onUnsupported(new UserLoggedOutEvent(1L, "s"), "1", 0, 6L);

        assertThat(appender.list).filteredOn(e -> e.getLevel() == Level.INFO)
                .extracting(e -> e.getArgumentArray()[0])
                .containsExactly("빈 값(tombstone)", UserLoggedOutEvent.class.getName());
    }

    @Test
    void 클래스패스에_없는_종류도_받은_클래스_이름으로_세어_INFO는_2의_거듭제곱_번째만_남긴다() {
        String typeId = "com.hapbang.shared.member.event.MemberLoggedOut";

        for (long offset = 0; offset < 9; offset++) {
            assertThatNoException().isThrownBy(() -> listener.onUnsupported(new MissingTypeEvent(typeId), "3", 0, 0L));
        }

        assertThat(appender.list).filteredOn(e -> e.getLevel() == Level.INFO)
                .allSatisfy(e -> assertThat(e.getArgumentArray()[0]).isEqualTo(typeId))
                .extracting(e -> e.getArgumentArray()[4]).containsExactly(1L, 2L, 4L, 8L);
        assertThat(appender.list).filteredOn(e -> e.getLevel() == Level.DEBUG).hasSize(5);
        verifyNoInteractions(facade);
    }

    @Test
    void 신뢰_패키지_밖_타입은_건너뛰지_않고_재시도_제외_예외로_DLT에_보내며_값을_로그에_남기지_않는다() {
        assertThatThrownBy(() -> listener.onUnsupported("secret-body", "7", 0, 1L))
                .isInstanceOf(RejectedEventException.class)
                .hasMessageContaining("java.lang.String").hasMessageNotContaining("secret-body");
        Map<String, String> map = new HashMap<>(Map.of("a", "secret-map"));
        assertThatThrownBy(() -> listener.onUnsupported(map, "7", 0, 2L))
                .isInstanceOf(RejectedEventException.class).hasMessageNotContaining("secret-map");

        assertThat(appender.list).isEmpty();
        assertThat(listener.skipCounterKeys()).isZero();
    }

    @Test
    void 따로_세는_종류는_상한까지이고_넘는_새_종류는_기타_종류로_함께_센다() {
        String first = "com.hapbang.shared.member.event.First";
        for (int i = 0; i < 3; i++) {
            listener.onUnsupported(new MissingTypeEvent(first), "1", 0, (long) i);
        }
        int overflowTypes = 9;
        for (int i = 0; i < SkippedEventLog.MAX_COUNTERS - 1 + overflowTypes; i++) {
            listener.onUnsupported(new MissingTypeEvent("com.hapbang.shared.member.event.NewType" + i), "1", 0, 0L);
        }
        listener.onUnsupported(new MissingTypeEvent(first), "1", 0, 9L);

        assertThat(listener.skipCounterKeys()).isEqualTo(SkippedEventLog.MAX_COUNTERS);
        List<ILoggingEvent> overflowInfos = appender.list.stream()
                .filter(e -> e.getLevel() == Level.INFO && SkippedEventLog.OVERFLOW_BUCKET.equals(e.getArgumentArray()[5]))
                .toList();
        // 상한을 넘은 9개 새 종류는 기타 종류 하나로 세고, 그 1·2·4·8번째만 INFO이며 받은 종류 이름을 적는다
        assertThat(overflowInfos).extracting(e -> e.getArgumentArray()[4]).containsExactly(1L, 2L, 4L, 8L);
        int firstOverflow = SkippedEventLog.MAX_COUNTERS - 1;
        assertThat(overflowInfos).extracting(e -> e.getArgumentArray()[0]).containsExactly(
                "com.hapbang.shared.member.event.NewType" + firstOverflow,
                "com.hapbang.shared.member.event.NewType" + (firstOverflow + 1),
                "com.hapbang.shared.member.event.NewType" + (firstOverflow + 3),
                "com.hapbang.shared.member.event.NewType" + (firstOverflow + 7));
        // 원래 종류는 자기 계수를 이어 센다(4번째라 INFO)
        assertThat(appender.list).filteredOn(e -> e.getLevel() == Level.INFO && first.equals(e.getArgumentArray()[0]))
                .extracting(e -> e.getArgumentArray()[4]).containsExactly(1L, 2L, 4L);
    }
}
