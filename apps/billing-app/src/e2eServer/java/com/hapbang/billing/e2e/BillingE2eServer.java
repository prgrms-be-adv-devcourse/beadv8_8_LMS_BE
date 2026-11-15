package com.hapbang.billing.e2e;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;

import com.hapbang.billing.BillingApplication;

/** 별도 sourceSet/Jar에서만 실행한다. 실제 BillingApplication에 HTTP 테스트 fixture만 더한다. */
public final class BillingE2eServer {

    private BillingE2eServer() { }

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(BillingApplication.class, BillingE2eServerConfiguration.class);
        app.setDefaultProperties(Map.of("spring.config.name", "billing-e2e", "spring.profiles.active", "e2e"));
        app.addListeners((ApplicationListener<ApplicationEnvironmentPreparedEvent>) event -> verifyEnvironment(event.getEnvironment()));
        app.addListeners((ApplicationListener<ApplicationReadyEvent>) event -> writeReadyFile(event.getApplicationContext().getEnvironment()));
        app.run(args);
    }

    private static void verifyEnvironment(ConfigurableEnvironment environment) {
        if (!"billing-e2e".equals(environment.getProperty("spring.config.name"))
                || !Arrays.equals(environment.getActiveProfiles(), new String[]{"e2e"})) {
            throw new IllegalArgumentException("billing E2E 서버는 billing-e2e 설정과 e2e 프로필로만 실행합니다.");
        }
        try {
            String address = environment.getRequiredProperty("server.address");
            if (!InetAddress.getByName(address).isLoopbackAddress()) {
                throw new IllegalArgumentException("billing E2E 서버는 loopback 주소에만 바인딩합니다.");
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        environment.getRequiredProperty("hapbang.e2e.ready-file");
        if (environment.getRequiredProperty("hapbang.e2e.key").isBlank()) {
            throw new IllegalArgumentException("billing E2E 접근 키는 비어 있을 수 없습니다.");
        }
    }

    private static void writeReadyFile(org.springframework.core.env.Environment environment) {
        int port = environment.getRequiredProperty("local.server.port", Integer.class);
        Path target = Path.of(environment.getRequiredProperty("hapbang.e2e.ready-file")).toAbsolutePath();
        try {
            Files.createDirectories(target.getParent());
            Path pending = Files.createTempFile(target.getParent(), "billing-ready-", ".json");
            String json = "{\"port\":" + port + ",\"baseUrl\":\"http://127.0.0.1:" + port
                    + "\",\"pid\":" + ProcessHandle.current().pid() + "}";
            Files.writeString(pending, json);
            try {
                Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(pending);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
