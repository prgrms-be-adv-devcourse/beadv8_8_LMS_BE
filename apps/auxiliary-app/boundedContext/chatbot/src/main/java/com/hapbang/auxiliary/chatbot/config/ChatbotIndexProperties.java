package com.hapbang.auxiliary.chatbot.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import com.hapbang.auxiliary.chatbot.domain.IndexType;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 추천 벡터 index 설정 {@code hapbang.chatbot.index} (#718 공통 기반). 강의(#718)·인플루언서(#719) index가 함께 쓴다.
 * 공급자(임베딩 모델)마다 값이 달라 프로필 파일에서 정한다(local=Ollama, prod=OpenAI). FAQ index는 따로({@link ChatbotFaqProperties}).
 *
 * @param lectureName    강의 추천 index 이름. 규칙 {@code chatbot-lectures-<공급자>-<차원>}. 모델을 바꾸면 새 index에 다시 색인한다
 * @param influencerName 인플루언서 추천 index 이름. 규칙 {@code chatbot-influencers-<공급자>-<차원>}
 * @param dimensions     임베딩 차원. 모델과 맞아야 한다
 * @param documentPrefix 문서를 임베딩할 때 앞에 붙이는 글(nomic-embed-text는 {@code search_document: })
 * @param queryPrefix    검색어(관심사 등)를 임베딩할 때 앞에 붙이는 글(nomic-embed-text는 {@code search_query: })
 * @param task           색인 작업 실행기
 */
@Validated
@ConfigurationProperties("hapbang.chatbot.index")
public record ChatbotIndexProperties(
        @NotBlank String lectureName,
        @NotBlank String influencerName,
        @Positive int dimensions,
        @DefaultValue("") String documentPrefix,
        @DefaultValue("") String queryPrefix,
        @DefaultValue @Valid Task task) {

    /** 종류의 ES index 이름 */
    public String nameOf(IndexType type) {
        return switch (type) {
            case LECTURE -> lectureName;
            case INFLUENCER -> influencerName;
        };
    }

    /**
     * 색인 작업({@code chatbot.index_task}) 실행기 설정.
     *
     * @param pollInterval       할 일을 찾는 주기(배치를 켠 실행만)
     * @param batchSize          한 번에 가져가는 요청 수
     * @param lease              가져간 요청을 다른 pod가 다시 가져가지 못하는 시간. 한 번의 실행(임베딩 + ES)보다 넉넉해야 한다
     * @param retryInitial       첫 실패 뒤 다시 시도할 때까지의 시간. 실패할 때마다 두 배
     * @param retryMax           다시 시도 간격의 상한
     * @param alertAfterAttempts 이 횟수 이상 실패하면 ERROR로 알린다
     * @param doneRetention      끝난 요청을 남기는 기간
     */
    public record Task(
            @DefaultValue("5s") @NotNull Duration pollInterval,
            @DefaultValue("50") @Positive int batchSize,
            @DefaultValue("5m") @NotNull Duration lease,
            @DefaultValue("30s") @NotNull Duration retryInitial,
            @DefaultValue("1h") @NotNull Duration retryMax,
            @DefaultValue("10") @Positive int alertAfterAttempts,
            @DefaultValue("7d") @NotNull Duration doneRetention) {
    }
}
