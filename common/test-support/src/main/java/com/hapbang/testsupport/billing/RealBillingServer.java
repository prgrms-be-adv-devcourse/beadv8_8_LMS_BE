package com.hapbang.testsupport.billing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.testcontainers.postgresql.PostgreSQLContainer;

/** 다른 앱의 클래스패스를 섞지 않고 실제 billing을 전용 DB·JVM에서 실행한다. */
final class RealBillingServer implements AutoCloseable {
    private static RealBillingServer instance;
    private final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18")
            .withDatabaseName("billing_e2e");
    private Process process;
    private String baseUrl;

    static synchronized RealBillingServer get() {
        if (instance == null) {
            RealBillingServer server = new RealBillingServer();
            try {
                server.start();
                instance = server;
                Runtime.getRuntime().addShutdownHook(new Thread(server::close, "billing-e2e-cleanup"));
            } catch (Exception failure) {
                server.close();
                throw new IllegalStateException("실제 billing E2E 서버 기동 실패", failure);
            }
        }
        return instance;
    }

    String baseUrl() { return baseUrl; }

    private void start() throws IOException, InterruptedException {
        String jar = System.getProperty("hapbang.billing.e2e.jar");
        if (jar == null || !Files.isRegularFile(Path.of(jar)))
            throw new IllegalStateException("billing e2eServerJar 산출물 경로가 필요합니다: " + jar);
        postgres.start();
        Path directory = Files.createTempDirectory("hapbang-billing-e2e-");
        Path ready = directory.resolve("ready.json");
        Path log = directory.resolve("billing.log");
        process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx384m", "-jar", jar,
                "--spring.config.name=billing-e2e", "--spring.profiles.active=e2e",
                "--server.address=127.0.0.1", "--server.port=0",
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--hapbang.internal-api.key=test-internal-api-key",
                "--hapbang.e2e.key=test-billing-e2e-key", "--hapbang.e2e.ready-file=" + ready)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        Pattern port = Pattern.compile("\"port\"\\s*:\\s*(\\d+)");
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) throw new IllegalStateException("billing이 기동 중 종료됐습니다. 로그: " + log);
            if (Files.isRegularFile(ready)) {
                var match = port.matcher(Files.readString(ready));
                if (match.find()) {
                    baseUrl = "http://127.0.0.1:" + match.group(1);
                    return;
                }
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("billing 준비 시간 초과. 로그: " + log);
    }

    @Override
    public void close() {
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        postgres.stop();
    }
}
