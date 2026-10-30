package com.hapbang.chat.fixture;

import java.io.IOException;
import java.net.ServerSocket;

import org.testcontainers.containers.ContainerLaunchException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;

/**
 * 고정 호스트 포트로 띄운 전용 Redis 컨테이너. 컨테이너를 죽였다 다시 띄워도 앱이 같은 주소로 다시 붙도록 포트를 고정한다.
 * <p>
 * 빈 포트를 고른 뒤 컨테이너가 그 포트에 묶이기 전에 다른 프로세스(다른 빌드의 Testcontainers 등)가 가져가면
 * "address already in use"로 시작이 실패한다. 그때는 새 포트로 몇 번 다시 띄운다.
 */
public record FixedPortRedis(GenericContainer<?> container, int hostPort) {

    private static final int REDIS_PORT = 6379;
    private static final int MAX_ATTEMPTS = 3;

    /** {@code command}(예: {@code redis-server --appendonly yes ...})로 띄운다 */
    public static FixedPortRedis start(String... command) {
        ContainerLaunchException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            int hostPort = freePort();
            GenericContainer<?> container = new GenericContainer<>("redis:8")
                    .withExposedPorts(REDIS_PORT)
                    .withCommand(command)
                    .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                            new PortBinding(Ports.Binding.bindPort(hostPort), new ExposedPort(REDIS_PORT))))
                    .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1));
            try {
                container.start();
                return new FixedPortRedis(container, hostPort);
            } catch (ContainerLaunchException e) {
                container.stop();
                if (!isPortConflict(e)) {
                    throw e;
                }
                last = e;
            }
        }
        throw last;
    }

    private static boolean isPortConflict(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && (message.contains("address already in use")
                    || message.contains("port is already allocated"))) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
