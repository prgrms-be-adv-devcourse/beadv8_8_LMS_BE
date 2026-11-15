package com.hapbang.shared.member.out;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.hapbang.shared.internal.InternalApiHeaders;
import com.hapbang.shared.member.dto.ContentCategoriesDto;
import com.hapbang.shared.member.dto.InfluencerStatsPageDto;
import com.hapbang.shared.member.dto.MemberChangePageDto;
import com.hapbang.shared.member.dto.MemberPageDto;

/**
 * 회원 내부 HTTP API Client (D62, #303). 다른 모듈은 회원 Java 타입 대신 이 Client로 회원을 부른다.
 * URI·내부 API 키·응답 감싸기(RsData)·오류 변환을 감춘다. 실패는 {@link MemberApiCallException}이다.
 * {@code hapbang.clients.member.base-url}이 있을 때만 빈이 생긴다(모듈 테스트에는 없다). 구조는 강의 Client(#300)와 같다.
 */
@Component
@ConditionalOnProperty(prefix = "hapbang.clients.member", name = "base-url")
@EnableConfigurationProperties(MemberClientProperties.class)
public class MemberApiClient {

    static final String MEMBERS_PATH = "/api/v1/internal/members";
    static final String INFLUENCER_STATS_PATH = MEMBERS_PATH + "/influencer-stats";
    static final String CHANGES_PATH = MEMBERS_PATH + "/changes";
    static final String CONTENT_CATEGORIES_PATH = MEMBERS_PATH + "/content-categories";

    /** 변경분 커서 시각의 쿼리 문자열 형식. 초가 0이어도 초를 쓰고, 마이크로초까지 그대로 보낸다 (예: 2026-11-09T10:15:30.123456) */
    private static final DateTimeFormatter CURSOR_TIME = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private static final ParameterizedTypeReference<Body<MemberPageDto>> PAGE =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<Body<InfluencerStatsPageDto>> STATS_PAGE =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<Body<MemberChangePageDto>> CHANGE_PAGE =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<Body<ContentCategoriesDto>> CONTENT_CATEGORIES =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<Map<String, Object>> PROBLEM =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;

    public MemberApiClient(RestClient.Builder restClientBuilder, MemberClientProperties properties,
                           @Value("${hapbang.internal-api.key}") String internalApiKey) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        this.restClient = restClientBuilder.clone()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .defaultHeader(InternalApiHeaders.API_KEY, internalApiKey)
                .build();
    }

    /**
     * 회원 목록 한 페이지 (ID 오름차순 키셋, 모든 역할·상태). afterId가 null이면 처음부터, size는 1~500.
     * 응답의 nextAfterId가 null이 될 때까지 다음 afterId로 넘겨 부르면 모든 회원을 받는다
     */
    public MemberPageDto getMembers(Long afterId, int size) {
        return call(restClient.get()
                .uri(builder -> {
                    builder.path(MEMBERS_PATH).queryParam("size", size);
                    if (afterId != null) {
                        builder.queryParam("afterId", afterId);
                    }
                    return builder.build();
                }), PAGE);
    }

    /**
     * 인플루언서 통계 한 페이지 (#769, 정상 인플루언서만, ID 오름차순 키셋). afterId가 null이면 처음부터, size는 1~500.
     * 응답의 nextAfterId가 null이 될 때까지 다음 afterId로 넘겨 부르면 모든 정상 인플루언서를 받는다
     */
    public InfluencerStatsPageDto getInfluencerStats(Long afterId, int size) {
        return call(restClient.get()
                .uri(builder -> {
                    builder.path(INFLUENCER_STATS_PATH).queryParam("size", size);
                    if (afterId != null) {
                        builder.queryParam("afterId", afterId);
                    }
                    return builder.build();
                }), STATS_PAGE);
    }

    /**
     * 회원 변경분 한 페이지 (#769, 모든 역할·상태, (updatedAt, id) 오름차순 키셋). 커서 둘은 함께 넘기거나 함께 null(처음부터)이다.
     * 페이지마다 응답의 next 커서를 저장하고 hasNext가 false가 될 때까지 넘겨 부른다. 다음 주기에는 저장한 커서를 1분 겹쳐 다시 읽는다
     * ({@link MemberChangePageDto})
     *
     * @throws IllegalArgumentException 커서 둘 중 하나만 넘긴 경우 (보내지 않는다)
     */
    public MemberChangePageDto getChanges(LocalDateTime afterUpdatedAt, Long afterId, int size) {
        if ((afterUpdatedAt == null) != (afterId == null)) {
            throw new IllegalArgumentException("afterUpdatedAt과 afterId는 함께 넘기거나 함께 생략합니다.");
        }
        return call(restClient.get()
                .uri(builder -> {
                    builder.path(CHANGES_PATH).queryParam("size", size);
                    if (afterUpdatedAt != null) {
                        builder.queryParam("afterUpdatedAt", CURSOR_TIME.format(afterUpdatedAt))
                                .queryParam("afterId", afterId);
                    }
                    return builder.build();
                }), CHANGE_PAGE);
    }

    /** 콘텐츠 분야 전체 목록 (#769). active가 꺼진 분야도 포함, 코드 오름차순 */
    public ContentCategoriesDto getContentCategories() {
        return call(restClient.get().uri(CONTENT_CATEGORIES_PATH), CONTENT_CATEGORIES);
    }

    /**
     * 응답 상태를 잃지 않도록 직접 읽는다. 4xx는 거절, 5xx·읽을 수 없는 본문(깨진 JSON, 계약에 없는 enum 값,
     * 본문 없음)·연결 실패·시간 초과는 호출 불가다. 어떤 실패든 {@link MemberApiCallException}으로 나간다
     */
    private <T> T call(RestClient.RequestHeadersSpec<?> request, ParameterizedTypeReference<Body<T>> type) {
        try {
            return request.exchange((req, response) -> {
                int status = response.getStatusCode().value();
                if (response.getStatusCode().isError()) {
                    MemberApiCallException.Reason reason = response.getStatusCode().is5xxServerError()
                            ? MemberApiCallException.Reason.UNAVAILABLE
                            : MemberApiCallException.Reason.REJECTED;
                    throw new MemberApiCallException(reason, status, problemCode(response),
                            "회원 내부 API가 " + status + "로 응답했습니다.", null);
                }
                Body<T> body;
                try {
                    body = response.bodyTo(type);
                } catch (RuntimeException unreadable) {
                    throw new MemberApiCallException(MemberApiCallException.Reason.UNAVAILABLE, status, null,
                            "회원 내부 API 응답을 읽지 못했습니다.", unreadable);
                }
                if (body == null || body.data() == null) {
                    throw new MemberApiCallException(MemberApiCallException.Reason.UNAVAILABLE, status, null,
                            "회원 내부 API 응답에 data가 없습니다.", null);
                }
                return body.data();
            });
        } catch (MemberApiCallException e) {
            throw e;
        } catch (RestClientException e) {
            throw new MemberApiCallException(MemberApiCallException.Reason.UNAVAILABLE, 0, null,
                    "회원 내부 API에 연결하지 못했거나 응답 시간이 지났습니다.", e);
        }
    }

    private static String problemCode(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) {
        try {
            Map<String, Object> problem = response.bodyTo(PROBLEM);
            return problem == null ? null : Objects.toString(problem.get("code"), null);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /** 회원 성공 응답(global RsData)의 모양. shared는 global에 의존하지 않아 같은 필드로 읽는다 */
    record Body<T>(int statusCode, String message, T data) {
    }
}
