package com.hapbang.testsupport.billing;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** 실제 서버로 전달한다. 장애 주입은 연결/응답 전달만 끊고 업무 응답을 생성하지 않는다. */
final class BillingTransport {
    private static final Set<String> HOP_HEADERS = Set.of("host", "connection", "content-length", "transfer-encoding",
            "upgrade", "http2-settings", "expect");
    private HttpServer proxy;
    private final int port;
    private final String target;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final AtomicInteger drops = new AtomicInteger();
    private final List<ResponseGate> gates = new ArrayList<>();
    private volatile boolean disconnected;

    BillingTransport(String target) {
        this.target = target;
        try {
            proxy = startServer(0);
            port = proxy.getAddress().getPort();
            Runtime.getRuntime().addShutdownHook(new Thread(this::disconnect, "billing-proxy-cleanup"));
        } catch (IOException failure) { throw new IllegalStateException(failure); }
    }

    private HttpServer startServer(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", this::forward);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return server;
    }

    String baseUrl() { return "http://127.0.0.1:" + port; }
    synchronized void disconnect() {
        disconnected = true;
        if (proxy != null) { proxy.stop(0); proxy = null; }
    }
    synchronized void reconnect() {
        if (proxy == null) {
            try { proxy = startServer(port); }
            catch (IOException failure) { throw new IllegalStateException("지갑 통신 복구 실패", failure); }
        }
        disconnected = false;
    }
    void dropResponses(int count) { drops.set(count); }
    synchronized void afterNextResponse(String suffix, Runnable action) { gates.add(new ResponseGate(suffix, action)); }
    synchronized void reset() { reconnect(); drops.set(0); gates.clear(); }

    private synchronized Runnable takeGate(String path) {
        for (int i = 0; i < gates.size(); i++) {
            if (path.endsWith(gates.get(i).suffix())) return gates.remove(i).action();
        }
        return null;
    }

    private void forward(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (disconnected) return;
            byte[] body = exchange.getRequestBody().readAllBytes();
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(target + exchange.getRequestURI()))
                    .timeout(Duration.ofSeconds(30))
                    .method(exchange.getRequestMethod(), body.length == 0 ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofByteArray(body));
            exchange.getRequestHeaders().forEach((key, values) -> {
                if (!HOP_HEADERS.contains(key.toLowerCase(java.util.Locale.ROOT)))
                    values.forEach(value -> request.header(key, value));
            });
            HttpResponse<byte[]> response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            Runnable gate = takeGate(exchange.getRequestURI().getPath());
            if (gate != null) gate.run();
            boolean drop = drops.getAndUpdate(value -> Math.max(0, value - 1)) > 0;
            response.headers().map().forEach((key, values) -> {
                if (!HOP_HEADERS.contains(key.toLowerCase(java.util.Locale.ROOT)))
                    exchange.getResponseHeaders().put(key, values);
            });
            exchange.sendResponseHeaders(response.statusCode(), response.body().length == 0 ? -1 : response.body().length);
            if (drop) {
                if (response.body().length > 1)
                    exchange.getResponseBody().write(response.body(), 0, response.body().length / 2);
                return;
            }
            if (response.body().length > 0) exchange.getResponseBody().write(response.body());
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException(failure);
        }
    }

    private record ResponseGate(String suffix, Runnable action) { }
}
