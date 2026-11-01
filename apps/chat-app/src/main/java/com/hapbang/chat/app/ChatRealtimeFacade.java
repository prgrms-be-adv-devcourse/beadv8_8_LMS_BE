package com.hapbang.chat.app;

import org.springframework.stereotype.Component;

import com.hapbang.chat.app.dto.ChatResyncReason;
import com.hapbang.chat.app.dto.ControlFanout;
import com.hapbang.chat.app.dto.MessageFanout;
import com.hapbang.chat.app.dto.RoomActivityFanout;
import com.hapbang.chat.domain.ChatControlType;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 실시간 경로(Redis 구독) 진입 어댑터가 쓰는 진입점. 로그인 사용자가 없으므로 {@code @PreAuthorize}를 붙이지 않는다.
 * Redis 타입은 받지 않고 역직렬화한 payload만 받는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatRealtimeFacade {

    private final ChatFanoutDeliveryService chatFanoutDeliveryService;
    private final ChatConnectionRecheckService chatConnectionRecheckService;
    private final ChatRoomActivityNotifier chatRoomActivityNotifier;

    public void deliverMessage(MessageFanout fanout) {
        chatFanoutDeliveryService.deliver(fanout);
    }

    /** 서버 채널로 받은 알림 재료: 보낸 사람에게 전송 결과 확인을 바로 보내고, 새 메시지 알림 묶음에 넣는다 */
    public void onRoomActivity(RoomActivityFanout activity) {
        chatFanoutDeliveryService.confirmToSender(activity);
        chatRoomActivityNotifier.record(activity);
    }

    public void applyControl(ControlFanout control) {
        if (control.type() != ChatControlType.RECHECK_USER) {
            log.warn("처리할 수 없는 연결 제어 작업을 버림 controlId={}, type={}", control.controlId(), control.type());
            return;
        }
        chatConnectionRecheckService.recheckUser(control.userId());
    }

    /** 서버 채널 구독이 다시 연결됐다(방 채널도 같은 연결이다). 그 사이 놓친 메시지는 FE 이력 대조로 복구하도록 알린다 */
    public void onMessageSubscriptionRecovered() {
        chatFanoutDeliveryService.resyncAll(ChatResyncReason.REALTIME_RESUBSCRIBED);
    }

    /** 처리 대기열이 넘쳐 이 방 메시지를 버렸다. 그 방의 로컬 구독 회원이 이력을 다시 대조하도록 알린다 */
    public void onMessageDropped(Long roomId) {
        chatFanoutDeliveryService.resyncRoomSubscribers(roomId, ChatResyncReason.DELIVERY_DROPPED);
    }

    /** 제어 채널 구독이 다시 연결됐다. 그 사이 놓친 제어 명령은 로컬 연결 전체 재검사로 회복한다 */
    public void onControlSubscriptionRecovered() {
        chatConnectionRecheckService.recheckAllConnected();
    }
}
