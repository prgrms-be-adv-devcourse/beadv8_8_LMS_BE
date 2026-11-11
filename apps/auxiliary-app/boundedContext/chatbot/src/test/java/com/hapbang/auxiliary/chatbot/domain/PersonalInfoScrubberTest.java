package com.hapbang.auxiliary.chatbot.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 자기소개의 연락처 지우기 (#717). LLM·임베딩에 넘기기 전에 거친다 */
class PersonalInfoScrubberTest {

    @ParameterizedTest
    @ValueSource(strings = {"hello@hapbang.com", "first.last+tag@mail.example.co.kr", "A_B-1@x-y.io"})
    @DisplayName("이메일을 지운다")
    void email(String email) {
        assertThat(PersonalInfoScrubber.scrub("연락은 " + email + " 로 주세요"))
                .isEqualTo("연락은 " + PersonalInfoScrubber.EMAIL_MARK + " 로 주세요");
    }

    @ParameterizedTest
    @ValueSource(strings = {"010-1234-5678", "01012345678", "010 1234 5678", "010.1234.5678", "+82 10-1234-5678",
            "+821012345678", "02-123-4567", "031-1234-5678", "070-1234-5678", "1588-1234", "016-123-4567"})
    @DisplayName("휴대폰·지역번호·인터넷 전화·대표번호를 지운다")
    void phone(String phone) {
        assertThat(PersonalInfoScrubber.scrub("문의 " + phone + "!"))
                .isEqualTo("문의 " + PersonalInfoScrubber.PHONE_MARK + "!");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "구독자 12345678명",
            "2026-11-09 방송",
            "주문번호 9010123456789",
            "게임 3시간 방송, 100-200명 시청"
    })
    @DisplayName("연락처가 아닌 숫자는 그대로 둔다")
    void notContact(String text) {
        assertThat(PersonalInfoScrubber.scrub(text)).isEqualTo(text);
    }

    @Test
    @DisplayName("둘 다 있으면 둘 다 지우고, null은 null이다")
    void both() {
        assertThat(PersonalInfoScrubber.scrub("메일 a@b.com, 전화 010-1111-2222"))
                .isEqualTo("메일 [이메일], 전화 [전화번호]");
        assertThat(PersonalInfoScrubber.scrub(null)).isNull();
    }
}
