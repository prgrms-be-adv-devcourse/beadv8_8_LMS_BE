package com.hapbang.chat.app;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import com.hapbang.chat.app.dto.ChatConnectionTicket;
import com.hapbang.chat.app.dto.ChatConnectionTicketResponse;
import com.hapbang.chat.app.exception.ChatErrorCode;
import com.hapbang.chat.app.exception.ChatException;
import com.hapbang.shared.member.domain.Role;

/**
 * 웹소켓 연결 티켓 발급·사용(설계 문서 6장): 티켓 모양, 연결 만료 상한, 발급 상한과 상한 확인 실패, 일회 사용.
 */
class DefaultChatConnectionTicketServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-02T01:00:00Z");
    private static final Instant CAP = NOW.plus(Duration.ofMinutes(15));

    private final MemoryStore store = new MemoryStore();
    private final DefaultChatConnectionTicketService service = service(store);

    @Test
    void 티켓은_32바이트_base64url이고_발급할_때마다_다르며_유효_시간을_돌려준다() {
        ChatConnectionTicketResponse first = service.issue(HOST_ID, Role.INFLUENCER, null);
        ChatConnectionTicketResponse second = service.issue(HOST_ID, Role.INFLUENCER, null);

        assertThat(first.ticket()).matches("[A-Za-z0-9_-]{43}").isNotEqualTo(second.ticket());
        assertThat(first.expiresIn()).isEqualTo(30);
        assertThat(first.connectionExpiresAt()).isEqualTo(CAP);
        assertThat(store.ttl).isEqualTo(Duration.ofSeconds(30));
        assertThat(store.tickets.get(first.ticket()))
                .isEqualTo(new ChatConnectionTicket(HOST_ID, Role.INFLUENCER, NOW, CAP));
    }

    @Test
    void 연결_만료는_헤더의_exp가_상한보다_이르면_그_값이고_응답에도_싣는다() {
        ChatConnectionTicketResponse response = service.issue(HOST_ID, Role.ADMIN,
                String.valueOf(NOW.plusSeconds(300).getEpochSecond()));

        assertThat(store.tickets.get(response.ticket()).tokenExpiresAt()).isEqualTo(NOW.plusSeconds(300));
        assertThat(response.connectionExpiresAt()).isEqualTo(NOW.plusSeconds(300));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"9999999999", "abc", "1.5", " ", "99999999999999999999", "-31557014167219201"})
    void 헤더가_없거나_상한보다_늦거나_형식이_틀리면_발급_시각_더하기_15분이다(String header) {
        String ticket = service.issue(HOST_ID, Role.INFLUENCER, header).ticket();

        assertThat(store.tickets.get(ticket).tokenExpiresAt()).isEqualTo(CAP);
    }

    @Test
    void 같은_회원이_창_안에서_11번째로_받으면_TOO_MANY_TICKET_REQUESTS다() {
        for (int i = 0; i < 10; i++) {
            service.issue(HOST_ID, Role.INFLUENCER, null);
        }

        assertThatThrownBy(() -> service.issue(HOST_ID, Role.INFLUENCER, null))
                .isInstanceOfSatisfying(ChatException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ChatErrorCode.TOO_MANY_TICKET_REQUESTS));
        // 다른 회원은 따로 센다
        assertThat(service.issue(GUEST_ID, Role.INFLUENCER, null).ticket()).isNotBlank();
        assertThat(ChatErrorCode.TOO_MANY_TICKET_REQUESTS.getStatus().value()).isEqualTo(429);
        assertThat(store.window).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void 발급_횟수를_세지_못하면_상한_확인을_건너뛰고_발급한다() {
        store.failCount = true;

        for (int i = 0; i < 20; i++) {
            assertThat(service.issue(HOST_ID, Role.INFLUENCER, null).ticket()).isNotBlank();
        }
    }

    @Test
    void 티켓을_저장하지_못하거나_저장소가_없으면_CONNECTION_TICKET_UNAVAILABLE이다() {
        store.failSave = true;
        assertThatThrownBy(() -> service.issue(HOST_ID, Role.INFLUENCER, null))
                .isInstanceOfSatisfying(ChatException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(ChatErrorCode.CONNECTION_TICKET_UNAVAILABLE));

        DefaultChatConnectionTicketService withoutStore = new DefaultChatConnectionTicketService(
                new StaticListableBeanFactory().getBeanProvider(ChatConnectionTicketStore.class),
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(30), 10, Duration.ofSeconds(60));
        assertThatThrownBy(() -> withoutStore.issue(HOST_ID, Role.INFLUENCER, null))
                .isInstanceOf(ChatException.class);
        assertThat(withoutStore.consume("A".repeat(43))).isEmpty();
    }

    @Test
    void 티켓은_한_번만_쓸_수_있다() {
        String ticket = service.issue(HOST_ID, Role.INFLUENCER, null).ticket();

        assertThat(service.consume(ticket)).contains(new ChatConnectionTicket(HOST_ID, Role.INFLUENCER, NOW, CAP));
        assertThat(service.consume(ticket)).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"short", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA+"})
    void 모양이_틀린_티켓은_저장소를_부르지_않고_빈_값이다(String ticket) {
        assertThat(service.consume(ticket)).isEmpty();
        assertThat(store.consumeCalls).hasValue(0);
    }

    @Test
    void 저장소를_읽지_못하면_빈_값이다() {
        store.failConsume = true;

        assertThat(service.consume("A".repeat(43))).isEmpty();
    }

    private static DefaultChatConnectionTicketService service(ChatConnectionTicketStore store) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("store", store);
        ObjectProvider<ChatConnectionTicketStore> provider = beans.getBeanProvider(ChatConnectionTicketStore.class);
        return new DefaultChatConnectionTicketService(provider, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(30),
                10, Duration.ofSeconds(60));
    }

    /** 고정 창 횟수와 일회 사용을 흉내 내는 메모리 저장소 */
    private static final class MemoryStore implements ChatConnectionTicketStore {

        private final Map<String, ChatConnectionTicket> tickets = new ConcurrentHashMap<>();
        private final Map<Long, AtomicLong> counts = new ConcurrentHashMap<>();
        private final AtomicLong consumeCalls = new AtomicLong();
        private volatile Duration ttl;
        private volatile Duration window;
        private volatile boolean failCount;
        private volatile boolean failSave;
        private volatile boolean failConsume;

        @Override
        public void save(String ticket, ChatConnectionTicket value, Duration ttl) {
            if (failSave) {
                throw new IllegalStateException("Redis 쓰기 실패");
            }
            this.ttl = ttl;
            tickets.put(ticket, value);
        }

        @Override
        public Optional<ChatConnectionTicket> consume(String ticket) {
            consumeCalls.incrementAndGet();
            if (failConsume) {
                throw new IllegalStateException("Redis 읽기 실패");
            }
            return Optional.ofNullable(tickets.remove(ticket));
        }

        @Override
        public long incrementIssueCount(Long userId, Duration window) {
            if (failCount) {
                throw new IllegalStateException("Redis 실패");
            }
            this.window = window;
            return counts.computeIfAbsent(userId, key -> new AtomicLong()).incrementAndGet();
        }
    }
}
