package com.hapbang.auxiliary.search.config;

import java.util.concurrent.TimeUnit;

import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.message.BasicHeader;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.Jackson3JsonpMapper;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import co.elastic.clients.transport.rest5_client.low_level.Rest5ClientBuilder;

/**
 * 검색 전용 {@link ElasticsearchClient}(#771). 챗봇은 Boot 자동 설정 client({@code spring.elasticsearch.*}, {@code ES_API_KEY})를
 * 쓰고, 검색은 같은 cluster에 검색 role의 key({@code ES_SEARCH_API_KEY})로 따로 붙는다. 모듈마다 role·key가 따로라는
 * {@code docs/deploy/k8s.md} "Elasticsearch 앱 접근" 규칙 때문이다.
 * <ul>
 *     <li>빈은 기본 후보에서 빼서({@code defaultCandidate = false}) Boot 자동 설정 client가 물러나지 않게 하고(챗봇이 그것을 쓴다),
 *     검색 코드는 이름({@link #CLIENT})으로만 받는다.</li>
 *     <li>low-level client({@link Rest5Client})는 빈으로 두지 않는다. Boot의 ES 헬스 지표가 챗봇 client로 이미 같은 cluster를 본다.
 *     client를 닫으면 transport와 low-level client가 함께 닫힌다.</li>
 *     <li>버전은 Boot BOM(elasticsearch-java 9.4.5)이고 운영 ES 9.5.5와 호환된다(#771 시험 결과).</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SearchElasticsearchProperties.class)
public class SearchElasticsearchConfig {

    /** 검색 전용 client 빈 이름 */
    public static final String CLIENT = "searchElasticsearchClient";

    @Bean(name = CLIENT, defaultCandidate = false)
    ElasticsearchClient searchElasticsearchClient(SearchElasticsearchProperties properties,
            ObjectProvider<SslBundles> sslBundles) {
        Rest5Client restClient = restClient(properties, sslBundles);
        return new ElasticsearchClient(new Rest5ClientTransport(restClient, new Jackson3JsonpMapper()));
    }

    static Rest5Client restClient(SearchElasticsearchProperties properties, ObjectProvider<SslBundles> sslBundles) {
        Rest5ClientBuilder builder = Rest5Client.builder(properties.uris())
                .setConnectionConfigCallback(connection -> connection
                        .setConnectTimeout(timeout(properties.connectionTimeout().toMillis()))
                        .setSocketTimeout(timeout(properties.socketTimeout().toMillis())))
                .setRequestConfigCallback(request -> request
                        .setResponseTimeout(timeout(properties.socketTimeout().toMillis())));
        if (properties.apiKey() != null) {
            builder.setDefaultHeaders(new Header[] {
                    new BasicHeader(HttpHeaders.AUTHORIZATION, "ApiKey " + properties.apiKey())});
        }
        if (properties.sslBundle() != null) {
            builder.setSSLContext(sslBundles.getObject().getBundle(properties.sslBundle()).createSslContext());
        }
        return builder.build();
    }

    private static Timeout timeout(long millis) {
        return Timeout.of(millis, TimeUnit.MILLISECONDS);
    }
}
