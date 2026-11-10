package com.hapbang.auxiliary.notification.support;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * 시험용 웹소켓 연결. STOMP 클라이언트 없이 프레임 문자열을 직접 주고받아 서버가 보낸 프레임과 종료 코드를 그대로 본다.
 */
public final class RawStomp {

    public static final String ORIGIN = "http://localhost:5173";

    private final WebSocketSession session;
    private final BlockingQueue<String> frames;
    private final CompletableFuture<CloseStatus> closed;

    private RawStomp(WebSocketSession session, BlockingQueue<String> frames, CompletableFuture<CloseStatus> closed) {
        this.session = session;
        this.frames = frames;
        this.closed = closed;
    }

    /** 허용 출처({@value #ORIGIN})로 연결한다. 핸드셰이크가 거절되면 예외(메시지에 상태 코드가 들어 있다) */
    public static RawStomp open(String url) throws Exception {
        return open(url, ORIGIN);
    }

    /** 정한 출처로 연결한다 */
    public static RawStomp open(String url, String origin) throws Exception {
        BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        CompletableFuture<CloseStatus> closed = new CompletableFuture<>();
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setOrigin(origin);
        WebSocketSession session = new StandardWebSocketClient().execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession s, TextMessage message) {
                frames.add(message.getPayload());
            }

            @Override
            public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
                closed.complete(status);
            }
        }, headers, URI.create(url)).get(5, TimeUnit.SECONDS);
        return new RawStomp(session, frames, closed);
    }

    /** CONNECT 뒤 CONNECTED를 받고 {@code destination}을 구독한다 */
    public static RawStomp connectAndSubscribe(String url, String destination) throws Exception {
        RawStomp stomp = open(url);
        stomp.send("CONNECT\naccept-version:1.2\nhost:localhost\nheart-beat:0,0\n\n\0");
        String connected = stomp.nextFrame(Duration.ofSeconds(5));
        if (connected == null || !connected.startsWith("CONNECTED")) {
            throw new IllegalStateException("CONNECTED가 아님: " + connected);
        }
        stomp.subscribe(destination);
        return stomp;
    }

    /** 구독 프레임만 보낸다. 서버가 구독을 등록했는지는 호출한 시험이 서버 쪽 사용자 목록으로 확인한다 */
    public void subscribe(String destination) throws Exception {
        send("SUBSCRIBE\nid:sub-0\ndestination:" + destination + "\n\n\0");
    }

    public void send(String frame) throws Exception {
        session.sendMessage(new TextMessage(frame));
    }

    /** 다음 프레임. 하트비트(빈 줄)는 건너뛴다. 시간 안에 없으면 null */
    public String nextFrame(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                return null;
            }
            String frame = frames.poll(left, TimeUnit.NANOSECONDS);
            if (frame == null) {
                return null;
            }
            if (!frame.isBlank()) {
                return frame;
            }
        }
    }

    /** 닫힐 때까지 기다린다. 시간 안에 닫히지 않으면 null */
    public CloseStatus awaitClose(Duration timeout) {
        try {
            return closed.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    public boolean isOpen() {
        return session.isOpen();
    }

    public void closeQuietly() {
        try {
            session.close();
        } catch (Exception ignored) {
            // 이미 닫힘
        }
    }
}
