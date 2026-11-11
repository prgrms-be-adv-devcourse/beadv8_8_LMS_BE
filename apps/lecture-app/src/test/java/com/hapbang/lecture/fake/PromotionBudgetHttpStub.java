package com.hapbang.lecture.fake;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.hapbang.shared.billing.dto.PromotionBudgetReserveCommand;
import com.hapbang.shared.internal.InternalApiHeaders;
import com.hapbang.testsupport.IntegrationTest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.json.JsonMapper;

/** 쿠폰·강의 관통 테스트에서 예산 예약 HTTP 계약만 제공한다. 실제 지갑 엔진은 billing 테스트가 검증한다. */
public final class PromotionBudgetHttpStub implements AutoCloseable {
    private final HttpServer server;
    private final JsonMapper json = JsonMapper.builder().build();
    private final List<PromotionBudgetReserveCommand> reservations = new CopyOnWriteArrayList<>();
    private final Map<String, PromotionBudgetReserveCommand> works = new ConcurrentHashMap<>();

    public PromotionBudgetHttpStub() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v1/internal/billing/promotion-budgets", this::handle);
            server.start();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public List<PromotionBudgetReserveCommand> reservations() {
        return List.copyOf(reservations);
    }

    public void reset() {
        reservations.clear();
        works.clear();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body;
        int status = 200;
        if (!IntegrationTest.INTERNAL_API_KEY.equals(exchange.getRequestHeaders().getFirst(InternalApiHeaders.API_KEY))) {
            status = 401;
            body = "{}";
        } else if (exchange.getRequestMethod().equals("POST") && exchange.getRequestURI().getPath().endsWith("/reservations")) {
            var command = json.readValue(exchange.getRequestBody(), PromotionBudgetReserveCommand.class);
            works.put(command.workId(), command);
            reservations.add(command);
            body = """
                    {"statusCode":200,"message":"예산 예약","data":{"workId":"%s","reservationId":"PB-%d",
                     "couponId":%d,"amountHp":%d,"replayed":false}}
                    """.formatted(command.workId(), command.couponId(), command.couponId(), command.amountHp());
        } else if (exchange.getRequestMethod().equals("GET") && exchange.getRequestURI().getPath().contains("/works/")) {
            String workId = exchange.getRequestURI().getPath().substring(exchange.getRequestURI().getPath().lastIndexOf('/') + 1);
            var work = works.get(workId);
            body = work == null
                    ? "{\"statusCode\":200,\"message\":\"없음\",\"data\":{\"workId\":\"" + workId + "\",\"status\":\"NOT_FOUND\"}}"
                    : """
                    {"statusCode":200,"message":"완료","data":{"workId":"%s","kind":"RESERVE","status":"DONE",
                     "reservationId":"PB-%d","amountHp":%d}}
                    """.formatted(workId, work.couponId(), work.amountHp());
        } else {
            status = 404;
            body = "{}";
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
