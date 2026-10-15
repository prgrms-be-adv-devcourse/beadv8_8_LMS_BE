package com.hapbang.billing.fixture;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

// 테스트용 가짜 TossPayments 코어 API 서버 (#224). 실제 TossPayments를 부르지 않고 승인·조회 응답과 장애를 재현한다.
// - authorize(): 결제창 인증을 마친 결제(READY)를 등록한다.
// - POST /v1/payments/confirm: 등록된 결제의 결제키·금액이 맞으면 DONE으로 바꾸고 Payment를 돌려준다.
//   Idempotency-Key가 같은 재요청은 처음 응답을 그대로, 처리 중이면 409 IDEMPOTENT_REQUEST_PROCESSING을 돌려준다(TossPayments 문서 동작).
// - GET /v1/payments/orders/{orderId}: 승인 이후 상태(DONE·CANCELED 등)의 결제를 돌려준다. 결제가 없거나 아직 승인 전
//   (READY·IN_PROGRESS)이면 404 NOT_FOUND_PAYMENT다(실제 TossPayments 동작, 2026-10-15 실제 테스트 결제로 확인).
// - nextConfirm·nextLookup: 주문별로 다음 응답의 장애 유형을 정한다(거절·5xx·응답 지연 등).
public class FakeTossPaymentsServer implements AutoCloseable {

    private static final Pattern STRING_FIELD = Pattern.compile("\"(\\w+)\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern NUMBER_FIELD = Pattern.compile("\"(\\w+)\"\\s*:\\s*(-?\\d+)");

    private final HttpServer server;
    private final Map<String, Payment> payments = new ConcurrentHashMap<>();
    private final Map<String, Deque<Fault>> confirmFaults = new ConcurrentHashMap<>();
    private final Map<String, Deque<Fault>> lookupFaults = new ConcurrentHashMap<>();
    private final Map<String, Response> idempotentResponses = new ConcurrentHashMap<>();
    private final Map<String, Boolean> idempotentInProgress = new ConcurrentHashMap<>();
    private final List<Request> requests = new ArrayList<>();
    private volatile long confirmDelayMillis;
    private volatile long timeoutMillis = 2_000L;

    public FakeTossPaymentsServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
        server.createContext("/v1/payments/confirm", this::confirm);
        server.createContext("/v1/payments/orders/", this::lookup);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    // 결제창 인증을 마친 결제(승인 전 READY)를 등록한다.
    public void authorize(String orderId, String paymentKey, long amount) {
        payments.put(orderId, new Payment(paymentKey, orderId, "READY", amount));
    }

    // 등록된 결제의 상태를 바꾼다(PG에서 취소·만료된 경우 등).
    public void setStatus(String orderId, String status) {
        Payment payment = payments.get(orderId);
        payments.put(orderId, new Payment(payment.paymentKey(), orderId, status, payment.amount()));
    }

    // 결제 세션이 만료되었다(인증 후 승인 없이 10분). 승인 요청은 404 NOT_FOUND_PAYMENT_SESSION, 조회는 404다.
    public void expireSession(String orderId) {
        payments.remove(orderId);
    }

    public String statusOf(String orderId) {
        return payments.get(orderId).status();
    }

    // 다음 승인 요청 하나에 장애를 넣는다(주문별 순서대로 하나씩 쓴다).
    public void nextConfirm(String orderId, Fault fault) {
        confirmFaults.computeIfAbsent(orderId, key -> new ArrayDeque<>()).add(fault);
    }

    // 다음 조회 요청 하나에 장애를 넣는다.
    public void nextLookup(String orderId, Fault fault) {
        lookupFaults.computeIfAbsent(orderId, key -> new ArrayDeque<>()).add(fault);
    }

    // 모든 승인 응답을 이 시간만큼 늦춘다(동시 요청 재현용).
    public void confirmDelay(long millis) {
        this.confirmDelayMillis = millis;
    }

    // TIMEOUT 장애가 응답을 미루는 시간. 클라이언트 읽기 시간 제한보다 길게 둔다.
    public void timeoutMillis(long millis) {
        this.timeoutMillis = millis;
    }

    public synchronized List<Request> requests() {
        return List.copyOf(requests);
    }

    public synchronized long count(String method, String path) {
        return requests.stream().filter(request -> request.method().equals(method) && request.path().equals(path))
                .count();
    }

    public long confirmCount() {
        return count("POST", "/v1/payments/confirm");
    }

    public synchronized void reset() {
        payments.clear();
        confirmFaults.clear();
        lookupFaults.clear();
        idempotentResponses.clear();
        idempotentInProgress.clear();
        requests.clear();
        confirmDelayMillis = 0;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void confirm(HttpExchange exchange) throws IOException {
        String body = read(exchange.getRequestBody());
        record(exchange, body);
        Map<String, String> fields = fields(body);
        String orderId = fields.get("orderId");
        String idempotencyKey = exchange.getRequestHeaders().getFirst("Idempotency-Key");
        Fault fault = poll(confirmFaults, orderId);
        if (fault == Fault.UNREACHABLE) {
            // 요청이 처리되지 않은 것처럼 응답 없이 연결을 끊는다(멱등키 기록도 남기지 않는다)
            exchange.close();
            return;
        }
        // 결제를 판단하지 않은 응답(처리 중 충돌·요청 한도·인증 키 오류)은 멱등키 응답으로 기록하지 않는다
        if (fault == Fault.CONFLICT_PROCESSING) {
            send(exchange, error(409, "IDEMPOTENT_REQUEST_PROCESSING", "이전 멱등 요청이 처리 중입니다."));
            return;
        }
        if (fault == Fault.RATE_LIMITED) {
            send(exchange, error(429, "TOO_MANY_REQUESTS", "요청량이 많습니다."));
            return;
        }
        if (fault == Fault.AUTH_ERROR) {
            send(exchange, error(401, "UNAUTHORIZED_KEY", "인증되지 않은 시크릿 키 혹은 클라이언트 키 입니다."));
            return;
        }
        if (idempotencyKey != null) {
            Response cached = idempotentResponses.get(idempotencyKey);
            if (cached != null) {
                send(exchange, cached);
                return;
            }
            if (idempotentInProgress.putIfAbsent(idempotencyKey, true) != null) {
                send(exchange, error(409, "IDEMPOTENT_REQUEST_PROCESSING", "이전 멱등 요청이 처리 중입니다."));
                return;
            }
        }
        sleep(confirmDelayMillis);
        Response response = switch (fault) {
            case REJECT_CARD -> error(403, "REJECT_CARD_PAYMENT", "한도초과 혹은 잔액부족으로 결제에 실패했습니다.");
            case INVALID_REQUEST -> error(400, "INVALID_REQUEST", "잘못된 요청입니다.");
            case SERVER_ERROR -> error(500, "FAILED_INTERNAL_SYSTEM_PROCESSING", "내부 시스템 처리 작업이 실패했습니다.");
            case ALREADY_PROCESSED -> error(400, "ALREADY_PROCESSED_PAYMENT", "이미 처리된 결제 입니다.");
            case TIMEOUT_NOT_APPROVED -> {
                sleep(timeoutMillis);
                yield error(500, "FAILED_INTERNAL_SYSTEM_PROCESSING", "응답 지연");
            }
            case APPROVE_THEN_TIMEOUT -> {
                Response approved = approve(fields);
                sleep(timeoutMillis);
                yield approved;
            }
            case APPROVE_THEN_SERVER_ERROR -> {
                approve(fields);
                yield error(500, "UNKNOWN_PAYMENT_ERROR", "결제에 실패했어요.");
            }
            case NONE, UNREACHABLE, CONFLICT_PROCESSING, RATE_LIMITED, AUTH_ERROR -> approve(fields);
        };
        if (idempotencyKey != null) {
            idempotentResponses.put(idempotencyKey, response);
            idempotentInProgress.remove(idempotencyKey);
        }
        send(exchange, response);
    }

    private Response approve(Map<String, String> fields) {
        Payment payment = payments.get(fields.get("orderId"));
        if (payment == null) {
            return error(404, "NOT_FOUND_PAYMENT_SESSION", "결제 시간이 만료되어 결제 진행 데이터가 존재하지 않습니다.");
        }
        if (!payment.paymentKey().equals(fields.get("paymentKey"))
                || payment.amount() != Long.parseLong(fields.get("amount"))) {
            return error(400, "INVALID_REQUEST", "결제 정보가 일치하지 않습니다.");
        }
        if (!"READY".equals(payment.status()) && !"IN_PROGRESS".equals(payment.status())) {
            return error(400, "ALREADY_PROCESSED_PAYMENT", "이미 처리된 결제 입니다.");
        }
        Payment done = new Payment(payment.paymentKey(), payment.orderId(), "DONE", payment.amount());
        payments.put(done.orderId(), done);
        return new Response(200, done.json());
    }

    private void lookup(HttpExchange exchange) throws IOException {
        record(exchange, null);
        String orderId = exchange.getRequestURI().getPath().substring("/v1/payments/orders/".length());
        Fault fault = poll(lookupFaults, orderId);
        if (fault == Fault.SERVER_ERROR) {
            send(exchange, error(500, "FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING", "결제가 완료되지 않았어요."));
            return;
        }
        if (fault == Fault.TIMEOUT_NOT_APPROVED) {
            sleep(timeoutMillis);
        }
        Payment payment = payments.get(orderId);
        boolean notApproved = payment == null || "READY".equals(payment.status())
                || "IN_PROGRESS".equals(payment.status());
        send(exchange, notApproved ? error(404, "NOT_FOUND_PAYMENT", "존재하지 않는 결제 정보 입니다.")
                : new Response(200, payment.json()));
    }

    private synchronized void record(HttpExchange exchange, String body) {
        requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("Idempotency-Key"), body));
    }

    private static Fault poll(Map<String, Deque<Fault>> faults, String orderId) {
        Deque<Fault> queue = orderId == null ? null : faults.get(orderId);
        if (queue == null) {
            return Fault.NONE;
        }
        synchronized (queue) {
            Fault fault = queue.poll();
            return fault == null ? Fault.NONE : fault;
        }
    }

    private static Response error(int status, String code, String message) {
        return new Response(status, "{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }

    private static void send(HttpExchange exchange, Response response) throws IOException {
        byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        try {
            exchange.sendResponseHeaders(response.status(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (IOException ignored) {
            // 클라이언트가 시간 초과로 연결을 끊은 경우
        } finally {
            exchange.close();
        }
    }

    private static String read(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static Map<String, String> fields(String body) {
        Map<String, String> fields = new ConcurrentHashMap<>();
        Matcher strings = STRING_FIELD.matcher(body);
        while (strings.find()) {
            fields.put(strings.group(1), strings.group(2));
        }
        Matcher numbers = NUMBER_FIELD.matcher(body);
        while (numbers.find()) {
            fields.put(numbers.group(1), numbers.group(2));
        }
        return fields;
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    // 다음 응답에 넣을 장애 유형
    public enum Fault {
        NONE,
        // 카드사 거절(403 REJECT_CARD_PAYMENT) — 승인 안 됨 확정
        REJECT_CARD,
        // 잘못된 요청(400) — 승인 안 됨 확정
        INVALID_REQUEST,
        // 500 — 승인 여부 모름
        SERVER_ERROR,
        // 400 ALREADY_PROCESSED_PAYMENT — 승인 여부 모름
        ALREADY_PROCESSED,
        // 승인하지 않고 응답을 늦춤(클라이언트 시간 초과)
        TIMEOUT_NOT_APPROVED,
        // 승인한 뒤 응답을 늦춤(PG 승인 성공 + 응답 유실)
        APPROVE_THEN_TIMEOUT,
        // 승인한 뒤 500으로 응답
        APPROVE_THEN_SERVER_ERROR,
        // 요청이 TossPayments에 닿지 않음(응답 없이 연결 종료, 멱등키 기록 없음)
        UNREACHABLE,
        // 409 IDEMPOTENT_REQUEST_PROCESSING(같은 멱등키 요청 처리 중) — 결제 판단 없음, 멱등키 기록 없음
        CONFLICT_PROCESSING,
        // 429 요청 한도 초과 — 결제 판단 없음
        RATE_LIMITED,
        // 401 UNAUTHORIZED_KEY(시크릿 키 설정 오류) — 결제 판단 없음
        AUTH_ERROR
    }

    public record Request(String method, String path, String authorization, String idempotencyKey, String body) {
    }

    private record Response(int status, String body) {
    }

    private record Payment(String paymentKey, String orderId, String status, long amount) {

        String json() {
            return "{\"mId\":\"tosspayments\",\"version\":\"2022-11-16\",\"paymentKey\":\"" + paymentKey
                    + "\",\"orderId\":\"" + orderId + "\",\"orderName\":\"HP 충전\",\"status\":\"" + status
                    + "\",\"totalAmount\":" + amount + ",\"balanceAmount\":" + amount
                    + ",\"method\":\"카드\",\"approvedAt\":\"2026-10-15T10:00:00+09:00\"}";
        }
    }
}
