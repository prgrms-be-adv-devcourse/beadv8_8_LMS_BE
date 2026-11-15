package com.hapbang.auxiliary.search.config;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 검색 전용 Elasticsearch 연결 설정({@code hapbang.search.elasticsearch.*}). 챗봇이 쓰는 Boot 자동 설정
 * ({@code spring.elasticsearch.*})과 같은 cluster지만 API key가 다르다(검색 role은 {@code search-*}만, #806).
 *
 * @param uris              ES 주소. 없으면 안 된다
 * @param apiKey            API key(encoded). 운영은 Secret {@code ES_SEARCH_API_KEY}, 보안을 끈 로컬·테스트는 비워 둔다
 * @param sslBundle         https 연결에 쓸 SSL bundle 이름(운영은 ECK CA를 믿는 {@code elasticsearch}). 비우면 JVM 기본
 * @param connectionTimeout 연결 제한 시간. 기본 1초(챗봇 설정과 같다)
 * @param socketTimeout     응답 제한 시간. 기본 10초
 */
@ConfigurationProperties("hapbang.search.elasticsearch")
public record SearchElasticsearchProperties(List<URI> uris, String apiKey, String sslBundle,
                                            Duration connectionTimeout, Duration socketTimeout) {

    public SearchElasticsearchProperties {
        if (uris == null || uris.isEmpty()) {
            throw new IllegalArgumentException("hapbang.search.elasticsearch.uris 설정이 필요합니다.");
        }
        uris = List.copyOf(uris);
        apiKey = apiKey == null || apiKey.isBlank() ? null : apiKey;
        sslBundle = sslBundle == null || sslBundle.isBlank() ? null : sslBundle;
        connectionTimeout = connectionTimeout == null ? Duration.ofSeconds(1) : connectionTimeout;
        socketTimeout = socketTimeout == null ? Duration.ofSeconds(10) : socketTimeout;
    }

    /** 로그·예외에 key가 나가지 않게 가린다 */
    @Override
    public String toString() {
        return "SearchElasticsearchProperties[uris=" + uris + ", apiKey=" + (apiKey == null ? "없음" : "****")
                + ", sslBundle=" + sslBundle + ", connectionTimeout=" + connectionTimeout
                + ", socketTimeout=" + socketTimeout + "]";
    }
}
