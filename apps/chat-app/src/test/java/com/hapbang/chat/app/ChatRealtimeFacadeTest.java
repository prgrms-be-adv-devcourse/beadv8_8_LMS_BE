package com.hapbang.chat.app;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.mockito.Mockito.*;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.hapbang.chat.app.dto.ChatResyncReason;
import com.hapbang.chat.app.dto.ControlFanout;
import com.hapbang.chat.app.dto.MessageFanout;
import com.hapbang.chat.app.dto.RoomActivityFanout;
import com.hapbang.chat.domain.ChatControlReason;
import com.hapbang.chat.domain.ChatControlType;
import com.hapbang.chat.domain.ChatNotifyRule;

class ChatRealtimeFacadeTest {

    private final ChatFanoutDeliveryService deliveryService = mock(ChatFanoutDeliveryService.class);
    private final ChatConnectionRecheckService recheckService = mock(ChatConnectionRecheckService.class);
    private final ChatRoomActivityNotifier activityNotifier = mock(ChatRoomActivityNotifier.class);
    private final ChatRealtimeFacade facade = new ChatRealtimeFacade(deliveryService, recheckService,
            activityNotifier);

    @Test
    void 전달_작업은_전달_서비스에_넘긴다() {
        MessageFanout fanout = new MessageFanout(UUID.randomUUID(), MessageFanout.Type.MESSAGE, 1L, 1, null,
                ChatNotifyRule.RECIPIENTS_EXCEPT_SENDER, null);

        facade.deliverMessage(fanout);

        verify(deliveryService).deliver(fanout);
        verifyNoInteractions(recheckService);
    }

    @Test
    void 알림_재료는_알림_묶음에_넣고_버린_방_메시지는_그_방_구독_회원에게_재동기화를_알린다() {
        RoomActivityFanout activity = new RoomActivityFanout(UUID.randomUUID(), 1L, 1, 3L,
                ChatNotifyRule.RECIPIENTS_EXCEPT_SENDER, HOST_ID, null, null);

        facade.onRoomActivity(activity);
        facade.onMessageDropped(1L);

        verify(deliveryService).confirmToSender(activity);
        verify(activityNotifier).record(activity);
        verify(deliveryService).resyncRoomSubscribers(1L, ChatResyncReason.DELIVERY_DROPPED);
    }

    @Test
    void RECHECK_USER는_명령의_사유와_버전이_아니라_그_회원의_재검사로_처리한다() {
        facade.applyControl(new ControlFanout(UUID.randomUUID(), ChatControlType.RECHECK_USER, HOST_ID, 3L,
                ChatControlReason.MEMBER_BACKFILL));

        verify(recheckService).recheckUser(HOST_ID);
        verifyNoInteractions(deliveryService);
    }

    @Test
    void 종류를_모르는_제어_작업은_버린다() {
        facade.applyControl(new ControlFanout(UUID.randomUUID(), null, HOST_ID, 3L, ChatControlReason.MEMBER_MODIFIED));

        verifyNoInteractions(recheckService, deliveryService);
    }

    @Test
    void 전달_채널_재구독은_재동기화_신호를_제어_채널_재구독은_로컬_연결_전체_재검사를_한다() {
        facade.onMessageSubscriptionRecovered();
        verify(deliveryService).resyncAll(ChatResyncReason.REALTIME_RESUBSCRIBED);
        verifyNoInteractions(recheckService);

        facade.onControlSubscriptionRecovered();
        verify(recheckService).recheckAllConnected();
    }
}
