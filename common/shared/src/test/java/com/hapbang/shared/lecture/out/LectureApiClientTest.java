package com.hapbang.shared.lecture.out;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.hapbang.shared.internal.InternalApiHeaders;
import com.hapbang.shared.lecture.domain.LectureEarningStatus;
import com.hapbang.shared.lecture.domain.LectureEarningType;
import com.hapbang.shared.lecture.domain.LectureObjectionCommandType;
import com.hapbang.shared.lecture.domain.LectureObjectionFailureCode;
import com.hapbang.shared.lecture.domain.LecturePurchaseResult;
import com.hapbang.shared.lecture.dto.CartItemSyncDto;
import com.hapbang.shared.lecture.dto.CartItemSyncPageDto;
import com.hapbang.shared.lecture.dto.LectureContentSyncDto;
import com.hapbang.shared.lecture.dto.LectureEarningAmountDto;
import com.hapbang.shared.lecture.dto.LectureEarningPageDto;
import com.hapbang.shared.lecture.dto.LectureEarningSummaryDto;
import com.hapbang.shared.lecture.dto.LectureObjectionFileRequest;
import com.hapbang.shared.lecture.dto.LectureObjectionFiledDto;
import com.hapbang.shared.lecture.dto.LecturePurchaseSyncDto;
import com.hapbang.shared.lecture.dto.LecturePurchaseSyncPageDto;
import com.hapbang.shared.lecture.dto.LectureReviewSyncDto;
import com.hapbang.shared.lecture.dto.LectureReviewSyncPageDto;
import com.hapbang.shared.lecture.dto.LectureSyncDto;
import com.hapbang.shared.lecture.dto.LectureSyncPageDto;
import com.hapbang.shared.member.dto.WithdrawalBlockReasonDto;
import com.hapbang.shared.member.dto.WithdrawalBlocksDto;
import com.sun.net.httpserver.HttpServer;

// 강의 내부 API Client의 요청 모양·응답 해제·오류 변환을 가짜 HTTP 서버로 확인한다 (#300). 실제 강의 API는 apps 통합 테스트가 부른다.
class LectureApiClientTest {

    private static final String API_KEY = "unit-internal-api-key";

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> methods = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private volatile int status;
    private volatile String contentType;
    private volatile String body;
    private volatile long delayMillis;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestURI() + " key=" + exchange.getRequestHeaders()
                    .getFirst(InternalApiHeaders.API_KEY));
            methods.add(exchange.getRequestMethod());
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            sleep(delayMillis);
            byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            if (contentType != null) {
                exchange.getResponseHeaders().add("Content-Type", contentType);
            }
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        // 시간 초과를 일으킨 지연 응답이 다음 요청을 막지 않게 요청마다 스레드를 쓴다
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void 요약_조회는_userId와_내부_키_헤더를_붙여_보내고_data를_돌려준다() {
        respond(200, "application/json", """
                {"statusCode":200,"message":"판매 수익 요약을 조회했습니다.","data":{
                  "confirmationPending":{"amountHp":10000,"count":1},
                  "scheduled":{"amountHp":20000,"count":2},
                  "onHold":{"amountHp":0,"count":0},
                  "processing":{"amountHp":0,"count":0},
                  "failed":{"amountHp":5000,"count":1},
                  "asOf":"2026-11-05T12:00:00+09:00"}}""");

        LectureEarningSummaryDto summary = client().getEarningSummary(7L);

        assertThat(requests).containsExactly(
                "/api/v1/internal/lectures/earnings/summary?userId=7 key=" + API_KEY);
        assertThat(summary.confirmationPending()).isEqualTo(new LectureEarningAmountDto(10_000L, 1));
        assertThat(summary.scheduled()).isEqualTo(new LectureEarningAmountDto(20_000L, 2));
        assertThat(summary.failed()).isEqualTo(new LectureEarningAmountDto(5_000L, 1));
        assertThat(summary.asOf()).isEqualTo(OffsetDateTime.parse("2026-11-05T12:00:00+09:00"));
    }

    @Test
    void 목록_조회는_상태_여러_개와_페이지를_쿼리로_보낸다() {
        respond(200, "application/json", """
                {"statusCode":200,"message":"판매 수익 목록을 조회했습니다.","data":{
                  "content":[{"sourceType":"LECTURE","sourceId":31,"earningType":"LECTURE_SALE",
                    "title":"방송 장비 세팅 강의","amountHp":10000,"status":"FAILED",
                    "statusUpdatedAt":"2026-11-04T01:00:00+09:00"}],
                  "page":{"size":20,"number":1,"totalElements":21,"totalPages":2}}}""");

        LectureEarningPageDto page = client().getEarnings(7L,
                List.of(LectureEarningStatus.SCHEDULED, LectureEarningStatus.FAILED), 1, 20);

        assertThat(requests).containsExactly("/api/v1/internal/lectures/earnings?userId=7&status=SCHEDULED"
                + "&status=FAILED&page=1&size=20 key=" + API_KEY);
        assertThat(page.page()).isEqualTo(new LectureEarningPageDto.PageMetadata(20, 1, 21, 2));
        assertThat(page.content()).singleElement().satisfies(item -> {
            assertThat(item.sourceId()).isEqualTo(31L);
            assertThat(item.earningType()).isEqualTo(LectureEarningType.LECTURE_SALE);
            assertThat(item.status()).isEqualTo(LectureEarningStatus.FAILED);
            // 응답에서 빠진 null 필드는 null이다
            assertThat(item.payoutScheduledAt()).isNull();
            assertThat(item.statusUpdatedAt()).isEqualTo(OffsetDateTime.parse("2026-11-04T01:00:00+09:00"));
        });
    }

    @Test
    void 상태를_null로_주면_상태_조건_없이_보낸다() {
        respond(200, "application/json", """
                {"statusCode":200,"message":"","data":{"content":[],
                 "page":{"size":20,"number":0,"totalElements":0,"totalPages":0}}}""");

        assertThat(client().getEarnings(7L, null, 0, 20).content()).isEmpty();
        assertThat(requests).containsExactly("/api/v1/internal/lectures/earnings?userId=7&page=0&size=20 key=" + API_KEY);
    }

    @Test
    void ProblemDetail_오류는_code를_담은_예외로_바뀐다() {
        respond(400, "application/problem+json", """
                {"status":400,"detail":"요청 값이 올바르지 않습니다.","code":"INVALID_REQUEST",
                 "errors":[{"field":"status","message":"값이 올바르지 않습니다."}]}""");

        assertThatThrownBy(() -> client().getEarnings(7L, List.of(), 0, 20))
                .isInstanceOfSatisfying(LectureApiCallException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(LectureApiCallException.Reason.REJECTED);
                    assertThat(e.getStatus()).isEqualTo(400);
                    assertThat(e.getCode()).isEqualTo("INVALID_REQUEST");
                });

        // 내부 API 키 오류는 본문이 없다
        respond(401, null, null);
        assertThatThrownBy(() -> client().getEarningSummary(7L))
                .isInstanceOfSatisfying(LectureApiCallException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(LectureApiCallException.Reason.REJECTED);
                    assertThat(e.getStatus()).isEqualTo(401);
                    assertThat(e.getCode()).isNull();
                });
    }

    @Test
    void 연결_실패와_timeout과_5xx는_호출_불가_예외로_바뀐다() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        LectureApiClient refused = client("http://127.0.0.1:" + closedPort);
        assertUnavailable(() -> refused.getEarningSummary(7L), 0);

        respond(200, "application/json", "{\"statusCode\":200,\"message\":\"\",\"data\":null}");
        delayMillis = 1_000L;
        LectureApiClient shortRead = client("http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofMillis(300));
        assertUnavailable(() -> shortRead.getEarningSummary(7L), 0);

        delayMillis = 0;
        respond(503, null, null);
        assertUnavailable(() -> client().getEarningSummary(7L), 503);
    }

    @Test
    void 읽을_수_없는_성공_응답은_호출_불가_예외로_바뀐다() {
        respond(200, "application/json", "{\"statusCode\":200,\"data\":{\"content\":[");
        assertUnavailable(() -> client().getEarnings(7L, List.of(), 0, 20), 200);

        // 모르는 상태 문자열(계약에 없는 값)
        respond(200, "application/json", """
                {"statusCode":200,"message":"","data":{"content":[{"sourceId":1,"status":"NEW_STATUS"}],
                 "page":{"size":20,"number":0,"totalElements":1,"totalPages":1}}}""");
        assertUnavailable(() -> client().getEarnings(7L, List.of(), 0, 20), 200);

        respond(204, null, null);
        assertUnavailable(() -> client().getEarningSummary(7L), 204);
    }

    @Test
    void 필수_필드가_빠진_성공_응답은_호출_불가_예외로_바뀐다() {
        respond(200, "application/json", "{\"statusCode\":200,\"message\":\"\",\"data\":{}}");
        assertUnavailable(() -> client().getEarningSummary(7L), 200);
        assertUnavailable(() -> client().getEarnings(7L, List.of(), 0, 20), 200);

        respond(200, "application/json", """
                {"statusCode":200,"message":"","data":{"content":[{}],
                 "page":{"size":20,"number":0,"totalElements":1,"totalPages":1}}}""");
        assertUnavailable(() -> client().getEarnings(7L, List.of(), 0, 20), 200);
    }

    @Test
    void 서버_오류의_ProblemDetail_code도_예외에_담는다() {
        respond(500, "application/problem+json", "{\"status\":500,\"code\":\"INTERNAL_ERROR\"}");

        assertThatThrownBy(() -> client().getEarningSummary(7L))
                .isInstanceOfSatisfying(LectureApiCallException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(LectureApiCallException.Reason.UNAVAILABLE);
                    assertThat(e.getStatus()).isEqualTo(500);
                    assertThat(e.getCode()).isEqualTo("INTERNAL_ERROR");
                });
    }

    @Test
    void 탈퇴_막힌_이유_조회는_경로와_내부_키를_붙여_보내고_data를_돌려준다() {
        respond(200, "application/json", """
                {"statusCode":200,"message":"탈퇴 확인을 마쳤습니다.","data":{"reasons":[
                  {"code":"LECTURE_ORDER_HOLDING","message":"구매한 강의 2건의 이의 제기 기간이 끝나지 않았어요.",
                   "availableAt":"2026-11-20"},
                  {"code":"LECTURE_ON_SALE","message":"판매 중이거나 검수 대기 중인 강의 1개가 있어요."}]}}""");

        WithdrawalBlocksDto blocks = client().getWithdrawalBlocks(7L);

        assertThat(requests).containsExactly(
                "/api/v1/internal/lectures/users/7/withdrawal-blocks key=" + API_KEY);
        assertThat(methods).containsExactly("GET");
        // 응답에서 빠진 availableAt은 null이다
        assertThat(blocks.reasons()).containsExactly(
                new WithdrawalBlockReasonDto("LECTURE_ORDER_HOLDING", "구매한 강의 2건의 이의 제기 기간이 끝나지 않았어요.",
                        LocalDate.of(2026, 11, 20)),
                WithdrawalBlockReasonDto.of("LECTURE_ON_SALE", "판매 중이거나 검수 대기 중인 강의 1개가 있어요."));
        assertThat(blocks.isBlocked()).isTrue();
    }

    @Test
    void 탈퇴_막힌_이유가_없으면_빈_목록이다() {
        respond(200, "application/json", """
                {"statusCode":200,"message":"탈퇴 확인을 마쳤습니다.","data":{"reasons":[]}}""");

        WithdrawalBlocksDto blocks = client().getWithdrawalBlocks(7L);

        assertThat(blocks.reasons()).isEmpty();
        assertThat(blocks.isBlocked()).isFalse();
    }

    @Test
    void 탈퇴_막힌_이유_조회의_4xx는_거절_예외다() {
        respond(400, "application/problem+json", """
                {"status":400,"detail":"요청 값이 올바르지 않습니다.","code":"INVALID_REQUEST",
                 "errors":[{"field":"userId","message":"값이 올바르지 않습니다."}]}""");
        assertThatThrownBy(() -> client().getWithdrawalBlocks(7L))
                .isInstanceOfSatisfying(LectureApiCallException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(LectureApiCallException.Reason.REJECTED);
                    assertThat(e.getStatus()).isEqualTo(400);
                    assertThat(e.getCode()).isEqualTo("INVALID_REQUEST");
                });

        // 내부 API 키 오류는 본문이 없다
        respond(401, null, null);
        assertThatThrownBy(() -> client().getWithdrawalBlocks(7L))
                .isInstanceOfSatisfying(LectureApiCallException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(LectureApiCallException.Reason.REJECTED);
                    assertThat(e.getStatus()).isEqualTo(401);
                    assertThat(e.getCode()).isNull();
                });
    }

    @Test
    void 탈퇴_막힌_이유_조회의_연결_실패와_5xx와_읽을_수_없는_응답은_호출_불가_예외다() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        LectureApiClient refused = client("http://127.0.0.1:" + closedPort);
        assertUnavailable(() -> refused.getWithdrawalBlocks(7L), 0);

        respond(503, null, null);
        assertUnavailable(() -> client().getWithdrawalBlocks(7L), 503);

        // reasons가 없거나 이유에 code가 없으면 계약에 맞지 않는 응답이다
        respond(200, "application/json", "{\"statusCode\":200,\"message\":\"\",\"data\":{}}");
        assertUnavailable(() -> client().getWithdrawalBlocks(7L), 200);
        respond(200, "application/json", """
                {"statusCode":200,"message":"","data":{"reasons":[{"message":"처리 중인 주문 1건이 있어요."}]}}""");
        assertUnavailable(() -> client().getWithdrawalBlocks(7L), 200);
    }

    @Test
    void 이의_제기_접수는_명령을_JSON으로_POST하고_접수_결과를_돌려준다() {
        respond(200, "application/json", """
                {"statusCode":200,"message":"이의 제기를 접수했습니다.","data":{
                  "commandId":"FILE:12","inquiryId":12,"orderItemId":31}}""");

        LectureObjectionFiledDto filed = client().fileObjection(LectureObjectionFileRequest.of(12L, 31L, 7L, "내용이 다릅니다."));

        assertThat(requests).containsExactly("/api/v1/internal/lectures/objections/file key=" + API_KEY);
        assertThat(methods).containsExactly("POST");
        assertThat(bodies).singleElement().satisfies(sent -> assertThat(sent)
                .contains("\"commandId\":\"FILE:12\"", "\"inquiryId\":12", "\"orderItemId\":31", "\"buyerUserId\":7",
                        "\"reason\":\"내용이 다릅니다.\""));
        assertThat(filed).isEqualTo(new LectureObjectionFiledDto("FILE:12", 12L, 31L));
    }

    @Test
    void 이의_제기_접수_거절은_사유_코드를_담은_거절_예외다() {
        respond(409, "application/problem+json", """
                {"status":409,"detail":"이의 제기 기간이 지났습니다.","code":"OBJECTION_PERIOD_EXPIRED"}""");

        assertThatThrownBy(() -> client().fileObjection(LectureObjectionFileRequest.of(12L, 31L, 7L, "내용이 다릅니다.")))
                .isInstanceOfSatisfying(LectureApiCallException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(LectureApiCallException.Reason.REJECTED);
                    assertThat(LectureObjectionFailureCode.from(e.getCode()))
                            .hasValue(LectureObjectionFailureCode.OBJECTION_PERIOD_EXPIRED);
                });
        // 계약에 없는 코드(요청 값 오류 등)는 이의 제기 거절 사유가 아니다
        assertThat(LectureObjectionFailureCode.from("INVALID_REQUEST")).isEmpty();
        assertThat(LectureObjectionFailureCode.from(null)).isEmpty();
    }

    @Test
    void 접수_요청은_빠진_값이나_빈_사유를_만들_수_없고_명령_ID는_문의_ID로_정해진다() {
        assertThat(LectureObjectionFileRequest.of(12L, 31L, 7L, "사유").commandId()).isEqualTo("FILE:12");
        assertThat(LectureObjectionCommandType.RESOLVE.commandIdOf(12L)).isEqualTo("RESOLVE:12");
        assertThatThrownBy(() -> LectureObjectionFileRequest.of(12L, 31L, 7L, " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LectureObjectionFileRequest("FILE:12", 12L, null, 7L, "사유"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void 강의_동기화_목록은_afterId와_size를_쿼리로_보내고_페이지를_돌려준다() {
        respond(200, "application/json", """
                {"statusCode":200,"message":"강의 동기화 목록을 조회했습니다.","data":{
                  "items":[{"id":11,"sellerUserId":3,"title":"방송 장비 세팅","priceHp":10000,"onSale":true,"deleted":false,
                    "contents":[{"id":21,"title":"1강","sequence":1},{"id":22,"title":"2강","sequence":2}],
                    "version":4}],
                  "nextAfterId":11}}""");

        LectureSyncPageDto page = client().getSyncLectures(10L, 1);

        assertThat(requests).containsExactly(
                "/api/v1/internal/lectures/sync/lectures?afterId=10&size=1 key=" + API_KEY);
        assertThat(methods).containsExactly("GET");
        // 응답에서 빠진 소개는 null이다
        assertThat(page.items()).containsExactly(new LectureSyncDto(11L, 3L, "방송 장비 세팅", null, 10_000L, true, false,
                List.of(new LectureContentSyncDto(21L, "1강", 1), new LectureContentSyncDto(22L, "2강", 2)), 4L));
        assertThat(page.nextAfterId()).isEqualTo(11L);
    }

    @Test
    void 구매_동기화_목록은_afterId가_없으면_처음부터_부르고_마지막_페이지는_nextAfterId가_null이다() {
        respond(200, "application/json", """
                {"statusCode":200,"message":"구매 동기화 목록을 조회했습니다.","data":{
                  "items":[{"orderItemId":31,"buyerUserId":7,"lectureId":11,"result":"PENDING",
                    "orderedAt":"2026-11-05T12:00:00+09:00","version":0}]}}""");

        LecturePurchaseSyncPageDto page = client().getSyncPurchases(null, LecturePurchaseSyncPageDto.MAX_SIZE);

        assertThat(requests).containsExactly(
                "/api/v1/internal/lectures/sync/purchases?size=500 key=" + API_KEY);
        // 결제 전이라 빠진 구매 시각은 null이다
        assertThat(page.items()).containsExactly(new LecturePurchaseSyncDto(31L, 7L, 11L,
                LecturePurchaseResult.PENDING, OffsetDateTime.parse("2026-11-05T12:00:00+09:00"), null, 0L));
        assertThat(page.nextAfterId()).isNull();
    }

    @Test
    void 장바구니_항목과_리뷰_동기화_목록도_같은_규칙으로_부른다() {
        respond(200, "application/json", """
                {"statusCode":200,"message":"","data":{"items":[{"id":41,"userId":7,"lectureId":11,"deleted":true,
                  "addedAt":"2026-11-05T12:00:00+09:00","version":1}],"nextAfterId":41}}""");
        CartItemSyncPageDto cartItems = client().getSyncCartItems(0L, 100);
        respond(200, "application/json", """
                {"statusCode":200,"message":"","data":{"items":[{"id":51,"lectureId":11,"reviewerUserId":7,
                  "rating":5,"counted":false,"deleted":false,"version":2}]}}""");
        LectureReviewSyncPageDto reviews = client().getSyncReviews(40L, 100);

        assertThat(requests).containsExactly(
                "/api/v1/internal/lectures/sync/cart-items?afterId=0&size=100 key=" + API_KEY,
                "/api/v1/internal/lectures/sync/reviews?afterId=40&size=100 key=" + API_KEY);
        assertThat(cartItems.items()).containsExactly(new CartItemSyncDto(41L, 7L, 11L, true,
                OffsetDateTime.parse("2026-11-05T12:00:00+09:00"), 1L));
        assertThat(cartItems.nextAfterId()).isEqualTo(41L);
        assertThat(reviews.items()).containsExactly(new LectureReviewSyncDto(51L, 11L, 7L, 5, false, false, 2L));
        assertThat(reviews.nextAfterId()).isNull();
    }

    @Test
    void 동기화_목록의_빠진_필수_필드와_4xx는_예외로_바뀐다() {
        respond(200, "application/json", """
                {"statusCode":200,"message":"","data":{"items":[{"id":11,"title":"제목","onSale":true,
                  "deleted":false,"contents":[],"version":0}]}}""");
        assertUnavailable(() -> client().getSyncLectures(null, 100), 200);

        respond(400, "application/problem+json", """
                {"status":400,"code":"INVALID_REQUEST","detail":"size는 500 이하여야 합니다."}""");
        assertThatThrownBy(() -> client().getSyncReviews(null, 501))
                .isInstanceOfSatisfying(LectureApiCallException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(LectureApiCallException.Reason.REJECTED);
                    assertThat(e.getCode()).isEqualTo("INVALID_REQUEST");
                });
    }

    private static void assertUnavailable(Runnable call, int status) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(LectureApiCallException.class, e -> {
            assertThat(e.getReason()).isEqualTo(LectureApiCallException.Reason.UNAVAILABLE);
            assertThat(e.getStatus()).isEqualTo(status);
        });
    }

    private void respond(int status, String contentType, String body) {
        this.status = status;
        this.contentType = contentType;
        this.body = body;
    }

    private LectureApiClient client() {
        return client("http://127.0.0.1:" + server.getAddress().getPort());
    }

    // 읽기 timeout은 시간 초과를 시험할 때만 짧게 둔다. 모든 요청에 300ms를 두면 CI 러너에서 클래스의 첫 요청(가짜 서버·클라이언트 클래스 로딩)이
    // 넘겨 Read timed out으로 흔들린다(#559, 2026-11-02 AppearanceApiClientTest).
    private static LectureApiClient client(String baseUrl) {
        return client(baseUrl, Duration.ofSeconds(5));
    }

    private static LectureApiClient client(String baseUrl, Duration readTimeout) {
        return new LectureApiClient(RestClient.builder(),
                new LectureClientProperties(baseUrl, Duration.ofMillis(500), readTimeout), API_KEY);
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
