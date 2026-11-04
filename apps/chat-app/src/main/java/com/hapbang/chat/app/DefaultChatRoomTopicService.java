package com.hapbang.chat.app;

import org.springframework.stereotype.Component;

import com.hapbang.chat.app.ChatRecipientStateReader.SubscriptionCheck;
import com.hapbang.chat.app.LocalChatSender.RoomTarget;
import com.hapbang.chat.app.LocalChatSender.SubscriptionSignal;
import com.hapbang.chat.app.LocalRoomSubscriptions.Registered;
import com.hapbang.chat.app.LocalRoomSubscriptions.Subscription;
import com.hapbang.chat.app.dto.ChatRecipientState;
import com.hapbang.chat.app.dto.ChatSubscriptionReason;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 방 topic 구독의 허가·해제·회수(설계 4.2).
 * <ul>
 *     <li>허가: 방이 열려 있고, 채팅할 수 있는 회원이고, 활성 참여이고, 구독에 붙인 세대가 현재 세대와 같아야 한다.</li>
 *     <li>거절은 연결을 닫지 않고 그 구독에 거절 프레임 하나를 쓴다. 성공 프레임은 없다(구독 순간의 공백은 허용, 설계 Q23).</li>
 *     <li>허가 뒤의 퇴장·세대 변경은 전달할 때의 버전 확인 규칙이 막는다. 퇴장 직후의 회수({@link #revokeMember})는 FE가
 *     빨리 알게 하는 것뿐이다.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultChatRoomTopicService implements ChatRoomTopicService {

    private final ChatRecipientStateReader chatRecipientStateReader;
    private final LocalRoomSubscriptions localRoomSubscriptions;
    private final LocalChatConnections localChatConnections;
    private final LocalChatSender localChatSender;

    /**
     * 방 topic 구독을 허가하고 목록에 넣는다. 거절하면 그 구독에 거절 프레임을 쓴다.
     *
     * @param participationVersion SUBSCRIBE의 {@code participation-version} 헤더 값
     * @return 넣었으면 true
     */
    @Override
    public boolean subscribe(Long roomId, Long userId, String sessionId, String subscriptionId,
            int participationVersion) {
        RoomTarget target = new RoomTarget(sessionId, subscriptionId, participationVersion);
        SubscriptionCheck check;
        try {
            check = chatRecipientStateReader.checkSubscription(roomId, userId);
        } catch (RuntimeException exception) {
            log.error("방 topic 구독 확인 실패로 거절 roomId={}, userId={}", roomId, userId, exception);
            reject(roomId, target, ChatSubscriptionReason.SERVER_BUSY, null);
            return false;
        }
        ChatRecipientState state = check.state();
        if (!check.roomOpen()) {
            reject(roomId, target, ChatSubscriptionReason.ROOM_NOT_FOUND, null);
            return false;
        }
        if (state == null || !state.canChat()) {
            reject(roomId, target, ChatSubscriptionReason.CHAT_NOT_ALLOWED, null);
            return false;
        }
        if (!state.activeParticipant()) {
            reject(roomId, target, ChatSubscriptionReason.NOT_MEMBER, null);
            return false;
        }
        if (state.participationVersion() != participationVersion) {
            reject(roomId, target, ChatSubscriptionReason.STALE_PARTICIPATION, state.participationVersion());
            return false;
        }
        Subscription subscription = new Subscription(sessionId, subscriptionId, roomId, userId, participationVersion);
        Registered registered = localRoomSubscriptions.register(subscription);
        switch (registered) {
            case OK -> {
                if (!localChatConnections.isConnected(sessionId)) {
                    // 허가하는 사이 연결이 닫혔다. 닫힘 정리가 먼저 끝났으면 다시 지울 기회가 없으므로 여기서 뺀다
                    localRoomSubscriptions.remove(subscription);
                    return false;
                }
                log.debug("방 topic 구독 roomId={}, userId={}, sessionId={}", roomId, userId, sessionId);
                return true;
            }
            case DUPLICATE -> {
                return false;
            }
            case TOO_MANY -> reject(roomId, target, ChatSubscriptionReason.TOO_MANY_SUBSCRIPTIONS, null);
            case SERVER_BUSY -> reject(roomId, target, ChatSubscriptionReason.SERVER_BUSY, null);
        }
        return false;
    }

    /** 구독을 뺀다. 모르는 구독 ID는 무시한다 */
    @Override
    public void unsubscribe(String sessionId, String subscriptionId) {
        localRoomSubscriptions.unregister(sessionId, subscriptionId);
    }

    /** 연결이 닫혔다. 그 연결의 방 구독을 모두 뺀다 */
    @Override
    public void sessionClosed(String sessionId) {
        localRoomSubscriptions.unregisterSession(sessionId);
    }

    /**
     * 퇴장 등이 커밋된 뒤 그 회원의 그 방 로컬 구독 중 끝난 세대 이하의 구독만 회수하고 회수 프레임을 쓴다. 구독은 허가할 때 확인한
     * 세대를 가지므로, 이 회수가 늦게 실행돼도 그 사이 재입장해 새 세대로 등록한 구독은 지우지 않는다.
     *
     * @param endedParticipationVersion 끝난 참여 세대
     */
    @Override
    public void revokeMember(Long roomId, Long userId, int endedParticipationVersion, ChatSubscriptionReason reason) {
        for (Subscription subscription : localRoomSubscriptions.subscriptionsOf(roomId, userId)) {
            if (subscription.participationVersion() > endedParticipationVersion) {
                continue;
            }
            if (localRoomSubscriptions.remove(subscription)) {
                localChatSender.sendSubscriptionSignal(roomId,
                        new RoomTarget(subscription.sessionId(), subscription.subscriptionId(),
                                subscription.participationVersion()),
                        SubscriptionSignal.SUBSCRIPTION_REVOKED, reason, null);
            }
        }
    }

    private void reject(Long roomId, RoomTarget target, ChatSubscriptionReason reason, Integer currentVersion) {
        log.info("방 topic 구독 거절 roomId={}, sessionId={}, reason={}", roomId, target.sessionId(), reason);
        localChatSender.sendSubscriptionSignal(roomId, target, SubscriptionSignal.SUBSCRIPTION_REJECTED, reason,
                currentVersion);
    }
}
