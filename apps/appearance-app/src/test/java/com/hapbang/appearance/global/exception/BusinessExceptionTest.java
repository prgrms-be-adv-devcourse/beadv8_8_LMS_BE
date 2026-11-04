package com.hapbang.appearance.global.exception;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class BusinessExceptionTest {

    @Test
    void 상세_메시지가_없으면_오류_코드의_기본_메시지를_쓴다() {
        BusinessException exception = new BusinessException(GlobalErrorCode.NOT_FOUND);

        assertThat(exception.getMessage()).isEqualTo(GlobalErrorCode.NOT_FOUND.getMessage());
        assertThat(exception.getErrorCode()).isEqualTo(GlobalErrorCode.NOT_FOUND);
    }

    @Test
    void 상세_메시지를_주면_그_메시지를_쓰고_오류_코드는_같다() {
        BusinessException exception = new BusinessException(GlobalErrorCode.NOT_FOUND, "채팅방을 찾을 수 없습니다.");

        assertThat(exception.getMessage()).isEqualTo("채팅방을 찾을 수 없습니다.");
        assertThat(exception.getErrorCode()).isEqualTo(GlobalErrorCode.NOT_FOUND);
    }

    @Test
    void 원인_예외를_담을_수_있다() {
        IllegalStateException cause = new IllegalStateException("원인");

        BusinessException exception = new BusinessException(GlobalErrorCode.CONFLICT, cause);

        assertThat(exception.getCause()).isSameAs(cause);
        assertThat(exception.getMessage()).isEqualTo(GlobalErrorCode.CONFLICT.getMessage());
    }

    @Test
    void 공통_코드의_응답_코드는_enum_이름이다() {
        assertThat(GlobalErrorCode.NOT_FOUND.getCode()).isEqualTo("NOT_FOUND");
    }

    @Test
    void 추가_속성은_읽기_전용으로_꺼낸다() {
        BusinessException exception = new PropertyException("reason", "PERIOD_EXPIRED");

        assertThat(exception.getProperties()).containsEntry("reason", "PERIOD_EXPIRED");
        assertThatThrownBy(() -> exception.getProperties().put("other", "x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void 예약된_속성은_추가할_수_없다() {
        assertThatThrownBy(() -> new PropertyException("code", "OTHER"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PropertyException("status", 200))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static class PropertyException extends BusinessException {

        PropertyException(String name, Object value) {
            super(GlobalErrorCode.CONFLICT);
            addProperty(name, value);
        }
    }
}
