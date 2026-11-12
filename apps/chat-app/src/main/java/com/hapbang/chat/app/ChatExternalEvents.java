package com.hapbang.chat.app;

import java.time.Duration;

import org.springframework.modulith.events.Externalized;

import com.hapbang.shared.chat.event.ChatEventTopic;

/**
 * Kafka로 내보내는 채팅 이벤트의 선택 기준과 실행 중 재발행 값. 외부화 설정({@code ChatEventExternalizationConfig})과 재발행
 * ({@link ChatEventRepublisher})이 같이 쓴다. 규칙은 docs/architecture/infrastructure/kafka.md "발행"이다.
 * <ul>
 *     <li>shared 채팅 이벤트({@code com.hapbang.shared.chat.event}) 바로 안의 {@link Externalized} 레코드만 내보낸다. 채팅이 받기만
 *     하는 다른 컨텍스트의 shared 이벤트(회원·출연)는 내보내지 않는다.</li>
 *     <li>재발행 값은 이 클래스에만 두고 yaml·환경 변수로 바꾸지 않는다(Kafka 값은 코드에 둔다는 채팅 결정과 같다). 다른 발행 앱과 같은
 *     매분·발행 후 1분·100건이다.</li>
 * </ul>
 */
public final class ChatExternalEvents {

    /** 재발행 주기: 매분 5초(KST) */
    public static final String REPUBLISH_CRON = "5 * * * * *";
    /** 발행한 지 이 시간이 지난 기록만 다시 보낸다. 커밋 직후 보내는 중인 기록과 겹치지 않게 한다 */
    public static final Duration REPUBLISH_PENDING_GRACE = Duration.ofMinutes(1);
    /** 한 번에 다시 보내는 최대 건수 */
    public static final int REPUBLISH_BATCH_SIZE = 100;

    private static final String EVENT_PACKAGE = ChatEventTopic.class.getPackageName();

    private ChatExternalEvents() {
    }

    public static boolean isExternalized(Object event) {
        Class<?> type = event.getClass();
        return type.getPackageName().equals(EVENT_PACKAGE) && type.isAnnotationPresent(Externalized.class);
    }
}
