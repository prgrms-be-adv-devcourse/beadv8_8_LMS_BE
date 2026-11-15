package com.hapbang.auxiliary.search.support;

import java.time.Duration;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

/**
 * Elasticsearch가 필요한 검색 테스트에서 @Import 한다(#771). 운영과 같은 9.5.5에 nori를 설치한 이미지, 보안을 끈 단일 노드다.
 * 운영은 ECK init container로 같은 plugin을 설치한다({@code k8s/platform/base/elasticsearch}).
 * <ul>
 *     <li>이미지 이름을 고정하고 {@code deleteOnExit=false}라 두 번째 실행부터 다시 빌드하지 않는다. 처음 빌드는 plugin을
 *     {@code artifacts.elastic.co}에서 받는다(외부망 필요, 약 1분).</li>
 *     <li>컨테이너는 테스트 JVM에서 한 번만 시작해 클래스끼리 공유한다(챗봇 {@code ElasticsearchTestcontainersConfig}와 같은 방식).
 *     그래서 테스트는 자기 index·동의어 묶음만 쓰고 지운다. 컨테이너를 멈추거나 다시 시작하지 않는다.</li>
 *     <li>검색은 Boot 자동 설정 client가 아니라 검색 전용 client를 쓰므로 {@code @ServiceConnection} 대신
 *     {@code hapbang.search.elasticsearch.uris}를 넣는다. 챗봇 설정과 합칠지는 나중에 정한다(#767).</li>
 *     <li>{@code disk.threshold_enabled=false}: 개발 PC 디스크가 90%를 넘어도 index가 읽기 전용이 되지 않게 한다.</li>
 * </ul>
 */
@TestConfiguration(proxyBeanMethods = false)
public class SearchElasticsearchTestcontainersConfig {

    public static final String ES_VERSION = "9.5.5";

    private static final int PORT = 9200;

    private static final ImageFromDockerfile IMAGE = new ImageFromDockerfile("hapbang/es-nori-test:" + ES_VERSION, false)
            .withDockerfileFromBuilder(builder -> builder
                    .from("docker.elastic.co/elasticsearch/elasticsearch:" + ES_VERSION)
                    .run("bin/elasticsearch-plugin install --batch analysis-nori")
                    .build());

    private static final GenericContainer<?> ELASTICSEARCH = new GenericContainer<>(IMAGE)
            .withEnv("discovery.type", "single-node")
            .withEnv("xpack.security.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withEnv("cluster.routing.allocation.disk.threshold_enabled", "false")
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
    DynamicPropertyRegistrar searchElasticsearchProperties() {
        return registry -> registry.add("hapbang.search.elasticsearch.uris", SearchElasticsearchTestcontainersConfig::uri);
    }
}
