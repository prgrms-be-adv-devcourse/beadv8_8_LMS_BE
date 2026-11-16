package com.hapbang.auxiliary.chatbot.in.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Size;

/**
 * 대화 요청 메시지의 글자 수는 코드 포인트로 센다 (#835). 이전 대화 검사({@code ChatHistory})·글 자르기({@code TextCut})와 같은 기준이다.
 * 이모지(서로게이트 쌍)는 한 글자다. 넘었을 때의 오류 메시지는 {@code @Size}와 같다.
 */
class ChatReqDtoLengthTest {

    private static final String EMOJI = "😀";
    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @AfterAll
    static void close() {
        FACTORY.close();
    }

    @Test
    @DisplayName("FAQ 대화: 이모지 500개(UTF-16 1,000)는 통과, 501개는 @Size(max=500)과 같은 메시지로 거절")
    void faqChatCountsCodePoints() {
        assertThat(EMOJI.repeat(500).length()).isEqualTo(1000);

        assertThat(VALIDATOR.validate(new FaqChatReqDto(EMOJI.repeat(500)))).isEmpty();
        assertThat(VALIDATOR.validate(new FaqChatReqDto("가".repeat(499) + EMOJI))).isEmpty();
        assertRejectedLikeSize(VALIDATOR.validate(new FaqChatReqDto(EMOJI.repeat(501))));
        assertRejectedLikeSize(VALIDATOR.validate(new FaqChatReqDto("가".repeat(500) + EMOJI)));
    }

    @Test
    @DisplayName("자유 질문: 이모지 500개는 통과, 501개는 @Size(max=500)과 같은 메시지로 거절")
    void chatMessageCountsCodePoints() {
        assertThat(VALIDATOR.validate(new ChatMessageReqDto(EMOJI.repeat(500), List.of()))).isEmpty();
        assertThat(VALIDATOR.validate(new ChatMessageReqDto("가".repeat(500), null))).isEmpty();
        assertRejectedLikeSize(VALIDATOR.validate(new ChatMessageReqDto(EMOJI.repeat(501), List.of())));
        assertRejectedLikeSize(VALIDATOR.validate(new ChatMessageReqDto("가".repeat(501), null)));
    }

    private static <T> void assertRejectedLikeSize(Set<ConstraintViolation<T>> violations) {
        String sizeMessage = VALIDATOR.validate(new SizeReference("가".repeat(501))).iterator().next().getMessage();
        assertThat(violations).singleElement().satisfies(violation -> {
            assertThat(violation.getPropertyPath()).hasToString("message");
            assertThat(violation.getMessage()).isEqualTo(sizeMessage);
        });
    }

    // 바꾸기 전 검사(@Size)가 내던 메시지
    private record SizeReference(@Size(max = 500) String message) {
    }
}
