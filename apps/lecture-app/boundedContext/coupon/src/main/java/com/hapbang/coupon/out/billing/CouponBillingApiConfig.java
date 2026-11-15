package com.hapbang.coupon.out.billing;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import com.hapbang.coupon.app.port.PromotionBudget;
import com.hapbang.shared.billing.out.BillingClientProperties;
import com.hapbang.shared.internal.InternalApiHeaders;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 기존 billing 주소·내부 키·제한 시간 설정으로 쿠폰 전용 예산 HTTP 프록시를 만든다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "hapbang.clients.billing", name = "base-url")
@EnableConfigurationProperties(BillingClientProperties.class)
public class CouponBillingApiConfig {

    @Bean
    CouponBillingInternalApi couponBillingInternalApi(RestClient.Builder builder, BillingClientProperties properties,
            @Value("${hapbang.internal-api.key}") String internalApiKey) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        return create(builder.clone().requestFactory(requestFactory), properties.baseUrl(), internalApiKey);
    }

    @Bean
    @ConditionalOnMissingBean(PromotionBudget.class)
    HttpPromotionBudget httpPromotionBudget(CouponBillingInternalApi api, BillingClientProperties properties) {
        return new HttpPromotionBudget(api, properties);
    }

    /** 테스트에서도 운영과 같은 프록시·오류 분류를 사용한다. */
    public static CouponBillingInternalApi create(RestClient.Builder builder, String baseUrl, String internalApiKey) {
        RestClient restClient = builder.baseUrl(baseUrl)
                .defaultHeader(InternalApiHeaders.API_KEY, internalApiKey)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                    throw new BudgetHttpResponse(response.getStatusCode().value(), problemCode(response));
                }).build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient)).build()
                .createClient(CouponBillingInternalApi.class);
    }

    private static String problemCode(ClientHttpResponse response) {
        try {
            byte[] body = response.getBody().readAllBytes();
            if (body.length == 0) {
                return null;
            }
            JsonNode code = JsonMapper.shared().readTree(body).get("code");
            return code == null || !code.isString() ? null : code.asString();
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /** 오류 본문·민감 값을 보관하지 않고 상태와 업무 코드만 전달한다. */
    static final class BudgetHttpResponse extends RuntimeException {
        private final int status;
        private final String code;

        BudgetHttpResponse(int status, String code) {
            super("프로모션 예산 API 오류 status=" + status + ", code=" + code, null, false, false);
            this.status = status;
            this.code = code;
        }

        int status() {
            return status;
        }

        String code() {
            return code;
        }
    }
}
