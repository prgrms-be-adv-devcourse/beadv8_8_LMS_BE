package com.hapbang.chat.in;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static com.hapbang.chat.in.support.RealtimeStompClient.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.hapbang.chat.app.ChatRoomUseCase;
import com.hapbang.chat.app.ChatUserUseCase;
import com.hapbang.chat.app.LocalChatConnections;
import com.hapbang.chat.app.RealtimeChatFanout;
import com.hapbang.chat.app.dto.ChatMessageResponse;
import com.hapbang.chat.app.dto.MessageFanout;
import com.hapbang.chat.domain.ChatMessageType;
import com.hapbang.chat.domain.ChatNotifyRule;
import com.hapbang.chat.fixture.ChatUserSeeder;
import com.hapbang.chat.in.support.ChatE2ETest;
import com.hapbang.chat.in.support.ChatPipelineIsolationListener;
import com.hapbang.chat.in.support.RealtimeStompClient;
import com.hapbang.chat.in.support.RealtimeStompClient.Frame;
import com.hapbang.chat.in.websocket.ChatMessageSender;

/**
 * 이미 발행돼 구독자 처리 실행기에 남은 전달이 메서드가 끝난 뒤 늦게 처리돼도, 다음 메서드가 같은 회원으로 연 연결에 들어가지 않는지
 * 검증한다({@link ChatPipelineIsolationListener}의 구독자 처리 멈춤·비우기).
 * <p>
 * 첫 메서드의 전달(보낸 사람의 전송 결과 확인)은 수신 자격 검사를 통과한 뒤 STOMP로 보내기 직전에 몇 초 붙잡혀 있다가 메서드가
 * 끝난다. 전송 결과 확인은 회원 단위(convertAndSendToUser)라, 비우기가 없으면 늦게 보낸 프레임이 다음 메서드가 같은 회원으로 연 새
 * 연결에 도착한다.
 */
@ChatE2ETest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ChatRealtimeDrainE2ETest {

    private static final Duration HELD = Duration.ofSeconds(3);
    private static final long LEAKED_MESSAGE_ID = 777_001L;

    @LocalServerPort
    private int port;

    @Autowired
    private ChatUserUseCase chatUserUseCase;

    @Autowired
    private ChatRoomUseCase chatRoomUseCase;

    @Autowired
    private RealtimeChatFanout realtimeChatFanout;

    @Autowired
    private LocalChatConnections localChatConnections;

    @MockitoSpyBean
    private ChatMessageSender chatMessageSender;

    private RealtimeStompClient stomp;

    @BeforeEach
    void setUp() {
        ChatUserSeeder.seedDefaultUsers(chatUserUseCase);
        stomp = new RealtimeStompClient(RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build(),
                port, localChatConnections);
    }

    @AfterEach
    void tearDown() {
        stomp.close();
    }

    @Test
    @Order(1)
    void 전달_처리가_붙잡힌_채로_끝난다() throws Exception {
        Long chatRoomId = chatRoomUseCase.createOrGet(HOST_ID, GUEST_ID).chatRoomId();
        StompSession host = stomp.connect(HOST_ID);
        stomp.subscribe(host, "/user/queue/chats/messages", ChatMessageResponse.class);
        waitForSubscriptions();
        CountDownLatch held = new CountDownLatch(1);
        doAnswer(invocation -> {
            ChatMessageResponse message = invocation.getArgument(1);
            if (message.messageId() == LEAKED_MESSAGE_ID) {
                held.countDown();
                Thread.sleep(HELD.toMillis());
            }
            return invocation.callRealMethod();
        }).when(chatMessageSender).sendMessage(anyLong(), any(), anyInt());

        realtimeChatFanout.publishMessage(fanout(chatRoomId));

        // 자격 검사를 통과한 전달이 구독자 실행기에서 STOMP 전송 직전에 붙잡힌 채로 메서드가 끝난다
        assertThat(held.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @Order(2)
    void 앞_메서드의_늦은_전달이_같은_회원의_새_연결에_도착하지_않는다() throws Exception {
        StompSession host = stomp.connect(HOST_ID);
        BlockingQueue<Frame<ChatMessageResponse>> messages = stomp.subscribe(host, "/user/queue/chats/messages",
                ChatMessageResponse.class);
        waitForSubscriptions();

        Frame<ChatMessageResponse> leaked = messages.poll(HELD.toSeconds() + 2, TimeUnit.SECONDS);

        assertThat(leaked).as("앞 메서드의 전달이 새 연결로 샜다").isNull();
    }

    private static MessageFanout fanout(Long chatRoomId) {
        // 보낸 사람(호스트)의 전송 결과 확인은 회원 단위(convertAndSendToUser)로 간다
        ChatMessageResponse message = new ChatMessageResponse(LEAKED_MESSAGE_ID, chatRoomId, 1L, HOST_ID,
                ChatMessageType.TEXT, "앞 메서드의 메시지", NOW, null, null, null, null, null, null);
        return new MessageFanout(UUID.randomUUID(), MessageFanout.Type.MESSAGE, chatRoomId, 1, message,
                ChatNotifyRule.RECIPIENTS_EXCEPT_SENDER, "c-leak");
    }
}
