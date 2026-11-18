package com.hapbang.auxiliary.chatbot.config;

import java.time.Duration;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 강의 추천 설정 {@code hapbang.chatbot.recommend.lecture} (#718). 정책 숫자(가중치·상한·혼합 비율)는 모두 여기 둔다.
 * index 이름·차원은 공통 {@link ChatbotIndexProperties}에 있다. 정합 점검 시각(cron)은 배치 빈의 {@code @Scheduled}
 * 플레이스홀더({@code index-check-cron})에 있다(싱크 배치와 같다).
 * <p>
 * <b>임시값</b>이라고 적은 값은 평가 없이 정한 값이다. 추천 가중치 보정(#867)에서 더미 데이터로 잰 값은 운영 프로필
 * ({@code application-prod.yaml})에 근거 주석과 함께 두었다(β·별점 1·2점 보정, 근거 표는 {@code docs/chatbot/recommend-weights.md}).
 * 코드 기본값과 local 값은 출발값 그대로다. 관심사 최대 길이(100자)는 요청 검증 애노테이션이 컴파일 시점 상수를 써야 해서 설정이 아니라
 * {@code RecommendInterestUtil.MAX_LENGTH}에 있다.
 *
 * @param resultSize       추천 수(이슈 #718에서 정한 화면 카드 수)
 * @param maxPerSeller     판매자 한 명의 강의를 최대 몇 개까지 넣는지(다양성, 이슈 #718에서 정함)
 * @param signalLimit      취향 벡터에 쓰는 신호 강의 수(최근 순). 더미 데이터는 신호가 최대 12개라 #867에서 재지 못했다. 임시값
 * @param weights          신호 가중치. #867에서 잰 값(구매·장바구니·4·5점은 유지, 1·2점은 prod에서 바꿈)
 * @param interestWeight   신호와 관심사가 함께 있을 때 관심사의 비율 α. {@code (1−α)·정규화(신호) + α·정규화(관심사)}.
 *                         활동 신호보다 낮게 둔다. 임베딩 모델마다 유사도 분포가 달라 기본값 없이 프로필 yaml에 둔다(prod는 #867에서 잼)
 * @param chatInterestWeight 자유 질문 대화의 강의 추천 Tool에서 쓰는 관심사 비율 α(#1007). 대화에서 직접 말한 요구가 과거 구매보다
 *                         앞서게 위젯·버튼(α {@code interestWeight})보다 높게 둔다. 없으면 {@code interestWeight}를 쓴다.
 *                         <b>임시값</b>이다(평가 없이 정함, prod 0.7)
 * @param popularityWeight 최종 점수에서 인기의 비율 β. {@code (1−β)·유사도 + β·인기(후보 안 0~1)}. 인기가 뒤집을 수 있는 유사도 차이는
 *                         약 {@code β/(1−β)}다(0.05면 약 0.05, 0.2면 0.25). α와 같이 기본값 없이 프로필 yaml에 둔다(prod는 #867에서 잼)
 * @param knnK             벡터 검색으로 받는 후보 수. 임시값
 * @param numCandidates    벡터 검색이 샤드마다 살펴볼 후보 수(knnK 이상). 임시값
 * @param popularPoolSize  인기순 캐시로 채우지 못할 때 직접 읽는 강의 수. 임시값
 * @param popularCacheSize 인기순 캐시에 두는 상위 강의 수(pod 메모리, 회원별 제외는 코드에서 한다). 임시값
 * @param popularCacheTtl  인기순 캐시를 다시 읽는 주기. 캐시 뒤 상태가 바뀐 강의는 응답 직전에 다시 확인해 거른다
 * @param rateLimit        관심사를 넣은 요청(임베딩 비용)의 회원별 호출 제한
 * @param buttonRateLimit  챗봇 "강의 추천받기" 버튼(LLM 비용)의 회원별 호출 제한
 * @param widgetRateLimit  위젯 API 전체(관심사 여부와 상관없이)의 회원별 호출 제한. 연타가 공용 DB 연결을 붙잡지 않게 넓게 둔다
 * @param titleWeight      강의 벡터에 섞는 제목 벡터의 비율 w(#1007). {@code (1−w)·정규화(글 벡터) + w·정규화(제목 벡터)}.
 *                         긴 소개가 제목·주제를 묻는 것을 줄인다. 0이면 제목을 따로 임베딩하지 않고 글 벡터 그대로다.
 *                         색인 글 해시에 들어가므로 바꾸면 모든 강의를 다시 임베딩한다(0으로 되돌려도 같다). <b>임시값</b>이다(평가 없이 정함, prod 0.3)
 * @param indexCheckBatch  정합 점검이 한 번에 읽는 강의 수
 * @param checkAlertAfterFailures 정합 점검이 이 횟수 연속 실패하면 ERROR로 알린다(인플루언서 점검과 같은 값)
 */
@Validated
@ConfigurationProperties("hapbang.chatbot.recommend.lecture")
public record ChatbotLectureRecommendProperties(
        @DefaultValue("5") @Positive int resultSize,
        @DefaultValue("2") @Positive int maxPerSeller,
        @DefaultValue("50") @Positive int signalLimit,
        @DefaultValue @Valid Weights weights,
        @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double interestWeight,
        @Nullable @DecimalMin("0.0") @DecimalMax("1.0") Double chatInterestWeight,
        @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double popularityWeight,
        @DefaultValue("50") @Positive int knnK,
        @DefaultValue("100") @Positive int numCandidates,
        @DefaultValue("50") @Positive int popularPoolSize,
        @DefaultValue("200") @Positive int popularCacheSize,
        @DefaultValue("1m") @NotNull Duration popularCacheTtl,
        @DefaultValue @Valid RateLimit rateLimit,
        @DefaultValue @Valid RateLimit buttonRateLimit,
        @DefaultValue @Valid WidgetRateLimit widgetRateLimit,
        @DefaultValue("0") @DecimalMin("0.0") @DecimalMax("1.0") double titleWeight,
        @DefaultValue("500") @Positive int indexCheckBatch,
        @DefaultValue("3") @Positive int checkAlertAfterFailures) {

    /** 자유 질문 Tool의 관심사 비율. 따로 정하지 않았으면 위젯·버튼과 같은 {@link #interestWeight} */
    public double chatInterestWeightOrDefault() {
        return chatInterestWeight == null ? interestWeight : chatInterestWeight;
    }

    /**
     * 신호 가중치. 강의 가중 = 기본(구매 또는 장바구니) + 그 강의 리뷰 별점 보정, 0 아래로 내리지 않는다(0이면 신호에서 뺀다).
     * 기본값은 "구매가 장바구니보다 강하고, 별점은 기본을 뒤집지 않을 만큼"이라는 방향만 정한 출발값이다. #867에서 재 보니
     * 구매·장바구니·4·5점은 그대로가 가장 좋았고, prod는 1점을 −1.0(산 강의도 취향에서 뺀다)·2점을 −0.6으로 바꿨다.
     *
     * @param owned   구매({@code OWNED})
     * @param cart    장바구니(삭제 안 됨)
     * @param rating1 리뷰 1점 보정
     * @param rating2 리뷰 2점 보정
     * @param rating3 리뷰 3점 보정
     * @param rating4 리뷰 4점 보정
     * @param rating5 리뷰 5점 보정
     */
    public record Weights(
            @DefaultValue("1.0") @DecimalMin("0.0") double owned,
            @DefaultValue("0.6") @DecimalMin("0.0") double cart,
            @DefaultValue("-0.5") double rating1,
            @DefaultValue("-0.3") double rating2,
            @DefaultValue("0") double rating3,
            @DefaultValue("0.3") double rating4,
            @DefaultValue("0.5") double rating5) {

        /** 별점(1~5)의 보정값. 범위 밖은 0 */
        public double rating(int stars) {
            return switch (stars) {
                case 1 -> rating1;
                case 2 -> rating2;
                case 3 -> rating3;
                case 4 -> rating4;
                case 5 -> rating5;
                default -> 0;
            };
        }
    }

    /**
     * @param limit   한 주기에 회원 한 명이 관심사를 넣어 부를 수 있는 횟수
     * @param period  주기
     * @param maxKeys 메모리에 둘 회원 수 상한
     */
    public record RateLimit(
            @DefaultValue("10") @Positive int limit,
            @DefaultValue("1m") @NotNull Duration period,
            @DefaultValue("10000") @Positive long maxKeys) {
    }

    /** 위젯 API 전체의 회원별 호출 제한. 기본값만 {@link RateLimit}와 다르다(인플루언서 위젯과 같은 값) */
    public record WidgetRateLimit(
            @DefaultValue("60") @Positive int limit,
            @DefaultValue("1m") @NotNull Duration period,
            @DefaultValue("10000") @Positive long maxKeys) {
    }
}
