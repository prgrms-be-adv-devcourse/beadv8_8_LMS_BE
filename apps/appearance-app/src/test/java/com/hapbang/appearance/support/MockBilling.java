package com.hapbang.appearance.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.internal.InternalApiHeaders;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 테스트용 Mock Billing 서버 (#391). 지갑 내부 API(`docs/api/internal-billing.md`) 4개 경로를 WireMock으로 흉내 낸다.
 * 모듈 간 E2E 대신, 출연이 지갑 응답(성공·재전송·거절·지연·장애)에 맞게 움직이는지 이 서버로 확인한다.
 * <ul>
 *     <li>서버는 JVM에서 하나만 띄워 테스트끼리 같이 쓴다. 테스트는 {@link MockBillingIntegrationTest}를 상속한다:
 *     {@code hapbang.clients.billing.base-url}을 이 서버 주소로 넣고(그러면 shared {@code BillingApiClient} 빈이 생긴다),
 *     설정 위치가 한 곳이라 상속한 테스트끼리 Spring 테스트 컨텍스트를 같이 쓴다.</li>
 *     <li>테스트마다 {@link #reset()}을 부른다 (부모 클래스가 부른다). 받은 요청·응답 설정·시나리오를 지우고, 4개 경로 모두 성공으로 되돌린다
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
            if (value == null) {
                return null;
            }
            if (!(value instanceof Number number)) {
                throw new AssertionError("요청 본문의 " + field + "가 숫자가 아닙니다: " + value + " (" + body + ")");
            }
            return number.longValue();
        }

        public String text(String field) {
            Object value = body.get(field);
            return value == null ? null : value.toString();
        }
    }

    /** 서버 주소 (처음 부를 때 띄운다) */
    public static String baseUrl() {
        return server().baseUrl();
    }

    /** 서버 (처음 부를 때 띄운다). 시나리오 메서드는 모두 이 접근자로 서버를 쓴다 */
    private static synchronized WireMockServer server() {
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
            warmUp();
        }
        return server;
    }

    /**
     * 4개 경로를 한 번씩 불러 응답 템플릿을 미리 컴파일하고, 받은 요청 기록은 지운다.
     * 처음 응답은 템플릿 컴파일로 느려서, 짧은 응답 대기 시간을 쓰는 테스트에서 시간 초과가 나고
     * 늦게 끝난 요청이 다음 테스트의 받은 요청에 섞일 수 있다.
     */
    private static void warmUp() {
        String sample = "{\"userId\":1,\"payerUserId\":1,\"referenceType\":\"APPEARANCE_REQUEST\",\"referenceId\":1,"
                + "\"releaseRequestId\":\"WARM-UP\",\"payoutRequestId\":\"WARM-UP\",\"cancellationId\":\"WARM-UP\","
                + "\"receiverUserId\":2,\"sourceType\":\"APPEARANCE_REQUEST\",\"sourceId\":1,\"amountHp\":1,"
                + "\"releaseAmountHp\":1,\"payoutAmountHp\":1}";
        try (HttpClient client = HttpClient.newHttpClient()) {
            for (Endpoint endpoint : Endpoint.values()) {
                HttpRequest request = HttpRequest.newBuilder(URI.create(server.baseUrl() + endpoint.path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(sample))
                        .build();
                client.send(request, HttpResponse.BodyHandlers.discarding());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        server.resetRequests();
    }

    /** 받은 요청·응답 설정·시나리오를 지우고 모든 경로를 성공으로 되돌린다. 테스트마다 부른다 */
    public static void reset() {
        server().resetAll();
        stubAllSucceed();
    }

    /** 이 경로를 성공으로 (요청 값을 돌려주고 {@code replayed = false}) */
    public static void succeed(Endpoint endpoint) {
        server().stubFor(post(urlEqualTo(endpoint.path)).willReturn(success(endpoint, false)));
    }

    /**
     * 같은 요청 ID 재전송 (반환·지급·위약금 정산): 이 요청 ID의 첫 요청은 처리({@code replayed = false}),
     * 같은 ID로 다시 보내면 처음 결과({@code replayed = true}). 다른 요청 ID는 영향을 받지 않는다(각자 처음 처리).
     * 지갑은 같은 멱등 키면 다시 처리하지 않고 처음 결과를 돌려준다.
     * <p>
     * 같은 내용의 재전송만 흉내 낸다. 응답은 이번 요청 값으로 만들어서, 같은 키로 다른 내용(금액 등)을 보내도
     * {@code replayed = true}와 새 값이 돌아간다. 실제 지갑은 이때 409 {@code IDEMPOTENCY_KEY_CONFLICT}이므로,
     * 그 경우는 {@link #fail}로 따로 확인한다.
     */
    public static void replayAfterFirst(Endpoint endpoint, String requestId) {
        if (endpoint.requestIdField == null) {
            throw new IllegalArgumentException("보류는 요청 ID가 아니라 참조 대상이 멱등 키다: replayHoldAfterFirst를 쓴다");
        }
        stubReplay(endpoint, endpoint.name() + "-" + requestId,
                () -> post(urlEqualTo(endpoint.path))
                        .withRequestBody(matchingJsonPath("$." + endpoint.requestIdField, equalTo(requestId))));
    }

    /**
     * 같은 참조 대상 보류 재전송: 첫 요청은 처리, 같은 참조 대상으로 다시 보내면 처음 결과({@code replayed = true}).
     * {@link #replayAfterFirst}처럼 같은 내용의 재전송만 흉내 낸다.
     */
    public static void replayHoldAfterFirst(BillingReferenceType referenceType, long referenceId) {
        stubReplay(Endpoint.HOLD, "HOLD-" + referenceType + "-" + referenceId,
                () -> post(urlEqualTo(Endpoint.HOLD.path))
                        .withRequestBody(matchingJsonPath("$.referenceType", equalTo(referenceType.name())))
                        .withRequestBody(matchingJsonPath("$.referenceId", equalTo(String.valueOf(referenceId)))));
    }

    /**
     * 멱등 키 하나의 시나리오: 처음 → 처리됨. 같은 키가 아닌 요청은 기본 응답(성공)으로 간다.
     * 응답 설정마다 새 {@link MappingBuilder}가 필요해서(같은 것을 두 번 등록하면 ID가 겹친다) 만드는 함수를 받는다.
     */
    private static void stubReplay(Endpoint endpoint, String key, Supplier<MappingBuilder> sameKey) {
        String scenario = REPLAY_SCENARIO + key;
        server().stubFor(sameKey.get().inScenario(scenario)
                .whenScenarioStateIs(Scenario.STARTED).willSetStateTo(PROCESSED)
                .willReturn(success(endpoint, false)));
        server().stubFor(sameKey.get().inScenario(scenario)
                .whenScenarioStateIs(PROCESSED)
                .willReturn(success(endpoint, true)));
    }

    /**
     * 지갑이 오류 코드로 응답 ({@code ProblemDetail}). 4xx면 Client는 {@code Failed}, 5xx면 결과 불명이다.
     *
     * @param code 지갑 오류 코드 (예: {@code INSUFFICIENT_BALANCE}, {@code IDEMPOTENCY_KEY_CONFLICT}). null이면 본문에 코드가 없다
     */
    public static void fail(Endpoint endpoint, int status, String code, String detail) {
        server().stubFor(post(urlEqualTo(endpoint.path)).willReturn(aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "application/problem+json")
                .withBody(problem(status, code, detail))));
    }

    /** 응답을 이만큼 늦게 보낸다. Client 응답 대기 시간보다 길면 결과 불명(시간 초과)이다 */
    public static void delay(Endpoint endpoint, int millis) {
        server().stubFor(post(urlEqualTo(endpoint.path)).willReturn(success(endpoint, false).withFixedDelay(millis)));
    }

    /** 응답 도중 연결이 끊긴다 (요청은 받음). Client는 결과 불명이다 */
    public static void connectionReset(Endpoint endpoint) {
        server().stubFor(post(urlEqualTo(endpoint.path)).willReturn(aResponse()
                .withFault(Fault.CONNECTION_RESET_BY_PEER)));
    }

    /** 이 경로가 받은 요청 (받은 순서) */
    public static List<ReceivedRequest> requests(Endpoint endpoint) {
        return server().findAll(postRequestedFor(urlEqualTo(endpoint.path))).stream()
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
