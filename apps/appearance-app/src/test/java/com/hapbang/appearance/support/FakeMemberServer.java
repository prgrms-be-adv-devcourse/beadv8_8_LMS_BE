package com.hapbang.appearance.support;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.web.client.RestClient;

import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.shared.member.out.MemberApiClient;
import com.hapbang.shared.member.out.MemberClientProperties;
import com.sun.net.httpserver.HttpServer;

/**
 * 회원 내부 API 대역 (기존 회원 Backfill 테스트, #394). JDK HTTP 서버로 {@code GET /api/v1/internal/members}를 흉내 내,
 * 운영 {@link MemberApiClient}가 실제 HTTP로 부르게 한다(오류 변환까지 운영과 같다).
 * <ul>
 *     <li>기본: {@link #members}를 회원 ID 오름차순 키셋 페이지로 돌려준다(afterId 초과, size개, 마지막 페이지는 nextAfterId 없음).</li>
 *     <li>{@link #respond}가 있으면 그 응답을 그대로 쓴다(5xx·4xx·깨진 응답·계약 위반).</li>
 *     <li>{@link #block}이 있으면 응답 전에 기다린다(동시 실행 확인).</li>
 * </ul>
 */
public final class FakeMemberServer implements AutoCloseable {

    public record Response(int status, String body) {
    }

    private final HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private volatile List<UserDto> members = List.of();
    private volatile Function<Map<String, String>, Response> respond;
    private volatile CountDownLatch block;

    public FakeMemberServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", exchange -> {
            Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
            requests.add("afterId=" + query.get("afterId") + " size=" + query.get("size"));
            await(block);
            Function<Map<String, String>, Response> custom = respond;
            Response response = custom != null ? custom.apply(query) : keyset(query);
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
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** 이 서버를 부르는 운영 Client */
    public MemberApiClient client() {
        return new MemberApiClient(RestClient.builder(),
                new MemberClientProperties(baseUrl(), Duration.ofSeconds(2), Duration.ofSeconds(2)), "test-internal-key");
    }

    public FakeMemberServer members(List<UserDto> members) {
        this.members = List.copyOf(members);
        return this;
    }

    public FakeMemberServer respond(Function<Map<String, String>, Response> respond) {
        this.respond = respond;
        return this;
    }

    public FakeMemberServer block(CountDownLatch block) {
        this.block = block;
        return this;
    }

    /** 받은 요청 ("afterId=0 size=500" 꼴) */
    public List<String> requests() {
        return List.copyOf(requests);
    }

    public void reset() {
        members = List.of();
        respond = null;
        block = null;
        requests.clear();
    }

    /** 회원 원본 기본 키셋 응답. 테스트가 계약 위반 응답을 만들 때도 쓴다 */
    public Response keyset(Map<String, String> query) {
        long afterId = query.get("afterId") == null ? 0 : Long.parseLong(query.get("afterId"));
        int size = Integer.parseInt(query.get("size"));
        List<UserDto> page = members.stream().filter(user -> user.id() > afterId).limit(size + 1L).toList();
        boolean hasNext = page.size() > size;
        List<UserDto> items = hasNext ? page.subList(0, size) : page;
        return ok(items, hasNext ? items.getLast().id() : null);
    }

    /** 성공 응답(RsData) 본문 */
    public static Response ok(List<UserDto> items, Long nextAfterId) {
        String users = items.stream()
                .map(user -> "{\"id\":%d,\"nickname\":\"%s\",\"role\":\"%s\",\"status\":\"%s\",\"version\":%s}"
                        .formatted(user.id(), user.nickname(), user.role(), user.status(), user.version()))
                .collect(Collectors.joining(","));
        String next = nextAfterId == null ? "" : ",\"nextAfterId\":" + nextAfterId;
        return new Response(200, "{\"statusCode\":200,\"message\":\"ok\",\"data\":{\"items\":[" + users + "]" + next + "}}");
    }

    @Override
    public void close() {
        server.stop(0);
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

    private static Map<String, String> query(String raw) {
        Map<String, String> query = new HashMap<>();
        if (raw == null) {
            return query;
        }
        for (String pair : raw.split("&")) {
            String[] kv = pair.split("=", 2);
            query.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                    kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return query;
    }
}
