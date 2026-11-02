package com.hapbang.chat.in.websocket;

import java.security.Principal;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import com.hapbang.chat.app.ChatRoomTopicFacade;
import com.hapbang.chat.app.ChatUserFacade;


/**
 * STOMP 연결·구독·보내기를 검사한다(설계 4.2 목적지 규칙). 인증({@link ChatStompAuthInterceptor}) 다음에 돈다. 그래서 여기 오는
 * CONNECT 외 프레임은 회원을 결합한 연결의 것이고, {@link Principal}은 핸드셰이크에서 쓴 웹소켓 연결 티켓의 회원이다.
 * <ul>
 *     <li>CONNECT·STOMP(연결 프레임): 연결 티켓의 회원({@code Principal.getName()} = 회원 ID)으로 식별한다. 티켓의 역할이 채팅
 *     역할(인플루언서·인증된 인플루언서·관리자)이 아니거나, 채팅 회원 복제본으로 채팅할 수 없으면(티켓 발급 이후 정지·탈퇴·강등)
 *     거절한다. 회원을 연결에 결합한 직후 도는 최신 자격 확인이다. 이미 열린 연결은 회원 상태 재검사가 닫는다.</li>
 *     <li>SUBSCRIBE: 자기 회원 큐({@code /user/queue/chats/...})와 방 topic({@code /topic/chats/rooms/{roomId}}, 헤더
 *     {@code participation-version} 필수)만 허용한다. 방 topic은 참여·세대·자격을 허가하고 chat-app의 방 구독 목록에 넣는다.
 *     형식이 맞는 방 topic의 참여 불일치는 연결을 닫지 않고 그 구독에 거절 프레임을 쓴다.</li>
 *     <li>UNSUBSCRIBE: 방 구독 목록에서 뺀다. 모르는 구독 ID는 무시한다(오류·연결 종료 없음).</li>
 *     <li>SEND: 애플리케이션 목적지(/pub/...)만 허용한다. 브로커(/queue/...·/topic/...)나 회원 큐(/user/...)로 직접 보내면
 *     서버 검증 없이 전달되므로(위조 메시지) 막는다. 방·자격 검사는 SEND마다 하지 않고 전송 처리기(UseCase)가 한다. 재인증
 *     ({@code /pub/chats/reauth})도 같은 규칙으로 통과한다. 그 처리기는 티켓으로 연결 만료만 바꾸고 메시지를 저장하지 않으며, 회원을
 *     결합한 연결의 프레임만 여기 온다({@link ChatStompAuthInterceptor}).</li>
 * </ul>
 * 거절하면 Spring이 ERROR 프레임을 보내고 연결을 PROTOCOL_ERROR로 닫는다.
 * ERROR 프레임은 닫기와 겹치면 도착하지 않을 수 있으므로, 프론트는 ERROR 수신과 연결 종료를 모두 거절로 처리한다.
 */
@Component
public class ChatStompInterceptor implements ChannelInterceptor {

    /** 채팅을 쓸 수 있는 토큰 역할. Facade의 {@code ChatRoles.CHAT}과 같다 */
    private static final Set<String> CHAT_AUTHORITIES = Set.of("ROLE_INFLUENCER", "ROLE_VERIFIED_INFLUENCER",
            "ROLE_ADMIN");
    private static final String USER_QUEUE_PREFIX = ChatWebSocketConfig.USER_PREFIX
            + ChatWebSocketConfig.CHAT_QUEUE_PREFIX + "/";
    private static final Pattern ROOM_TOPIC = Pattern.compile(
            Pattern.quote(ChatWebSocketConfig.ROOM_TOPIC_PREFIX) + "([1-9][0-9]{0,18})");

    private final ChatUserFacade chatUserFacade;
    private final ChatRoomTopicFacade chatRoomTopicFacade;

    /**
     * 방 topic 구독 허가는 프레임을 쓰는 송신기를 거쳐 메시지 브로커 설정에 닿고, 그 설정이 이 인터셉터를 쓴다. 생성 순환을 피해
     * 지연해서 얻는다.
     */
    public ChatStompInterceptor(ChatUserFacade chatUserFacade, @Lazy ChatRoomTopicFacade chatRoomTopicFacade) {
        this.chatUserFacade = chatUserFacade;
        this.chatRoomTopicFacade = chatRoomTopicFacade;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        switch (accessor.getCommand()) {
            // STOMP 1.2의 STOMP 프레임도 Spring은 CONNECT와 같은 연결로 처리한다. 둘 다 검사해야 우회되지 않는다
            case CONNECT, STOMP -> checkChatUser(accessor.getUser());
            case SUBSCRIBE -> subscribe(accessor);
            case UNSUBSCRIBE -> {
                if (accessor.getSessionId() != null && accessor.getSubscriptionId() != null) {
                    chatRoomTopicFacade.unsubscribe(accessor.getSessionId(), accessor.getSubscriptionId());
                }
            }
            case SEND -> requireDestination(accessor.getDestination(), ChatWebSocketConfig.APP_PREFIX + "/",
                    "보낼 수 없는 경로입니다: ");
            default -> {
            }
        }
        return message;
    }

    private void subscribe(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination != null && destination.startsWith(USER_QUEUE_PREFIX)) {
            return;
        }
        Matcher room = destination == null ? null : ROOM_TOPIC.matcher(destination);
        if (room == null || !room.matches()) {
            throw new MessageDeliveryException("구독할 수 없는 경로입니다: " + destination);
        }
        Long userId = userIdOf(accessor.getUser());
        String subscriptionId = accessor.getSubscriptionId();
        if (userId == null || accessor.getSessionId() == null || subscriptionId == null) {
            throw new MessageDeliveryException("구독 정보가 올바르지 않습니다.");
        }
        chatRoomTopicFacade.subscribe(Long.valueOf(room.group(1)), userId, accessor.getSessionId(), subscriptionId,
                participationVersionOf(accessor));
    }

    private static int participationVersionOf(StompHeaderAccessor accessor) {
        String value = accessor.getFirstNativeHeader(ChatWebSocketConfig.PARTICIPATION_VERSION_HEADER);
        try {
            int version = value == null ? 0 : Integer.parseInt(value.trim());
            if (version >= 1) {
                return version;
            }
        } catch (NumberFormatException ignored) {
            // 아래에서 거절한다
        }
        throw new MessageDeliveryException("방 topic 구독에는 1 이상의 participation-version 헤더가 필요합니다.");
    }

    private void checkChatUser(Principal user) {
        Long userId = userIdOf(user);
        if (userId == null) {
            throw new MessageDeliveryException(user == null ? "로그인이 필요합니다." : "로그인 회원 정보가 올바르지 않습니다.");
        }
        if (!(user instanceof Authentication authentication) || authentication.getAuthorities().stream()
                .noneMatch(authority -> CHAT_AUTHORITIES.contains(authority.getAuthority()))
                || !chatUserFacade.canChat(userId)) {
            throw new MessageDeliveryException("인플루언서 이상 또는 관리자만 채팅을 사용할 수 있습니다.");
        }
    }

    private static Long userIdOf(Principal user) {
        if (user == null) {
            return null;
        }
        try {
            return Long.valueOf(user.getName());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static void requireDestination(String destination, String prefix, String errorMessage) {
        if (destination == null || !destination.startsWith(prefix)) {
            throw new MessageDeliveryException(errorMessage + destination);
        }
    }
}
