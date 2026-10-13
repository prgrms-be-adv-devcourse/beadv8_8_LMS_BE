package com.hapbang.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * S3 호출이 필요한 테스트에서 @Import 한다. 로컬 개발(docker/compose.yaml)과 같은 S3Mock을 띄우고 global의 S3 설정을 이 컨테이너로 돌린다.
 * S3Mock은 Presigned URL의 서명·만료를 검사하지 않는다.
 * <p>
 * global의 S3 설정은 {@code hapbang.storage.s3.bucket}이 있을 때만 만들어지고, 이 조건은 아래 동적 속성보다 먼저 평가된다.
 * {@link IntegrationTest}는 버킷 값을 이미 넣고 있다. 직접 {@code @SpringBootTest}를 쓰면 {@code properties}에 버킷을 넣는다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class S3MockTestcontainersConfig {

    public static final String BUCKET = "hapbang-test";
    public static final String KEY_PREFIX = "test";

    private static final int PORT = 9090;

    private static final GenericContainer<?> S3MOCK = new GenericContainer<>("adobe/s3mock:5.2.3")
            .withEnv("COM_ADOBE_TESTING_S3MOCK_STORE_INITIAL_BUCKETS", BUCKET)
            .withExposedPorts(PORT)
            .waitingFor(Wait.forHttp("/").forPort(PORT));

    static {
        S3MOCK.start();
    }

    public static String endpoint() {
        return "http://" + S3MOCK.getHost() + ":" + S3MOCK.getMappedPort(PORT);
    }

    @Bean
    DynamicPropertyRegistrar s3MockProperties() {
        return registry -> {
            registry.add("hapbang.storage.s3.endpoint", S3MockTestcontainersConfig::endpoint);
            registry.add("hapbang.storage.s3.bucket", () -> BUCKET);
            registry.add("hapbang.storage.s3.region", () -> "ap-northeast-2");
            registry.add("hapbang.storage.s3.key-prefix", () -> KEY_PREFIX);
            registry.add("hapbang.storage.s3.upload-url-expiry", () -> "10m");
            registry.add("hapbang.storage.s3.download-url-expiry", () -> "10m");
        };
    }
}
