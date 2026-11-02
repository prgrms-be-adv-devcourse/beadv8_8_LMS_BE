package com.hapbang.chat.app;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import com.hapbang.chat.app.dto.ChatConnectionTicket;
import com.hapbang.chat.app.dto.ChatConnectionTicketResponse;
import com.hapbang.chat.app.exception.ChatErrorCode;
import com.hapbang.chat.app.exception.ChatException;
import com.hapbang.shared.member.domain.Role;

import lombok.extern.slf4j.Slf4j;

/**
 * 웹소켓 연결 티켓 발급·사용의 업무 구현(설계 문서 6장).
 * <ul>
 *     <li>티켓: {@link SecureRandom} 32바이트를 base64url(패딩 없음, 43자)로 쓴다. 저장 키는 원문이 아니라 해시다(저장소가 정한다).
 *     원문은 로그에 남기지 않는다.</li>
 *     <li>티켓 유효 시간: 초기값 30초. 한 번 쓰면 사라진다.</li>
 *     <li>연결 만료({@code tokenExpiresAt}): access token의 {@code exp}를 따른다. Gateway가 넣은 {@code X-User-Token-Exp}(epoch 초)와
 *     발급 시각 + access token 수명({@value #ACCESS_TOKEN_LIFETIME_MINUTES}분) 중 이른 값이다. 헤더가 없거나 형식이 틀리면 발급 시각 +
 *     access token 수명이다. 헤더를 위조해도 연결 수명을 늘리지 못한다.</li>
 *     <li>발급 상한: 회원마다 60초 고정 창에 10번(초기값). 넘으면 {@code TOO_MANY_TICKET_REQUESTS}(429)다. 횟수를 세지 못하면
 *     (Redis 실패) 상한 확인을 건너뛰고 발급한다.</li>
 * </ul>
 * 수치는 미확정 초기값(Q12)이고 코드 기본값이다. 같은 키의 yaml·환경 변수가 덮는다. access token 수명 상한은 회원 앱
 * ({@code hapbang.jwt.access-token-ttl}, 15분)과 맞춘 상수다.
 */
@Slf4j
@Service
public class DefaultChatConnectionTicketService implements ChatConnectionTicketService {

    /** 회원 앱의 access token 수명(분). 연결 수명의 상한이다 */
    static final long ACCESS_TOKEN_LIFETIME_MINUTES = 15;
    static final Duration ACCESS_TOKEN_LIFETIME = Duration.ofMinutes(ACCESS_TOKEN_LIFETIME_MINUTES);
    private static final int TICKET_BYTES = 32;
    /** base64url(패딩 없음) 32바이트 = 43자. 이 모양이 아니면 저장소를 부르지 않는다 */
    private static final Pattern TICKET_FORMAT = Pattern.compile("[A-Za-z0-9_-]{43}");

    private final ObjectProvider<ChatConnectionTicketStore> chatConnectionTicketStore;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Duration ticketTtl;
    private final int issueLimit;
    private final Duration issueWindow;

    @Autowired
    public DefaultChatConnectionTicketService(ObjectProvider<ChatConnectionTicketStore> chatConnectionTicketStore,
            @Value("${hapbang.chat.realtime.websocket.ticket-ttl:30s}") Duration ticketTtl,
            @Value("${hapbang.chat.realtime.websocket.ticket-issue-limit:10}") int issueLimit,
            @Value("${hapbang.chat.realtime.websocket.ticket-issue-window:60s}") Duration issueWindow) {
        this(chatConnectionTicketStore, Clock.systemUTC(), ticketTtl, issueLimit, issueWindow);
    }

    /** 시계를 직접 넘긴다(테스트) */
    DefaultChatConnectionTicketService(ObjectProvider<ChatConnectionTicketStore> chatConnectionTicketStore,
            Clock clock, Duration ticketTtl, int issueLimit, Duration issueWindow) {
        Assert.isTrue(ticketTtl.toSeconds() >= 1, "hapbang.chat.realtime.websocket.ticket-ttl은 1초 이상이어야 합니다.");
        Assert.isTrue(issueLimit > 0, "hapbang.chat.realtime.websocket.ticket-issue-limit는 1 이상이어야 합니다.");
        Assert.isTrue(issueWindow.isPositive(), "hapbang.chat.realtime.websocket.ticket-issue-window는 0보다 커야 합니다.");
        this.chatConnectionTicketStore = chatConnectionTicketStore;
        this.clock = clock;
        this.ticketTtl = ticketTtl;
        this.issueLimit = issueLimit;
        this.issueWindow = issueWindow;
    }

    @Override
    public ChatConnectionTicketResponse issue(Long userId, Role role, String tokenExpiresAtHeader) {
        Assert.notNull(userId, "userId가 필요합니다.");
        Assert.notNull(role, "role이 필요합니다.");
        ChatConnectionTicketStore store = chatConnectionTicketStore.getIfAvailable();
        if (store == null) {
            throw new ChatException(ChatErrorCode.CONNECTION_TICKET_UNAVAILABLE);
        }
        checkIssueLimit(store, userId);
        Instant issuedAt = clock.instant();
        String ticket = newTicket();
        ChatConnectionTicket value = new ChatConnectionTicket(userId, role, issuedAt,
                tokenExpiresAt(tokenExpiresAtHeader, issuedAt));
        try {
            store.save(ticket, value, ticketTtl);
        } catch (RuntimeException exception) {
            log.warn("웹소켓 연결 티켓을 저장하지 못함 userId={}", userId, exception);
            throw new ChatException(ChatErrorCode.CONNECTION_TICKET_UNAVAILABLE);
        }
        return new ChatConnectionTicketResponse(ticket, ticketTtl.toSeconds(), value.tokenExpiresAt());
    }

    @Override
    public Optional<ChatConnectionTicket> consume(String ticket) {
        ChatConnectionTicketStore store = chatConnectionTicketStore.getIfAvailable();
        if (store == null || ticket == null || !TICKET_FORMAT.matcher(ticket).matches()) {
            return Optional.empty();
        }
        try {
            return store.consume(ticket);
        } catch (RuntimeException exception) {
            log.warn("웹소켓 연결 티켓을 읽지 못해 연결을 거절함", exception);
            return Optional.empty();
        }
    }

    /**
     * 연결을 닫을 시각: 헤더의 {@code exp}와 발급 시각 + access token 수명 중 이른 값. 헤더가 없거나 epoch 초 정수가 아니면 상한을 쓴다.
     */
    static Instant tokenExpiresAt(String tokenExpiresAtHeader, Instant issuedAt) {
        Instant cap = issuedAt.plus(ACCESS_TOKEN_LIFETIME);
        if (tokenExpiresAtHeader == null) {
            return cap;
        }
        try {
            Instant exp = Instant.ofEpochSecond(Long.parseLong(tokenExpiresAtHeader.trim()));
            return exp.isBefore(cap) ? exp : cap;
        } catch (NumberFormatException | DateTimeException exception) {
            log.warn("X-User-Token-Exp 형식 오류로 access token 수명 상한을 씀");
            return cap;
        }
    }

    private void checkIssueLimit(ChatConnectionTicketStore store, Long userId) {
        long count;
        try {
            count = store.incrementIssueCount(userId, issueWindow);
        } catch (RuntimeException exception) {
            log.warn("웹소켓 연결 티켓 발급 횟수를 세지 못해 상한 확인을 건너뜀 userId={}", userId, exception);
            return;
        }
        if (count > issueLimit) {
            throw new ChatException(ChatErrorCode.TOO_MANY_TICKET_REQUESTS);
        }
    }

    private String newTicket() {
        byte[] bytes = new byte[TICKET_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
