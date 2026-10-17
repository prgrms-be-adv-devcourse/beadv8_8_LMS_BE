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
// - POST /v1/payments/{paymentKey}/cancel (#260): 승인된 결제를 부분 취소한다. 취소마다 cancels[]에 한 건(transactionKey·cancelAmount·
//   cancelReason·cancelStatus DONE)을 더하고 balanceAmount를 줄인다(전부 취소면 CANCELED, 일부면 PARTIAL_CANCELED). 남은 금액보다
//   많으면 403 NOT_CANCELABLE_AMOUNT. Idempotency-Key가 같은 재요청은 처음 응답을 그대로 돌려준다.
// - GET /v1/payments/{paymentKey} (#260): 승인 이후 결제(취소 이력 포함)를 돌려준다. 없거나 승인 전이면 404 NOT_FOUND_PAYMENT.
// - nextCancel·nextPaymentLookup: 주문별로 다음 취소·결제키 조회 응답의 장애 유형을 정한다.
public class FakeTossPaymentsServer implements AutoCloseable {

    private static final Pattern STRING_FIELD = Pattern.compile("\"(\\w+)\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern NUMBER_FIELD = Pattern.compile("\"(\\w+)\"\\s*:\\s*(-?\\d+)");

    private final HttpServer server;
    private final Map<String, Payment> payments = new ConcurrentHashMap<>();
    private final Map<String, Deque<Fault>> confirmFaults = new ConcurrentHashMap<>();
    private final Map<String, Deque<Fault>> lookupFaults = new ConcurrentHashMap<>();
    private final Map<String, Deque<Fault>> cancelFaults = new ConcurrentHashMap<>();
    private final Map<String, Deque<Response>> cancelRejections = new ConcurrentHashMap<>();
    private final Map<String, Deque<Fault>> paymentLookupFaults = new ConcurrentHashMap<>();
    private final Map<String, List<Cancel>> cancels = new ConcurrentHashMap<>();
    private volatile long cancelDelayMillis;
    private int transactionSequence;
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
        server.createContext("/v1/payments/", this::payment);
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

    // 다음 취소 요청 하나에 장애를 넣는다(주문별 순서대로 하나씩 쓴다, #260).
    public void nextCancel(String orderId, Fault fault) {
        cancelFaults.computeIfAbsent(orderId, key -> new ArrayDeque<>()).add(fault);
    }

    // 다음 취소 요청 하나를 지정한 상태·코드로 거절한다(취소하지 않음, #260 2차).
    public void nextCancelRejection(String orderId, int status, String code) {
        cancelRejections.computeIfAbsent(orderId, key -> new ArrayDeque<>())
                .add(error(status, code, code + " 거절"));
    }

    // 다음 결제키 조회 하나에 장애를 넣는다(#260).
    public void nextPaymentLookup(String orderId, Fault fault) {
        paymentLookupFaults.computeIfAbsent(orderId, key -> new ArrayDeque<>()).add(fault);
    }

    // 모든 취소 응답을 이 시간만큼 늦춘다(동시 요청 재현용, #260).
    public void cancelDelay(long millis) {
        this.cancelDelayMillis = millis;
    }

    // 우리 서버를 거치지 않은 취소(상점 관리자 화면 등)를 넣는다(#260).
    public synchronized void cancelDirectly(String orderId, long amount, String reason) {
        applyCancel(payments.get(orderId), amount, reason);
    }

    // 주문의 취소 이력(#260)
    public synchronized List<Cancel> cancelsOf(String orderId) {
        return List.copyOf(cancels.getOrDefault(orderId, List.of()));
    }

    // 주문의 남은 취소 가능 금액(#260)
    public synchronized long balanceOf(String orderId) {
        return payments.get(orderId).amount() - canceledAmount(orderId);
    }

    // 받은 취소 요청 수(재요청 포함, #260)
    public synchronized long cancelCount() {
        return requests.stream().filter(request -> request.method().equals("POST")
                && request.path().endsWith("/cancel")).count();
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
        cancelFaults.clear();
        cancelRejections.clear();
        paymentLookupFaults.clear();
        cancels.clear();
        cancelDelayMillis = 0;
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
            case NONE, UNREACHABLE, CONFLICT_PROCESSING, RATE_LIMITED, AUTH_ERROR, CANCEL_REJECTED, PROVIDER_ERROR,
                    ALREADY_CANCELED, CANCEL_DIFFERENT_AMOUNT, CANCEL_WITHOUT_MARKER, HIDE_CANCELS,
                    CANCEL_TWICE -> approve(fields);
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

    // /v1/payments/{paymentKey}/cancel(POST)·/v1/payments/{paymentKey}(GET) (#260)
    private void payment(HttpExchange exchange) throws IOException {
        String rest = exchange.getRequestURI().getPath().substring("/v1/payments/".length());
        if ("POST".equals(exchange.getRequestMethod()) && rest.endsWith("/cancel")) {
            cancel(exchange, rest.substring(0, rest.length() - "/cancel".length()));
            return;
        }
        record(exchange, null);
        Payment payment = byPaymentKey(rest);
        Fault fault = poll(paymentLookupFaults, payment == null ? null : payment.orderId());
        if (fault == Fault.SERVER_ERROR) {
            send(exchange, error(500, "FAILED_INTERNAL_SYSTEM_PROCESSING", "내부 시스템 처리 작업이 실패했습니다."));
            return;
        }
        if (fault == Fault.TIMEOUT_NOT_APPROVED) {
            sleep(timeoutMillis);
        }
        boolean notApproved = payment == null || "READY".equals(payment.status())
                || "IN_PROGRESS".equals(payment.status());
        if (!notApproved && fault == Fault.HIDE_CANCELS) {
            String json = payment.json();
            send(exchange, new Response(200, json.substring(0, json.length() - 1) + ",\"cancels\":[]}"));
            return;
        }
        send(exchange, notApproved ? error(404, "NOT_FOUND_PAYMENT", "존재하지 않는 결제 정보 입니다.")
                : new Response(200, paymentJson(payment)));
    }

    private void cancel(HttpExchange exchange, String paymentKey) throws IOException {
        String body = read(exchange.getRequestBody());
        record(exchange, body);
        Map<String, String> fields = fields(body);
        Payment payment = byPaymentKey(paymentKey);
        String idempotencyKey = exchange.getRequestHeaders().getFirst("Idempotency-Key");
        Fault fault = poll(cancelFaults, payment == null ? null : payment.orderId());
        if (fault == Fault.UNREACHABLE) {
            exchange.close();
            return;
        }
        // 취소를 판단하지 않은 응답(처리 중 충돌·요청 한도·일시 오류)은 멱등키 응답으로 기록하지 않는다
        if (fault == Fault.CONFLICT_PROCESSING) {
            send(exchange, error(409, "IDEMPOTENT_REQUEST_PROCESSING", "이전 멱등 요청이 처리 중입니다."));
            return;
        }
        if (fault == Fault.RATE_LIMITED) {
            send(exchange, error(429, "TOO_MANY_REQUESTS", "요청량이 많습니다."));
            return;
        }
        if (fault == Fault.PROVIDER_ERROR) {
            send(exchange, error(400, "PROVIDER_ERROR", "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요."));
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
        sleep(cancelDelayMillis);
        long amount = Long.parseLong(fields.get("cancelAmount"));
        String reason = fields.get("cancelReason");
        Deque<Response> rejections = payment == null ? null : cancelRejections.get(payment.orderId());
        Response rejection = rejections == null ? null : rejections.poll();
        Response response = rejection != null ? rejection : switch (fault) {
            case CANCEL_REJECTED -> error(403, "NOT_CANCELABLE_PAYMENT", "취소 할 수 없는 결제 입니다.");
            case INVALID_REQUEST -> error(400, "INVALID_REQUEST", "잘못된 요청입니다.");
            case SERVER_ERROR -> error(500, "FAILED_INTERNAL_SYSTEM_PROCESSING", "내부 시스템 처리 작업이 실패했습니다.");
            case ALREADY_CANCELED -> error(400, "ALREADY_CANCELED_PAYMENT", "이미 취소된 결제 입니다.");
            case TIMEOUT_NOT_APPROVED -> {
                sleep(timeoutMillis);
                yield error(500, "FAILED_INTERNAL_SYSTEM_PROCESSING", "응답 지연");
            }
            case APPROVE_THEN_TIMEOUT -> {
                Response canceled = cancelPayment(payment, amount, reason);
                sleep(timeoutMillis);
                yield canceled;
            }
            case APPROVE_THEN_SERVER_ERROR -> {
                cancelPayment(payment, amount, reason);
                yield error(500, "FAILED_INTERNAL_SYSTEM_PROCESSING", "내부 시스템 처리 작업이 실패했습니다.");
            }
            // PG가 요청과 다른 금액을 취소했다(이상 응답 재현)
            case CANCEL_DIFFERENT_AMOUNT -> cancelPayment(payment, amount / 2, reason);
            // 같은 취소를 두 번 처리한 응답(PG 중복 취소 재현)
            case CANCEL_TWICE -> {
                cancelPayment(payment, amount, reason);
                yield cancelPayment(payment, amount, reason);
            }
            // 취소하지 않고 취소 이력에 우리 표식이 없는 200 응답(이상 응답 재현)
            case CANCEL_WITHOUT_MARKER -> new Response(200, paymentJson(payment));
            default -> cancelPayment(payment, amount, reason);
        };
        if (idempotencyKey != null) {
            idempotentResponses.put(idempotencyKey, response);
            idempotentInProgress.remove(idempotencyKey);
        }
        send(exchange, response);
    }

    private synchronized Response cancelPayment(Payment payment, long amount, String reason) {
        if (payment == null) {
            return error(404, "NOT_FOUND_PAYMENT", "존재하지 않는 결제 정보 입니다.");
        }
        if ("CANCELED".equals(payment.status())) {
            return error(400, "ALREADY_CANCELED_PAYMENT", "이미 취소된 결제 입니다.");
        }
        if (!"DONE".equals(payment.status()) && !"PARTIAL_CANCELED".equals(payment.status())) {
            return error(403, "NOT_CANCELABLE_PAYMENT", "취소 할 수 없는 결제 입니다.");
        }
        if (amount > payment.amount() - canceledAmount(payment.orderId())) {
            return error(403, "NOT_CANCELABLE_AMOUNT", "취소 할 수 없는 금액 입니다.");
        }
        return new Response(200, paymentJson(applyCancel(payment, amount, reason)));
    }

    private Payment applyCancel(Payment payment, long amount, String reason) {
        cancels.computeIfAbsent(payment.orderId(), key -> new ArrayList<>())
                .add(new Cancel("tcancel_" + (++transactionSequence), amount, reason, "DONE"));
        String status = canceledAmount(payment.orderId()) == payment.amount() ? "CANCELED" : "PARTIAL_CANCELED";
        Payment canceled = new Payment(payment.paymentKey(), payment.orderId(), status, payment.amount());
        payments.put(payment.orderId(), canceled);
        return canceled;
    }

    private long canceledAmount(String orderId) {
        return cancels.getOrDefault(orderId, List.of()).stream().mapToLong(Cancel::amount).sum();
    }

    private Payment byPaymentKey(String paymentKey) {
        return payments.values().stream().filter(payment -> payment.paymentKey().equals(paymentKey)).findFirst()
                .orElse(null);
    }

    // 결제 JSON에 남은 취소 가능 금액과 취소 이력을 넣는다
    private synchronized String paymentJson(Payment payment) {
        StringBuilder history = new StringBuilder("[");
        List<Cancel> list = cancels.getOrDefault(payment.orderId(), List.of());
        for (int i = 0; i < list.size(); i++) {
            Cancel cancel = list.get(i);
            history.append(i == 0 ? "" : ",")
                    .append("{\"transactionKey\":\"").append(cancel.transactionKey())
                    .append("\",\"cancelAmount\":").append(cancel.amount())
                    .append(",\"cancelReason\":\"").append(cancel.reason())
                    .append("\",\"canceledAt\":\"2026-10-15T11:00:00+09:00\",\"cancelStatus\":\"")
                    .append(cancel.status()).append("\"}");
        }
        history.append("]");
        long balance = payment.amount() - canceledAmount(payment.orderId());
        String json = payment.json().replace("\"balanceAmount\":" + payment.amount() + ",",
                "\"balanceAmount\":" + balance + ",");
        return json.substring(0, json.length() - 1) + ",\"cancels\":" + history + "}";
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
        AUTH_ERROR,
        // 취소 거절(403 NOT_CANCELABLE_PAYMENT) — 취소 안 됨 확정 (#260)
        CANCEL_REJECTED,
        // 400 PROVIDER_ERROR(일시 오류) — 취소 여부 모름 (#260)
        PROVIDER_ERROR,
        // 400 ALREADY_CANCELED_PAYMENT(실제 취소 없이) — 우리 취소가 먼저 처리됐을 수 있는 거절 (#260)
        ALREADY_CANCELED,
        // 요청 금액의 절반만 취소한 성공 응답 (#260)
        CANCEL_DIFFERENT_AMOUNT,
        // 취소하지 않고 취소 이력에 우리 표식 없이 200 응답 (#260)
        CANCEL_WITHOUT_MARKER,
        // 결제키 조회에서 취소 이력을 비워 응답(취소 반영 지연 재현, #260)
        HIDE_CANCELS,
        // 같은 취소를 두 번 처리(PG 중복 취소 재현, #260 2차)
        CANCEL_TWICE
    }

    // 취소 이력 한 건 (#260)
    public record Cancel(String transactionKey, long amount, String reason, String status) {
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
