package com.hapbang.appearance.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.appearance.app.dto.AppearanceUserBackfillResDto;
import com.hapbang.appearance.domain.AppearanceErrorCode;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.support.FakeMemberServer;
import com.hapbang.appearance.support.FakeMemberServer.Response;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;

/**
 * 기존 회원 Backfill 순회·집계·중단 (#394). 회원 내부 API는 {@link FakeMemberServer}로 실제 HTTP를 부르고,
 * 회원 하나의 반영({@link AppearanceUserSyncUseCase#applySnapshot})은 대역으로 둔다. DB 반영은 E2E가 확인한다.
 */
class AppearanceUserBackfillUseCaseTest {

    private final FakeMemberServer memberServer = new FakeMemberServer();
    private final AppearanceUserSyncUseCase sync = mock(AppearanceUserSyncUseCase.class);
    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
    private final List<Long> applied = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                invocation.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
        when(sync.applySnapshot(any())).thenAnswer(invocation -> {
            applied.add(invocation.<UserDto>getArgument(0).id());
            return AppearanceUserSyncResult.CREATED;
        });
    }

    @AfterEach
    void tearDown() {
        memberServer.close();
    }

    private AppearanceUserBackfillUseCase useCase() {
        return new AppearanceUserBackfillUseCase(memberServer::client, sync, transactionTemplate);
    }

    private static List<UserDto> users(long from, long to) {
        return LongStream.rangeClosed(from, to)
                .mapToObj(id -> new UserDto(id, "회원" + id, Role.INFLUENCER, UserStatus.ACTIVE, 1L))
                .toList();
    }

    private static List<Long> ids(long from, long to) {
        return LongStream.rangeClosed(from, to).boxed().toList();
    }

    @Test
    @DisplayName("처음부터 끝까지 500명씩 ID 오름차순으로 읽어 모두 반영하고 completed다")
    void allPages() {
        memberServer.members(users(1, 900));

        AppearanceUserBackfillResDto result = useCase().run(0, 1_000);

        assertThat(applied).containsExactlyElementsOf(ids(1, 900));
        assertThat(memberServer.requests()).containsExactly("afterId=0 size=500", "afterId=500 size=500");
        assertThat(result.scanned()).isEqualTo(900);
        assertThat(result.created()).isEqualTo(900);
        assertThat(result.lastProcessedId()).isEqualTo(900L);
        assertThat(result.nextAfterId()).isNull();
        assertThat(result.completed()).isTrue();
    }

    @Test
    @DisplayName("limit에서 멈추면 completed = false, nextAfterId로 이어서 실행한다. 회원이 없으면 0건으로 completed다")
    void limitAndResume() {
        memberServer.members(users(1, 150));

        AppearanceUserBackfillResDto first = useCase().run(0, 100);
        assertThat(first.scanned()).isEqualTo(100);
        assertThat(first.completed()).isFalse();
        assertThat(first.nextAfterId()).isEqualTo(100L);

        AppearanceUserBackfillResDto second = useCase().run(first.nextAfterId(), 100);
        assertThat(second.scanned()).isEqualTo(50);
        assertThat(second.completed()).isTrue();
        assertThat(applied).containsExactlyElementsOf(ids(1, 150));

        memberServer.members(List.of());
        assertThat(useCase().run(0, 100)).satisfies(empty -> {
            assertThat(empty.scanned()).isZero();
            assertThat(empty.lastProcessedId()).isNull();
            assertThat(empty.completed()).isTrue();
        });
    }

    @Test
    @DisplayName("반영 결과를 종류별로 세고 scanned = 합계다. 실패한 회원은 failed·failedIds로 남기고 다음 회원을 계속한다")
    void tally() {
        memberServer.members(users(1, 4));
        doAnswer(invocation -> switch (invocation.<UserDto>getArgument(0).id().intValue()) {
            case 1 -> AppearanceUserSyncResult.CREATED;
            case 2 -> AppearanceUserSyncResult.UPDATED;
            case 3 -> throw new IllegalStateException("저장 실패");
            default -> AppearanceUserSyncResult.IGNORED;
        }).when(sync).applySnapshot(any());

        AppearanceUserBackfillResDto result = useCase().run(0, 100);

        assertThat(result.scanned()).isEqualTo(4);
        assertThat(List.of(result.created(), result.updated(), result.ignored(), result.failed()))
                .containsExactly(1L, 1L, 1L, 1L);
        assertThat(result.failedIds()).containsExactly(3L);
        assertThat(result.completed()).isTrue();
    }

    @Test
    @DisplayName("실패 회원 ID는 앞쪽 100개만 담고 failed는 전체 건수다")
    void failedIdsCapped() {
        memberServer.members(users(1, 150));
        doThrow(new IllegalStateException("저장 실패")).when(sync).applySnapshot(any());

        AppearanceUserBackfillResDto result = useCase().run(0, 1_000);

        assertThat(result.failed()).isEqualTo(150);
        assertThat(result.failedIds()).containsExactlyElementsOf(ids(1, 100));
    }

    @Test
    @DisplayName("두 번째 페이지에서 5xx면 멈추고 503 MEMBER_SOURCE_UNAVAILABLE에 첫 페이지 집계·resumeAfterId를 담는다")
    void serverErrorStops() {
        memberServer.members(users(1, 800))
                .respond(query -> "500".equals(query.get("afterId")) ? new Response(503, null) : memberServer.keyset(query));

        assertAbort(AppearanceErrorCode.MEMBER_SOURCE_UNAVAILABLE, 0, 500, 500L);
        assertThat(applied).containsExactlyElementsOf(ids(1, 500));
    }

    @Test
    @DisplayName("503으로 멈춘 뒤에도 실행 중 표시가 풀려, 같은 인스턴스로 resumeAfterId부터 다시 실행하면 이어서 끝낸다")
    void resumesAfterAbort() {
        memberServer.members(users(1, 800))
                .respond(query -> "500".equals(query.get("afterId")) ? new Response(503, null) : memberServer.keyset(query));
        AppearanceUserBackfillUseCase useCase = useCase();
        assertThatThrownBy(() -> useCase.run(0, 1_000)).isInstanceOf(AppearanceUserBackfillException.class);

        memberServer.respond(null);
        AppearanceUserBackfillResDto resumed = useCase.run(500, 1_000);

        assertThat(resumed.completed()).isTrue();
        assertThat(resumed.scanned()).isEqualTo(300);
        assertThat(applied).containsExactlyElementsOf(ids(1, 800));
    }

    @Test
    @DisplayName("4xx(내부 API 키 거절)는 503 MEMBER_SOURCE_REJECTED, 읽을 수 없는 응답은 MEMBER_SOURCE_UNAVAILABLE이다")
    void rejectedAndUnreadable() {
        memberServer.respond(query -> new Response(401, null));
        assertAbort(AppearanceErrorCode.MEMBER_SOURCE_REJECTED, 0, 0, 0L);

        memberServer.respond(query -> new Response(200, "{\"statusCode\":200,\"data\":{\"items\":["));
        assertAbort(AppearanceErrorCode.MEMBER_SOURCE_UNAVAILABLE, 7, 0, 7L);
        verify(sync, never()).applySnapshot(any());
    }

    @Test
    @DisplayName("회원 내부 API 주소가 없어 Client가 없으면 빈 결과를 성공으로 주지 않고 MEMBER_SOURCE_UNAVAILABLE이다")
    void noClient() {
        assertThatThrownBy(() -> new AppearanceUserBackfillUseCase(() -> null, sync, transactionTemplate).run(0, 100))
                .isInstanceOfSatisfying(AppearanceUserBackfillException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(AppearanceErrorCode.MEMBER_SOURCE_UNAVAILABLE));
    }

    @Test
    @DisplayName("키셋 계약 위반(역순·afterId 이하 ID·ID 없음·요청보다 많은 회원·nextAfterId 불일치·빈 페이지의 nextAfterId)은 그 페이지를 반영하지 않고 멈춘다")
    void keysetViolation() {
        // run(10, 100)이므로 afterId 10, 요청 크기 100. 역순·ID 없음은 afterId보다 큰 ID로 만들어 다른 검사에 먼저 걸리지 않게 한다
        List<FakeMemberServer.Response> violations = List.of(
                FakeMemberServer.ok(List.of(users(12, 12).getFirst(), users(11, 11).getFirst()), null),
                FakeMemberServer.ok(users(5, 6), null),
                FakeMemberServer.ok(List.of(users(11, 11).getFirst(),
                        new UserDto(null, "ID 없음", Role.INFLUENCER, UserStatus.ACTIVE, 1L)), null),
                FakeMemberServer.ok(users(11, 111), null),
                FakeMemberServer.ok(users(11, 12), 99L),
                FakeMemberServer.ok(List.of(), 20L));

        for (Response violation : violations) {
            memberServer.respond(query -> violation);
            assertThatThrownBy(() -> useCase().run(10, 100))
                    .isInstanceOfSatisfying(AppearanceUserBackfillException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(AppearanceErrorCode.MEMBER_SOURCE_UNAVAILABLE));
        }
        verify(sync, never()).applySnapshot(any());
    }

    @Test
    @DisplayName("실행 중 다시 실행하면 409 BACKFILL_ALREADY_RUNNING이고, 끝나면 실행 중 표시가 풀린다. limit은 1~1,000만 받는다")
    void alreadyRunning() throws Exception {
        memberServer.members(users(1, 3));
        CountDownLatch release = new CountDownLatch(1);
        memberServer.block(release);
        AppearanceUserBackfillUseCase useCase = useCase();

        CompletableFuture<AppearanceUserBackfillResDto> first = CompletableFuture.supplyAsync(() -> useCase.run(0, 100));
        while (memberServer.requests().isEmpty()) {
            Thread.onSpinWait();
        }
        assertThatThrownBy(() -> useCase.run(0, 100))
                .isInstanceOfSatisfying(AppearanceException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(AppearanceErrorCode.BACKFILL_ALREADY_RUNNING));
        release.countDown();
        assertThat(first.get(10, TimeUnit.SECONDS).scanned()).isEqualTo(3);

        memberServer.block(null);
        assertThat(useCase.run(0, 100).completed()).isTrue();
        assertThatThrownBy(() -> useCase.run(0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> useCase.run(0, 1_001)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> useCase.run(-1, 100)).isInstanceOf(IllegalArgumentException.class);
    }

    private void assertAbort(AppearanceErrorCode code, long afterId, long scanned, long resumeAfterId) {
        assertThatThrownBy(() -> useCase().run(afterId, 1_000))
                .isInstanceOfSatisfying(AppearanceUserBackfillException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(code);
                    assertThat(e.getProperties()).containsEntry("scanned", scanned)
                            .containsEntry("resumeAfterId", resumeAfterId);
                });
    }
}
