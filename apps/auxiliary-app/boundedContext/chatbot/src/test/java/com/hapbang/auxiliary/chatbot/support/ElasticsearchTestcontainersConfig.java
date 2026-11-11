package com.hapbang.auxiliary.chatbot.support;

import java.time.Duration;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * Elasticsearch가 필요한 챗봇 테스트에서 @Import 한다(#715). 운영과 같은 9.5.5, 보안을 끈 단일 노드다.
 * <p>
 * 이미지가 커서 테스트 JVM에서 한 번만 시작해 클래스끼리 공유한다(다른 Testcontainers 설정과 같은 방식). 그래서 ES를 쓰는 테스트는
 * 자기 index만 쓰고 지운다. 컨테이너를 멈추거나 다시 시작하지 않는다(다른 클래스가 쓰는 중일 수 있다). ES 장애는 닿지 않는 주소로 흉내 낸다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ElasticsearchTestcontainersConfig {

    private static final int PORT = 9200;

    private static final GenericContainer<?> ELASTICSEARCH = new GenericContainer<>("elasticsearch:9.5.5")
            .withEnv("discovery.type", "single-node")
            .withEnv("xpack.security.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withExposedPorts(PORT)
            .waitingFor(Wait.forHttp("/_cluster/health?wait_for_status=yellow&timeout=60s").forPort(PORT)
                    .withStartupTimeout(Duration.ofMinutes(3)));

    static {
        ELASTICSEARCH.start();
    }

    /** 컨테이너 ES 주소(http://host:port) */
    public static String uri() {
        return "http://" + ELASTICSEARCH.getHost() + ":" + ELASTICSEARCH.getMappedPort(PORT);
    }

    @Bean
    DynamicPropertyRegistrar elasticsearchProperties() {
        return registry -> registry.add("spring.elasticsearch.uris", ElasticsearchTestcontainersConfig::uri);
    }
}
