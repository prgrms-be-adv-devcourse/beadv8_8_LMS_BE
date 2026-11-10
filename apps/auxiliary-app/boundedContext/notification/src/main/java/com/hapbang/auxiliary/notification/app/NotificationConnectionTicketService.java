package com.hapbang.auxiliary.notification.app;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import com.hapbang.auxiliary.global.ratelimit.RateLimitExceededException;
import com.hapbang.auxiliary.notification.app.dto.ConnectionTicket;
import com.hapbang.auxiliary.notification.app.dto.ConnectionTicketResponse;
import com.hapbang.auxiliary.notification.app.exception.NotificationErrorCode;
import com.hapbang.auxiliary.notification.app.exception.NotificationException;
import com.hapbang.shared.member.domain.Role;

import lombok.extern.slf4j.Slf4j;

/**
 * 알림 웹소켓 연결 티켓 발급·사용. 채팅 연결 티켓을 복사해 줄였다(ND-028).
 * <ul>
 *     <li>티켓: {@link SecureRandom} 32바이트 base64url(43자). 원문은 로그에 남기지 않는다. 유효 시간은 기본 30초이고 한 번 쓰면
 *     사라진다(ND-058).</li>
 *     <li>연결 만료: 발급 시각 + {@value #CONNECTION_LIFETIME_MINUTES}분이 상한이다. {@code X-User-Token-Exp}(epoch 초)가 그보다 이르면
 *     그 시각으로 앞당긴다(ND-056). 이 헤더는 Gateway가 넣도록 설정하기 전(N4)에는 믿을 수 있는 값이 아니므로 <b>앞당기는 데만
 *     쓰고 늘리는 데는 쓰지 않는다.</b> 위조해도 자기 연결을 짧게 할 뿐이다.</li>
 *     <li>발급 상한: 회원마다 60초 고정 창에 10번. 넘으면 공통 {@link RateLimitExceededException}(429 {@code TOO_MANY_REQUESTS},
 *     {@code Retry-After} = 창 길이). 횟수를 세지 못하면 상한 확인을 건너뛰고 발급한다(ND-058).</li>
 *     <li>저장소가 없거나(실시간 전달을 끈 실행) 저장하지 못하면 503이다.</li>
 * </ul>
 */
@Slf4j
@Service
public class NotificationConnectionTicketService {

    /** 회원 앱의 access token 수명(분). 연결 수명의 상한이다(ND-056) */
    static final long CONNECTION_LIFETIME_MINUTES = 15;
    static final Duration CONNECTION_LIFETIME = Duration.ofMinutes(CONNECTION_LIFETIME_MINUTES);
    private static final int TICKET_BYTES = 32;
    private static final Pattern TICKET_FORMAT = Pattern.compile("[A-Za-z0-9_-]{43}");

    private final Supplier<NotificationConnectionTicketStore> store;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Duration ticketTtl;
    private final int issueLimit;
    private final Duration issueWindow;

    @Autowired
    public NotificationConnectionTicketService(ObjectProvider<NotificationConnectionTicketStore> store,
            @Value("${hapbang.notification.realtime.websocket.ticket-ttl:30s}") Duration ticketTtl,
            @Value("${hapbang.notification.realtime.websocket.ticket-issue-limit:10}") int issueLimit,
            @Value("${hapbang.notification.realtime.websocket.ticket-issue-window:60s}") Duration issueWindow) {
        this(store::getIfAvailable, Clock.systemUTC(), ticketTtl, issueLimit, issueWindow);
    }

    /** 저장소·시계를 직접 넘긴다(테스트) */
    NotificationConnectionTicketService(Supplier<NotificationConnectionTicketStore> store, Clock clock,
            Duration ticketTtl, int issueLimit, Duration issueWindow) {
        Assert.isTrue(ticketTtl.toSeconds() >= 1, "ticket-ttl은 1초 이상이어야 합니다.");
        Assert.isTrue(issueLimit > 0, "ticket-issue-limit는 1 이상이어야 합니다.");
        Assert.isTrue(issueWindow.isPositive(), "ticket-issue-window는 0보다 커야 합니다.");
        this.store = store;
        this.clock = clock;
        this.ticketTtl = ticketTtl;
        this.issueLimit = issueLimit;
        this.issueWindow = issueWindow;
    }

    public ConnectionTicketResponse issue(Long userId, Role role, String tokenExpiresAtHeader) {
        Assert.notNull(userId, "userId가 필요합니다.");
        Assert.notNull(role, "role이 필요합니다.");
        NotificationConnectionTicketStore ticketStore = store.get();
        if (ticketStore == null) {
            throw new NotificationException(NotificationErrorCode.CONNECTION_TICKET_UNAVAILABLE);
        }
        checkIssueLimit(ticketStore, userId);
        Instant issuedAt = clock.instant();
        String ticket = newTicket();
        ConnectionTicket value = new ConnectionTicket(userId, role, issuedAt,
                connectionExpiresAt(tokenExpiresAtHeader, issuedAt));
        try {
            ticketStore.save(ticket, value, ticketTtl);
        } catch (RuntimeException exception) {
            log.warn("알림 연결 티켓을 저장하지 못함 userId={}", userId, exception);
            throw new NotificationException(NotificationErrorCode.CONNECTION_TICKET_UNAVAILABLE);
        }
        return new ConnectionTicketResponse(ticket, ticketTtl.toSeconds(), value.connectionExpiresAt());
    }

    /** 티켓을 쓴다. 없거나, 모양이 틀리거나, 만료됐거나, 이미 썼거나, 저장소를 읽지 못하면 빈 값(연결 거절) */
    public Optional<ConnectionTicket> consume(String ticket) {
        if (ticket == null || !TICKET_FORMAT.matcher(ticket).matches()) {
            return Optional.empty();
        }
        NotificationConnectionTicketStore ticketStore = store.get();
        if (ticketStore == null) {
            return Optional.empty();
        }
        try {
            return ticketStore.consume(ticket);
        } catch (RuntimeException exception) {
            log.warn("알림 연결 티켓을 읽지 못해 연결을 거절함", exception);
            return Optional.empty();
        }
    }

    /** 헤더의 {@code exp}와 발급 시각 + 15분 중 이른 값. 헤더가 없거나 epoch 초 정수가 아니면 상한이다 */
    static Instant connectionExpiresAt(String tokenExpiresAtHeader, Instant issuedAt) {
        Instant cap = issuedAt.plus(CONNECTION_LIFETIME);
        if (tokenExpiresAtHeader == null || tokenExpiresAtHeader.isBlank()) {
            return cap;
        }
        try {
            Instant exp = Instant.ofEpochSecond(Long.parseLong(tokenExpiresAtHeader.trim()));
            return exp.isBefore(cap) ? exp : cap;
        } catch (NumberFormatException | DateTimeException exception) {
            log.warn("X-User-Token-Exp 형식 오류로 연결 수명 상한을 씀");
            return cap;
        }
    }

    private void checkIssueLimit(NotificationConnectionTicketStore ticketStore, Long userId) {
        long count;
        try {
            count = ticketStore.incrementIssueCount(userId, issueWindow);
        } catch (RuntimeException exception) {
            log.warn("알림 연결 티켓 발급 횟수를 세지 못해 상한 확인을 건너뜀 userId={}", userId, exception);
            return;
        }
        if (count > issueLimit) {
            // 잠시 뒤 다시 보내면 되는 거절: 공통 429 TOO_MANY_REQUESTS + Retry-After, 요청마다 남기지 않는다(docs/dev/logging.md).
            // 남은 시간을 따로 묻지 않고 창 길이를 상한으로 준다
            throw new RateLimitExceededException(Math.max(1, issueWindow.toSeconds()));
        }
    }

    private String newTicket() {
        byte[] bytes = new byte[TICKET_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
