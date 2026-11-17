package com.hapbang.chat.fixture;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;

/**
 * 장애 시험 전용 Redis 컨테이너와, 앱이 붙는 고정 주소. 컨테이너를 죽였다 다시 띄워도 앱이 같은 주소로 다시 붙어야 한다.
 * <p>
 * 앱은 컨테이너 포트가 아니라 이 JVM이 시험 내내 쥐고 있는 중계 포트({@link #hostPort()})에 붙는다. 중계는 연결마다 컨테이너의
 * 지금 매핑 포트로 잇고, 컨테이너가 멈춰 있으면 연결을 바로 닫는다. 컨테이너에 호스트 포트를 고정하면 죽인 동안 그 포트가 풀려
 * 다른 컨테이너(같은 머신의 다른 빌드·Testcontainers)가 가져갈 수 있고, 그러면 앱이 남의 Redis에 다시 붙어 장애가 끝난 것처럼
 * 보인다. 중계 포트는 JVM이 놓지 않으므로 그런 일이 없다.
 */
public record FixedPortRedis(GenericContainer<?> container, int hostPort) {

    private static final int REDIS_PORT = 6379;
    private static final int CONNECT_TIMEOUT_MILLIS = 1000;

    /** {@code command}(예: {@code redis-server --appendonly yes ...})로 띄운다 */
    public static FixedPortRedis start(String... command) {
        GenericContainer<?> container = new GenericContainer<>("redis:8")
                .withExposedPorts(REDIS_PORT)
                .withCommand(command)
                .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1));
        container.start();
        return new FixedPortRedis(container, startRelay(container));
    }

    /** 앱이 붙을 호스트. 중계는 루프백에서만 받는다 */
    public String host() {
        return InetAddress.getLoopbackAddress().getHostAddress();
    }

    private static int startRelay(GenericContainer<?> container) {
        ServerSocket server;
        try {
            server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        Thread acceptor = new Thread(() -> {
            while (!server.isClosed()) {
                try {
                    relay(container, server.accept());
                } catch (IOException e) {
                    // 받은 연결 하나의 실패. 다음 연결을 계속 받는다
                }
            }
        }, "fixed-port-redis-relay-" + server.getLocalPort());
        acceptor.setDaemon(true);
        acceptor.start();
        return server.getLocalPort();
    }

    private static void relay(GenericContainer<?> container, Socket client) throws IOException {
        Integer port = currentMappedPort(container);
        if (port == null) {
            // 컨테이너가 멈춰 있다. 연결을 바로 닫아 앱이 연결 불가로 보게 한다
            client.close();
            return;
        }
        Socket backend = new Socket();
        try {
            backend.connect(new InetSocketAddress(container.getHost(), port), CONNECT_TIMEOUT_MILLIS);
        } catch (IOException e) {
            backend.close();
            client.close();
            return;
        }
        pipe(client, backend);
        pipe(backend, client);
    }

    /** 컨테이너가 실행 중이면 지금 매핑된 호스트 포트. 다시 띄우면 매핑 포트가 바뀔 수 있어 연결마다 새로 읽는다 */
    private static Integer currentMappedPort(GenericContainer<?> container) {
        try {
            InspectContainerResponse info = container.getDockerClient()
                    .inspectContainerCmd(container.getContainerId()).exec();
            if (!Boolean.TRUE.equals(info.getState().getRunning())) {
                return null;
            }
            Ports.Binding[] bindings = info.getNetworkSettings().getPorts().getBindings()
                    .get(ExposedPort.tcp(REDIS_PORT));
            if (bindings == null || bindings.length == 0) {
                return null;
            }
            return Integer.valueOf(bindings[0].getHostPortSpec());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 한쪽 입력을 다른 쪽 출력으로 옮긴다. 어느 쪽이든 끊기면 두 소켓을 모두 닫는다 */
    private static void pipe(Socket from, Socket to) {
        Thread thread = new Thread(() -> {
            byte[] buffer = new byte[8192];
            try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    out.flush();
                }
            } catch (IOException e) {
                // 연결이 끊겼다
            } finally {
                closeQuietly(from);
                closeQuietly(to);
            }
        }, "fixed-port-redis-pipe");
        thread.setDaemon(true);
        thread.start();
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException e) {
            // 이미 닫혔다
        }
    }
}
