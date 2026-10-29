package com.hapbang.appearance.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;

import org.springframework.test.context.DynamicPropertyRegistry;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.hapbang.shared.internal.InternalApiHeaders;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 테스트용 Mock Billing 서버 (#391). 지갑 내부 API(`docs/api/internal-billing.md`) 4개 경로를 WireMock으로 흉내 낸다.
 * 모듈 간 E2E 대신, 출연이 지갑 응답(성공·재전송·거절·지연·장애)에 맞게 움직이는지 이 서버로 확인한다.
 * <ul>
 *     <li>서버는 JVM에서 하나만 띄워 테스트끼리 같이 쓴다. 테스트 컨텍스트에는 {@link #registerProperties}로
 *     {@code hapbang.clients.billing.base-url}을 이 서버 주소로 넣는다 (그러면 shared {@code BillingApiClient} 빈이 생긴다).</li>
 *     <li>테스트마다 {@link #reset()}을 부른다. 받은 요청·응답 설정·시나리오를 지우고, 4개 경로 모두 성공으로 되돌린다
 *     (Mockito 대역 {@link BillingStubs}의 기본값과 같다).</li>
 *     <li>성공 응답 본문은 {@code src/test/resources/mock-billing/__files}의 계약 파일이고, 요청 값을 그대로 돌려준다.
 *     실패 응답은 지갑과 같은 {@code ProblemDetail}(+ {@code code})이다.</li>
 *     <li>받은 요청은 {@link #requests}로 꺼내 경로·내부 API 키·요청 ID·금액을 확인한다 ({@link BillingCalls}의 HTTP판).</li>
 * </ul>
 */
public final class MockBilling {

    private static final String FILES_ROOT = "mock-billing";
    private static final String TEMPLATE = "response-template";
    private static final String REPLAY_SCENARIO = "replay-";
    private static final String PROCESSED = "processed";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static WireMockServer server;

    private MockBilling() {
    }

    /** 지갑 내부 API 경로. 요청 ID 필드는 경로마다 다르다 (보류는 참조 대상이 멱등 키라 요청 ID 필드가 없다) */
    public enum Endpoint {
        HOLD("/api/v1/internal/billing/holds", "hold-success.json", null),
        RELEASE("/api/v1/internal/billing/releases", "release-success.json", "releaseRequestId"),
        PAYOUT("/api/v1/internal/billing/payouts", "payout-success.json", "payoutRequestId"),
        PENALTY("/api/v1/internal/billing/penalty-settlements", "penalty-success.json", "cancellationId");

        private final String path;
        private final String successFile;
        private final String requestIdField;

        Endpoint(String path, String successFile, String requestIdField) {
            this.path = path;
            this.successFile = successFile;
            this.requestIdField = requestIdField;
        }

        public String path() {
            return path;
        }
    }

    /** Mock이 받은 요청 하나 */
    public record ReceivedRequest(String path, String apiKey, String contentType, Map<String, Object> body) {

        /** 요청 ID (반환 {@code releaseRequestId}, 지급 {@code payoutRequestId}, 위약금 {@code cancellationId}). 보류면 null */
        public String requestId(Endpoint endpoint) {
            return endpoint.requestIdField == null ? null : (String) body.get(endpoint.requestIdField);
        }

        /** 숫자 필드 (없거나 null이면 null) */
        public Long number(String field) {
            Object value = body.get(field);
            return value == null ? null : ((Number) value).longValue();
        }

        public String text(String field) {
            Object value = body.get(field);
            return value == null ? null : value.toString();
        }
    }

    /** 서버 주소 (처음 부를 때 띄운다) */
    public static synchronized String baseUrl() {
        if (server == null) {
            server = new WireMockServer(WireMockConfiguration.options()
                    .dynamicPort()
                    .bindAddress("127.0.0.1")
                    .usingFilesUnderClasspath(FILES_ROOT)
                    .templatingEnabled(true)
                    .globalTemplating(false));
            server.start();
            Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
            stubAllSucceed();
        }
        return server.baseUrl();
    }

    /**
     * 테스트 컨텍스트에 Mock 주소를 넣는다. {@code @DynamicPropertySource} 메서드에서 부른다.
     *
     * @param readTimeout Client 응답 대기 시간 (예: "1s"). 지연 시나리오를 빨리 끝내려고 짧게 준다
     */
    public static void registerProperties(DynamicPropertyRegistry registry, String readTimeout) {
        registry.add("hapbang.clients.billing.base-url", MockBilling::baseUrl);
        registry.add("hapbang.clients.billing.read-timeout", () -> readTimeout);
    }

    /** 받은 요청·응답 설정·시나리오를 지우고 모든 경로를 성공으로 되돌린다. 테스트마다 부른다 */
    public static void reset() {
        baseUrl();
        server.resetAll();
        stubAllSucceed();
    }

    /** 이 경로를 성공으로 (요청 값을 돌려주고 {@code replayed = false}) */
    public static void succeed(Endpoint endpoint) {
        server.stubFor(post(urlEqualTo(endpoint.path)).willReturn(success(endpoint, false)));
    }

    /**
     * 같은 요청 ID 재전송: 첫 요청은 처리({@code replayed = false}), 그 뒤 요청은 처음 결과({@code replayed = true}).
     * 지갑은 같은 멱등 키면 다시 처리하지 않고 처음 결과를 돌려준다.
     */
    public static void replayAfterFirst(Endpoint endpoint) {
        String scenario = REPLAY_SCENARIO + endpoint.name();
        server.stubFor(post(urlEqualTo(endpoint.path)).inScenario(scenario)
                .whenScenarioStateIs(Scenario.STARTED).willSetStateTo(PROCESSED)
                .willReturn(success(endpoint, false)));
        server.stubFor(post(urlEqualTo(endpoint.path)).inScenario(scenario)
                .whenScenarioStateIs(PROCESSED)
                .willReturn(success(endpoint, true)));
    }

    /**
     * 지갑이 오류 코드로 응답 ({@code ProblemDetail}). 4xx면 Client는 {@code Failed}, 5xx면 결과 불명이다.
     *
     * @param code 지갑 오류 코드 (예: {@code INSUFFICIENT_BALANCE}, {@code IDEMPOTENCY_KEY_CONFLICT}). null이면 본문에 코드가 없다
     */
    public static void fail(Endpoint endpoint, int status, String code, String detail) {
        server.stubFor(post(urlEqualTo(endpoint.path)).willReturn(aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "application/problem+json")
                .withBody(problem(status, code, detail))));
    }

    /** 응답을 이만큼 늦게 보낸다. Client 응답 대기 시간보다 길면 결과 불명(시간 초과)이다 */
    public static void delay(Endpoint endpoint, int millis) {
        server.stubFor(post(urlEqualTo(endpoint.path)).willReturn(success(endpoint, false).withFixedDelay(millis)));
    }

    /** 응답 도중 연결이 끊긴다 (요청은 받음). Client는 결과 불명이다 */
    public static void connectionReset(Endpoint endpoint) {
        server.stubFor(post(urlEqualTo(endpoint.path)).willReturn(aResponse()
                .withFault(Fault.CONNECTION_RESET_BY_PEER)));
    }

    /** 이 경로가 받은 요청 (받은 순서) */
    public static List<ReceivedRequest> requests(Endpoint endpoint) {
        return server.findAll(postRequestedFor(urlEqualTo(endpoint.path))).stream()
                .map(MockBilling::toReceived)
                .toList();
    }

    /**
     * 아무것도 듣지 않는 주소 (연결 거부 시나리오용). 이 주소로 만든 Client는 지갑에 닿지 못한다.
     * 포트를 잠깐 열었다 닫아 얻으므로 다른 프로세스가 바로 그 포트를 쓰지 않는 한 연결이 거부된다.
     */
    public static String refusedBaseUrl() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return "http://127.0.0.1:" + socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void stubAllSucceed() {
        for (Endpoint endpoint : Endpoint.values()) {
            succeed(endpoint);
        }
    }

    private static ResponseDefinitionBuilder success(Endpoint endpoint, boolean replayed) {
        return aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBodyFile(endpoint.successFile)
                .withTransformers(TEMPLATE)
                .withTransformerParameter("replayed", replayed);
    }

    private static String problem(int status, String code, String detail) {
        StringBuilder json = new StringBuilder("{\"type\":\"about:blank\",\"status\":").append(status);
        if (detail != null) {
            json.append(",\"detail\":").append(JSON.writeValueAsString(detail));
        }
        if (code != null) {
            json.append(",\"code\":").append(JSON.writeValueAsString(code));
        }
        return json.append('}').toString();
    }

    private static ReceivedRequest toReceived(LoggedRequest request) {
        Map<String, Object> body = JSON.readValue(request.getBodyAsString(), new TypeReference<>() {
        });
        return new ReceivedRequest(request.getUrl(), request.getHeader(InternalApiHeaders.API_KEY),
                request.getHeader("Content-Type"), body);
    }
}
