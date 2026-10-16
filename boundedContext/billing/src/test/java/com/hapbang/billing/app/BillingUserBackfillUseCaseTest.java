package com.hapbang.billing.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.LongStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.client.RestClient;

import com.hapbang.billing.app.BillingUserSyncUseCase.Outcome;
import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.shared.internal.InternalApiHeaders;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.shared.member.out.MemberApiClient;
import com.hapbang.shared.member.out.MemberClientProperties;
import com.sun.net.httpserver.HttpServer;

// 기존 회원 BillingUser Backfill(#324)의 키셋 순회·처리량 상한·응답 계약 검증·집계·개별 실패 계속·전체 중단·동시 실행 방지를
// 실제 shared MemberApiClient와 가짜 회원 내부 API 서버로 확인한다. 회원 반영(BillingUserSyncUseCase)은 대역이며,
// 실제 DB 반영·버전 비교는 BillingUserBackfillIntegrationTest가 맡는다.
class BillingUserBackfillUseCaseTest {

    private static final String API_KEY = "billing-backfill-unit-key";

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    // 회원 ID 목록으로 키셋 페이지를 만들어 응답한다. respond가 있으면 그 응답을 그대로 쓴다
    private volatile List<Long> memberIds = List.of();
    private volatile Function<Map<String, String>, Response> respond;
    private volatile long delayMillis;
    private volatile CountDownLatch blockRequest;

    private final BillingUserSyncUseCase sync = mock(BillingUserSyncUseCase.class);
    private final List<Long> applied = new CopyOnWriteArrayList<>();

    private record Response(int status, String body) {
    }

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
            requests.add("afterId=" + query.get("afterId") + " size=" + query.get("size") + " key="
                    + exchange.getRequestHeaders().getFirst(InternalApiHeaders.API_KEY));
            await(blockRequest);
            sleep(delayMillis);
            Response response = respond != null ? respond.apply(query) : keyset(query);
            byte[] bytes = response.body() == null ? new byte[0] : response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(response.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        when(sync.apply(any())).thenAnswer(invocation -> {
            UserDto user = invocation.getArgument(0);
            applied.add(user.id());
            return Outcome.CREATED;
        });
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    // ---------------------------------------------------------------------------------------------
    // 키셋 순회·처리량
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("첫 페이지부터 마지막 페이지까지 500명씩 ID 오름차순으로 읽어 모두 반영하고, 마지막 페이지에서 completed다")
    void allPages() {
        memberIds = ids(1, 1_200);

        BillingUserBackfillResult result = useCase().run(0, 5_000);

        assertThat(requests).containsExactly("afterId=0 size=500 key=" + API_KEY, "afterId=500 size=500 key=" + API_KEY,
                "afterId=1000 size=500 key=" + API_KEY);
        assertThat(applied).containsExactlyElementsOf(memberIds);
        assertThat(result.scanned()).isEqualTo(1_200);
        assertThat(result.created()).isEqualTo(1_200);
        assertThat(result.completed()).isTrue();
        assertThat(result.nextAfterId()).isNull();
        assertThat(result.lastProcessedId()).isEqualTo(1_200L);
        assertInvariant(result);
    }

    @Test
    @DisplayName("limit 750이면 500 + 250명만 읽고, 남은 회원이 있으면 completed = false에 nextAfterId로 이어서 실행한다")
    void limitAndResume() {
        memberIds = ids(1, 1_000);

        BillingUserBackfillResult first = useCase().run(0, 750);

        assertThat(requests).extracting(r -> r.split(" key")[0]).containsExactly("afterId=0 size=500",
                "afterId=500 size=250");
        assertThat(first.scanned()).isEqualTo(750);
        assertThat(first.completed()).isFalse();
        assertThat(first.nextAfterId()).isEqualTo(750L);
        assertThat(first.lastProcessedId()).isEqualTo(750L);

        requests.clear();
        BillingUserBackfillResult second = useCase().run(first.nextAfterId(), 750);

        assertThat(requests).extracting(r -> r.split(" key")[0]).containsExactly("afterId=750 size=500");
        assertThat(second.scanned()).isEqualTo(250);
        assertThat(second.completed()).isTrue();
        assertThat(second.nextAfterId()).isNull();
        assertThat(applied).containsExactlyElementsOf(memberIds).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("limit 5,000은 회원이 더 있어도 5,000명에서 멈추고(500명 × 10번), limit은 1~5,000만 받는다")
    void maxLimit() {
        memberIds = ids(1, 6_000);

        BillingUserBackfillResult result = useCase().run(0, BillingUserBackfillUseCase.MAX_LIMIT);

        assertThat(requests).hasSize(10).allSatisfy(r -> assertThat(r).contains("size=500"));
        assertThat(result.scanned()).isEqualTo(5_000);
        assertThat(result.completed()).isFalse();
        assertThat(result.nextAfterId()).isEqualTo(5_000L);
        for (int[] invalid : new int[][] {{-1, 10}, {0, 0}, {0, 5_001}}) {
            assertThatThrownBy(() -> useCase().run(invalid[0], invalid[1]))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("limit이 마지막 페이지와 정확히 맞으면 completed다. 회원이 없으면 0건으로 completed다")
    void exactLastPageAndEmpty() {
        memberIds = ids(1, 500);
        BillingUserBackfillResult exact = useCase().run(0, 500);
        assertThat(exact.completed()).isTrue();
        assertThat(exact.scanned()).isEqualTo(500);

        memberIds = List.of();
        BillingUserBackfillResult empty = useCase().run(0, 5_000);
        assertThat(empty.completed()).isTrue();
        assertThat(empty.scanned()).isZero();
        assertThat(empty.lastProcessedId()).isNull();
        assertThat(empty.nextAfterId()).isNull();
    }

    @ParameterizedTest(name = "회원 {0}명, limit {1}")
    @CsvSource({"0, 100", "1, 100", "100, 100", "101, 100", "5000, 5000", "5001, 5000"})
    @DisplayName("completed·nextAfterId 경계: 회원 내부 API가 nextAfterId를 주지 않으면 limit에 도달해도 completed다. "
            + "남으면 limit번째 회원 ID가 nextAfterId이고, 이어서 실행하면 중복·누락 없이 끝난다(ID는 불연속)")
    void completionBoundary(int members, int limit) {
        // 실제 회원 ID처럼 1부터 연속이지 않다(간격 7, 시작 1,003)
        memberIds = LongStream.range(0, members).map(i -> 1_003 + i * 7).boxed().toList();

        BillingUserBackfillResult first = useCase().run(0, limit);

        int processed = Math.min(members, limit);
        boolean remains = members > limit;
        Long lastProcessed = processed == 0 ? null : memberIds.get(processed - 1);
        assertThat(first.scanned()).isEqualTo(processed);
        assertThat(first.created()).isEqualTo(processed);
        assertThat(first.lastProcessedId()).isEqualTo(lastProcessed);
        assertThat(first.completed()).isEqualTo(!remains);
        assertThat(first.nextAfterId()).isEqualTo(remains ? lastProcessed : null);
        assertThat(applied).containsExactlyElementsOf(memberIds.subList(0, processed));
        // 마지막 페이지(nextAfterId 없음) 뒤에는 더 부르지 않는다: 페이지 수 = ceil(처리 수 / 500), 0명이면 1번
        int pageSize = Math.min(BillingUserBackfillUseCase.PAGE_SIZE, limit);
        assertThat(requests).hasSize(Math.max(1, (processed + pageSize - 1) / pageSize));
        assertThat(requests.getFirst()).startsWith("afterId=0 size=" + pageSize);
        assertInvariant(first);

        if (remains) {
            requests.clear();
            BillingUserBackfillResult rest = useCase().run(first.nextAfterId(), limit);

            assertThat(requests).containsExactly("afterId=" + first.nextAfterId() + " size=" + pageSize + " key="
                    + API_KEY);
            assertThat(rest.scanned()).isEqualTo(members - limit);
            assertThat(rest.completed()).isTrue();
            assertThat(rest.nextAfterId()).isNull();
            assertThat(rest.lastProcessedId()).isEqualTo(memberIds.getLast());
        }
        assertThat(applied).containsExactlyElementsOf(memberIds).doesNotHaveDuplicates();
    }

    // ---------------------------------------------------------------------------------------------
    // 집계·개별 실패
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("반영 결과를 종류별로 세고 scanned = 합계다. 실패한 회원은 failed로 세고 다음 회원을 계속한다")
    void outcomesAndFailures() {
        memberIds = ids(1, 10);
        doAnswer(invocation -> {
            long id = ((UserDto) invocation.getArgument(0)).id();
            applied.add(id);
            return switch ((int) (id % 5)) {
                case 0 -> throw new IllegalStateException("db");
                case 1 -> Outcome.CREATED;
                case 2 -> Outcome.UPDATED;
                case 3 -> Outcome.IGNORED_STALE;
                default -> Outcome.IGNORED_VERSIONLESS;
            };
        }).when(sync).apply(any());

        BillingUserBackfillResult result = useCase().run(0, 5_000);

        assertThat(applied).containsExactlyElementsOf(memberIds);
        assertThat(result.created()).isEqualTo(2);
        assertThat(result.updated()).isEqualTo(2);
        assertThat(result.ignoredStale()).isEqualTo(2);
        assertThat(result.ignoredVersionless()).isEqualTo(2);
        assertThat(result.failed()).isEqualTo(2);
        assertThat(result.failedIds()).containsExactly(5L, 10L);
        assertThat(result.completed()).isTrue();
        assertInvariant(result);
    }

    @Test
    @DisplayName("실패 회원 ID는 앞쪽 100개만 담고 failed는 전체 건수다")
    void failedIdsCap() {
        memberIds = ids(1, 250);
        doThrow(new IllegalStateException("db")).when(sync).apply(any());

        BillingUserBackfillResult result = useCase().run(0, 5_000);

        assertThat(result.failed()).isEqualTo(250);
        assertThat(result.failedIds()).hasSize(100).containsExactlyElementsOf(ids(1, 100));
        assertThat(result.scanned()).isEqualTo(250);
        assertInvariant(result);
    }

    // ---------------------------------------------------------------------------------------------
    // 전체 중단
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("두 번째 페이지에서 5xx면 멈추고 503 MEMBER_SOURCE_UNAVAILABLE에 첫 페이지 집계·resumeAfterId를 담는다(반영한 회원은 유지)")
    void serverErrorStops() {
        memberIds = ids(1, 800);
        respond = query -> "500".equals(query.get("afterId")) ? new Response(503, null) : keyset(query);

        assertAbort(() -> useCase().run(0, 5_000), BillingErrorCode.MEMBER_SOURCE_UNAVAILABLE, 500, 500L, 500L);
        assertThat(applied).containsExactlyElementsOf(ids(1, 500));

        // 처음부터 다시 실행할 수 있다
        respond = null;
        applied.clear();
        assertThat(useCase().run(0, 5_000).scanned()).isEqualTo(800);
    }

    @Test
    @DisplayName("4xx(내부 키 거절)는 MEMBER_SOURCE_REJECTED(503), 시간 초과·읽을 수 없는 응답은 MEMBER_SOURCE_UNAVAILABLE이다")
    void rejectedAndTimeout() {
        respond = query -> new Response(401, null);
        assertAbort(() -> useCase().run(0, 5_000), BillingErrorCode.MEMBER_SOURCE_REJECTED, 0, null, 0L);

        respond = query -> new Response(200, "{\"statusCode\":200,\"data\":{\"items\":[");
        assertAbort(() -> useCase().run(7, 5_000), BillingErrorCode.MEMBER_SOURCE_UNAVAILABLE, 0, null, 7L);

        respond = null;
        memberIds = ids(1, 3);
        delayMillis = 1_000;
        assertAbort(() -> new BillingUserBackfillUseCase(() -> client(Duration.ofMillis(200)), sync).run(0, 5_000),
                BillingErrorCode.MEMBER_SOURCE_UNAVAILABLE, 0, null, 0L);
        verify(sync, never()).apply(any());
    }

    @Test
    @DisplayName("회원 내부 API Client가 없으면 빈 결과를 성공으로 주지 않고 MEMBER_SOURCE_UNAVAILABLE이다")
    void noClient() {
        assertAbort(() -> new BillingUserBackfillUseCase(() -> null, sync).run(0, 5_000),
                BillingErrorCode.MEMBER_SOURCE_UNAVAILABLE, 0, null, 0L);
        assertThat(requests).isEmpty();
    }

    @Test
    @DisplayName("키셋 계약 위반(역순·중복·afterId 이하 ID, nextAfterId 불일치, 빈 페이지의 nextAfterId, 요청보다 많은 회원)은 "
            + "그 페이지를 반영하지 않고 멈춘다")
    void contractViolations() {
        List<String> violations = List.of(
                page("[" + user(3) + "," + user(2) + "]", "2"),          // 역순
                page("[" + user(2) + "," + user(2) + "]", "2"),          // 중복
                page("[" + user(5) + "]", null),                         // afterId(5) 이하
                page("[" + user(6) + "," + user(7) + "]", "6"),          // nextAfterId ≠ 마지막 ID
                page("[]", "9"),                                         // 빈 페이지에 nextAfterId
                page("[" + user(6) + "," + user(7) + "," + user(8) + "]", "8")); // 요청(2)보다 많음
        for (String body : violations) {
            applied.clear();
            respond = query -> new Response(200, body);
            assertAbort(() -> useCase().run(5, 2), BillingErrorCode.MEMBER_SOURCE_UNAVAILABLE, 0, null, 5L);
            assertThat(applied).as(body).isEmpty();
        }
    }

    @Test
    @DisplayName("커서가 전진하지 않는 응답(같은 페이지 반복)은 두 번째 페이지에서 계약 위반으로 멈춘다(무한 반복 없음)")
    void cursorDoesNotAdvance() {
        respond = query -> new Response(200, page("[" + user(1) + "," + user(2) + "]", "2"));

        assertAbort(() -> useCase().run(0, 10), BillingErrorCode.MEMBER_SOURCE_UNAVAILABLE, 2, 2L, 2L);
        assertThat(requests).hasSize(2);
        assertThat(applied).containsExactly(1L, 2L);
    }

    // ---------------------------------------------------------------------------------------------
    // 동시 실행
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("실행 중 다시 실행하면 409 BACKFILL_ALREADY_RUNNING이고, 끝나거나 실패하면 실행 중 표시가 풀린다")
    void concurrentRun() throws Exception {
        memberIds = ids(1, 3);
        blockRequest = new CountDownLatch(1);
        BillingUserBackfillUseCase useCase = useCase();

        CompletableFuture<BillingUserBackfillResult> first = CompletableFuture.supplyAsync(() -> useCase.run(0, 5_000));
        awaitRequests(1);
        assertThatThrownBy(() -> useCase.run(0, 5_000)).isInstanceOfSatisfying(BillingException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(BillingErrorCode.BACKFILL_ALREADY_RUNNING));
        blockRequest.countDown();
        assertThat(first.get(10, TimeUnit.SECONDS).scanned()).isEqualTo(3);
        blockRequest = null;

        // 실패한 실행 뒤에도 다시 실행할 수 있다
        respond = query -> new Response(500, null);
        assertThatThrownBy(() -> useCase.run(0, 5_000)).isInstanceOf(BillingUserBackfillException.class);
        respond = null;
        assertThat(useCase.run(0, 5_000).scanned()).isEqualTo(3);
        // 회원 반영에서 RuntimeException이 아닌 Error가 나도 실행 중 표시는 풀린다
        doThrow(new AssertionError("boom")).when(sync).apply(any());
        assertThatThrownBy(() -> useCase.run(0, 5_000)).isInstanceOf(AssertionError.class);
        doReturn(Outcome.IGNORED_STALE).when(sync).apply(any());
        assertThat(useCase.run(0, 5_000).ignoredStale()).isEqualTo(3);
    }

    // ---------------------------------------------------------------------------------------------

    private void assertAbort(Runnable run, BillingErrorCode code, long scanned, Long lastProcessedId,
            long resumeAfterId) {
        assertThatThrownBy(run::run).isInstanceOfSatisfying(BillingUserBackfillException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(code);
            assertThat(e.getProgress().scanned()).isEqualTo(scanned);
            assertThat(e.getProgress().lastProcessedId()).isEqualTo(lastProcessedId);
            assertThat(e.getProgress().completed()).isFalse();
            assertThat(e.getProgress().nextAfterId()).isEqualTo(resumeAfterId);
            assertThat(e.getProperties()).containsEntry("resumeAfterId", resumeAfterId)
                    .containsEntry("scanned", scanned).containsEntry("lastProcessedId", lastProcessedId);
            assertThat(e.getMessage()).doesNotContain(API_KEY);
        });
    }

    private static void assertInvariant(BillingUserBackfillResult result) {
        assertThat(result.scanned()).isEqualTo(result.created() + result.updated() + result.ignoredStale()
                + result.ignoredVersionless() + result.failed());
    }

    private BillingUserBackfillUseCase useCase() {
        return new BillingUserBackfillUseCase(() -> client(Duration.ofSeconds(5)), sync);
    }

    private MemberApiClient client(Duration readTimeout) {
        return new MemberApiClient(RestClient.builder(), new MemberClientProperties(
                "http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(2), readTimeout), API_KEY);
    }

    // 실제 회원 내부 API처럼 afterId보다 큰 ID를 오름차순으로 size개 주고, 더 있으면 마지막 ID를 nextAfterId로 준다
    private Response keyset(Map<String, String> query) {
        long afterId = query.get("afterId") == null ? 0 : Long.parseLong(query.get("afterId"));
        int size = Integer.parseInt(query.get("size"));
        List<Long> rest = memberIds.stream().filter(id -> id > afterId).toList();
        List<Long> pageIds = rest.subList(0, Math.min(size, rest.size()));
        StringBuilder items = new StringBuilder();
        for (Long id : pageIds) {
            items.append(items.isEmpty() ? "" : ",").append(user(id));
        }
        String next = rest.size() > size ? String.valueOf(pageIds.getLast()) : null;
        return new Response(200, page("[" + items + "]", next));
    }

    private static String page(String items, String nextAfterId) {
        return "{\"statusCode\":200,\"message\":\"ok\",\"data\":{\"items\":" + items
                + (nextAfterId == null ? "" : ",\"nextAfterId\":" + nextAfterId) + "}}";
    }

    private static String user(long id) {
        return "{\"id\":" + id + ",\"nickname\":\"member" + id + "\",\"role\":\"INFLUENCER\",\"status\":\"ACTIVE\","
                + "\"version\":1}";
    }

    private static List<Long> ids(long from, long to) {
        return LongStream.rangeClosed(from, to).boxed().toList();
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> query = new HashMap<>();
        if (raw != null) {
            for (String pair : raw.split("&")) {
                String[] kv = pair.split("=", 2);
                query.put(kv[0], kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
            }
        }
        return query;
    }

    private void awaitRequests(int count) throws InterruptedException {
        for (int i = 0; i < 200 && requests.size() < count; i++) {
            Thread.sleep(25);
        }
        assertThat(requests).hasSizeGreaterThanOrEqualTo(count);
    }

    private static void await(CountDownLatch latch) {
        if (latch == null) {
            return;
        }
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
