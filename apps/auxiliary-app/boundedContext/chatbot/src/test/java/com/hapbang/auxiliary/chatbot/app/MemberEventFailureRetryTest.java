package com.hapbang.auxiliary.chatbot.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.hapbang.auxiliary.chatbot.app.dto.MemberEventRetryResDto;
import com.hapbang.auxiliary.chatbot.app.dto.MemberEventRetryResult;
import com.hapbang.auxiliary.chatbot.domain.ApplySource;
import com.hapbang.auxiliary.chatbot.in.kafka.MemberEventDeadLetterRecorder;
import com.hapbang.auxiliary.chatbot.support.ChatbotIntegrationTestBase;
import com.hapbang.auxiliary.chatbot.support.CopyFixtures;
import com.hapbang.shared.lecture.domain.LecturePurchaseResult;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.shared.member.event.UserJoinedEvent;
import com.hapbang.shared.member.event.UserMaskedEvent;
import com.hapbang.shared.member.event.UserModifiedEvent;

/**
 * DLT로 보낸 회원 이벤트의 실패 기록과 관리자 수동 재처리 (#976). Kafka 없이 오류 처리기가 부르는 기록기를 바로 부른다.
 * "전체" 상한은 이 클래스에서 2로 줄인다.
 */
@TestPropertySource(properties = "hapbang.chatbot.member-event-failure.retry-all-limit=2")
class MemberEventFailureRetryTest extends ChatbotIntegrationTestBase {

    private static final long ADMIN = 1L;
    private static final String TOPIC = "member.events.v1";
    private static final AtomicLong OFFSET = new AtomicLong();

    @Autowired
    private MemberEventDeadLetterRecorder recorder;

    @Autowired
    private MemberEventFailureRetryUseCase retryUseCase;

    @MockitoSpyBean
    private CopyApplier copyApplier;

    @MockitoSpyBean
    private MemberMasking memberMasking;

    @Test
    @DisplayName("DLT로 간 마스킹을 다시 처리하면 그 회원 데이터가 지워지고 기록은 처리됨이 되며 원문을 지운다. 두 번째는 이미 처리됨")
    void retryMasking() {
        copyApplier.apply(CopyFixtures.purchase(70, 6, LecturePurchaseResult.OWNED, 0), ApplySource.EVENT);
        long id = recordFailure("6", new UserMaskedEvent(6L, Instant.parse("2026-11-09T01:00:00Z")), dbDown());

        assertThat(retryUseCase.retry(List.of(id), ADMIN))
                .containsExactly(new MemberEventRetryResDto(id, MemberEventRetryResult.SUCCEEDED, null, null));

        assertThat(rows.maskedMembers()).contains(6L);
        assertThat(rows.count("lecture_purchase_copy")).isZero();
        assertThat(failure(id))
                .containsEntry("status", "RESOLVED")
                .containsEntry("resolved_by", ADMIN)
                .containsEntry("payload", null);
        assertThat(retryUseCase.retry(List.of(id), ADMIN)).extracting(MemberEventRetryResDto::result)
                .containsExactly(MemberEventRetryResult.ALREADY_RESOLVED);
        verify(memberMasking, times(1)).mask(any());
    }

    @Test
    @DisplayName("새 버전이 반영된 뒤 옛 변경 이벤트를 다시 처리해도 사본은 새 값 그대로다")
    void retryOlderVersionKeepsNewer() {
        long id = recordFailure("7", new UserModifiedEvent(user(7, 1, "옛 소개")), dbDown());
        copyApplier.apply(user(7, 3, "새 소개"));

        assertThat(retryUseCase.retry(List.of(id), ADMIN)).extracting(MemberEventRetryResDto::result)
                .containsExactly(MemberEventRetryResult.SUCCEEDED);

        assertThat(rows.row("member_copy", "id", 7)).containsEntry("version", 3L).containsEntry("bio", "새 소개");
    }

    @Test
    @DisplayName("다시 처리가 실패하면 FAILED와 예외 클래스를 돌려주고 기록은 PENDING으로 남아 횟수와 원인이 바뀐다")
    void retryFails() {
        long id = recordFailure("6", new UserMaskedEvent(6L, Instant.parse("2026-11-09T01:00:00Z")), dbDown());
        doThrow(new IllegalStateException("또 실패 010-1234-5678 " + "가".repeat(300))).when(memberMasking).mask(any());

        List<MemberEventRetryResDto> results = retryUseCase.retry(List.of(id), ADMIN);

        // 응답 메시지도 기록과 같이 거르고 200자로 자른다
        String expected = "또 실패 [전화번호] " + "가".repeat(200 - "또 실패 [전화번호] ".length());
        assertThat(results).containsExactly(new MemberEventRetryResDto(id, MemberEventRetryResult.FAILED,
                IllegalStateException.class.getName(), expected));
        assertThat(failure(id)).containsEntry("message", expected);
        assertThat(failure(id))
                .containsEntry("status", "PENDING")
                .containsEntry("retry_count", 1)
                .containsEntry("exception_class", IllegalStateException.class.getName())
                .containsEntry("last_retried_at", java.sql.Timestamp.valueOf(clock.now()));
        assertThat((String) failure(id).get("payload")).isNotNull();
    }

    @Test
    @DisplayName("고르지 않으면 다시 처리할 수 있는 PENDING만 오래된 순으로 상한만큼 처리한다(앞에 쌓인 처리 불가 기록이 막지 않는다). "
            + "없는 ID는 NOT_FOUND, 읽을 수 없던 메시지는 NOT_REPLAYABLE")
    void retryAllAndUnknown() {
        long unreadable = recordFailure("24", null, new ListenerExecutionFailedException("읽지 못함"));
        long first = recordFailure("21", new UserMaskedEvent(21L, Instant.parse("2026-11-09T01:00:00Z")), dbDown());
        long second = recordFailure("22", new UserMaskedEvent(22L, Instant.parse("2026-11-09T01:00:00Z")), dbDown());
        long third = recordFailure("23", new UserMaskedEvent(23L, Instant.parse("2026-11-09T01:00:00Z")), dbDown());

        assertThat(retryUseCase.retry(List.of(), ADMIN)).extracting(MemberEventRetryResDto::id).containsExactly(first, second);
        assertThat(failure(third)).containsEntry("status", "PENDING");
        assertThat(failure(unreadable)).containsEntry("status", "PENDING");

        assertThat(retryUseCase.retry(List.of(unreadable, 999_999L), ADMIN)).extracting(MemberEventRetryResDto::result)
                .containsExactly(MemberEventRetryResult.NOT_REPLAYABLE, MemberEventRetryResult.NOT_FOUND);
        assertThat(failure(unreadable)).containsEntry("event_type", "UNREADABLE").containsEntry("user_id", 24L);
    }

    @Test
    @DisplayName("두 관리자가 같은 기록을 동시에 다시 처리하면 뒤 요청은 회원 락에서 기다렸다가 ALREADY_RESOLVED, 반영은 한 번")
    void concurrentRetry() throws Exception {
        long id = recordFailure("6", new UserMaskedEvent(6L, Instant.parse("2026-11-09T01:00:00Z")), dbDown());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(memberMasking).mask(any());

        CompletableFuture<List<MemberEventRetryResDto>> first =
                CompletableFuture.supplyAsync(() -> retryUseCase.retry(List.of(id), ADMIN));
        assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<List<MemberEventRetryResDto>> second =
                CompletableFuture.supplyAsync(() -> retryUseCase.retry(List.of(id), 2L));
        awaitWaitingLock();
        release.countDown();

        assertThat(first.get(30, TimeUnit.SECONDS)).extracting(MemberEventRetryResDto::result)
                .containsExactly(MemberEventRetryResult.SUCCEEDED);
        assertThat(second.get(30, TimeUnit.SECONDS)).extracting(MemberEventRetryResDto::result)
                .containsExactly(MemberEventRetryResult.ALREADY_RESOLVED);
        verify(memberMasking, times(1)).mask(any());
    }

    @Test
    @DisplayName("관리자가 가입·변경을 다시 처리하는 중에 같은 회원의 마스킹이 와도 교착 없이 둘 다 끝난다(락 순서: 회원 → 실패 행)")
    void retryAndMaskingDoNotDeadlock() throws Exception {
        long id = recordFailure("51", new UserModifiedEvent(user(51, 1, "소개")), dbDown());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // 다시 처리가 회원·실패 행 락을 잡은 뒤 반영 직전에 멈춘다
        doAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(copyApplier).apply(any(UserDto.class));

        CompletableFuture<List<MemberEventRetryResDto>> retry =
                CompletableFuture.supplyAsync(() -> retryUseCase.retry(List.of(id), ADMIN));
        assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<MemberMasking.Result> masking = CompletableFuture.supplyAsync(() ->
                memberMasking.mask(new UserMaskedEvent(51L, Instant.parse("2026-11-09T01:00:00Z"))));
        awaitWaitingLock();
        release.countDown();

        assertThat(retry.get(30, TimeUnit.SECONDS)).extracting(MemberEventRetryResDto::result)
                .containsExactly(MemberEventRetryResult.SUCCEEDED);
        assertThat(masking.get(30, TimeUnit.SECONDS).first()).isTrue();
        assertThat(rows.maskedMembers()).contains(51L);
        assertThat(failure(id)).containsEntry("status", "RESOLVED").containsEntry("payload", null);
    }

    // 다른 트랜잭션이 락을 기다리기 시작할 때까지 기다린다(시간이 아니라 DB 상태로)
    private void awaitWaitingLock() {
        await().atMost(Duration.ofSeconds(30)).until(() ->
                jdbcTemplate.queryForObject("SELECT count(*) FROM pg_locks WHERE NOT granted", Integer.class) > 0);
    }

    @Test
    @DisplayName("기록의 메시지는 이메일·전화를 지우고 200자로 자른다. DB 예외는 메시지 대신 SQLState만 남긴다")
    void personalInfo() {
        long plain = recordFailure("31", new UserJoinedEvent(user(31, 1, "소개")),
                new ListenerExecutionFailedException("실패", new IllegalStateException(
                        "a@b.com 010-1234-5678 " + "가".repeat(300))));
        long sql = recordFailure("32", new UserJoinedEvent(user(32, 1, "소개")),
                new ListenerExecutionFailedException("실패", new DataIntegrityViolationException("중복",
                        new SQLException("Detail: Key (nickname)=(홍길동) already exists.", "23505"))));

        String message = (String) failure(plain).get("message");
        assertThat(message).startsWith("[이메일] [전화번호] 가").doesNotContain("a@b.com");
        assertThat(message.codePointCount(0, message.length())).isEqualTo(200);
        assertThat(failure(plain)).containsEntry("exception_class", IllegalStateException.class.getName());
        assertThat(failure(sql))
                .containsEntry("exception_class", DataIntegrityViolationException.class.getName())
                .containsEntry("root_cause_class", SQLException.class.getName())
                .containsEntry("message", "SQLState=23505");
    }

    @Test
    @DisplayName("회원이 마스킹되면 그 회원의 PENDING 원문에서 닉네임·분야·자기소개를 비우고, 이미 마스킹된 회원의 실패는 처음부터 비워 적는다")
    void maskingClearsPayloadProfile() {
        long before = recordFailure("41", new UserModifiedEvent(user(41, 1, "지울 소개")), dbDown());

        memberMasking.mask(new UserMaskedEvent(41L, Instant.parse("2026-11-09T01:00:00Z")));
        long after = recordFailure("41", new UserModifiedEvent(user(41, 2, "또 지울 소개")), dbDown());

        assertThat((String) failure(before).get("payload")).doesNotContain("지울 소개", "회원닉", "GAME");
        assertThat((String) failure(after).get("payload")).doesNotContain("지울 소개", "회원닉", "GAME");
        assertThat(retryUseCase.retry(List.of(before), ADMIN)).extracting(MemberEventRetryResDto::result)
                .containsExactly(MemberEventRetryResult.SUCCEEDED);
    }

    private long recordFailure(String key, Object event, Exception exception) {
        long offset = OFFSET.incrementAndGet();
        recorder.record(new ConsumerRecord<>(TOPIC, 0, offset, key, event), exception);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM chatbot.member_event_failure WHERE source_topic = ? AND source_offset = ?",
                Long.class, TOPIC, offset);
    }

    private Map<String, Object> failure(long id) {
        return jdbcTemplate.queryForMap("SELECT * FROM chatbot.member_event_failure WHERE id = ?", id);
    }

    private static Exception dbDown() {
        return new ListenerExecutionFailedException("리스너 실패",
                new DataAccessResourceFailureException("DB가 응답하지 않음"));
    }

    private static UserDto user(long id, long version, String bio) {
        return new UserDto(id, "회원닉", Role.INFLUENCER, UserStatus.ACTIVE, version, List.of("GAME"), bio);
    }
}
