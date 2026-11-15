package com.hapbang.auxiliary.chatbot.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 글 해시(UTF-8 SHA-256, 16진수 소문자 64자)의 값을 고정한다 (#835). 기대값은 {@code sha256sum}으로 낸 값이다.
 * 값이 바뀌면 FAQ·강의·인플루언서 문서를 모두 다시 임베딩하므로, 계산 위치를 옮겨도 같아야 한다.
 */
class TextHashTest {

    @Test
    @DisplayName("글은 UTF-8로 해시한다(FAQ 내용 해시가 쓴다). 바이트 해시도 같은 값이다")
    void sha256() {
        assertThat(Sha256.hex("안녕하세요")).isEqualTo("2c68318e352971113645cbc72861e1ec23f48d5baa5f9b405fed9dddca893eb4");
        assertThat(Sha256.hex("HP\n질문")).isEqualTo("cfa7d665a761a863c2d212d44c2233fee1a2bebeb05e694734cb9f889956636c");
        assertThat(Sha256.hex("abc".getBytes(StandardCharsets.US_ASCII)))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    @DisplayName("인플루언서 프로필 글 해시. 빈 글도 해시가 있다")
    void influencerProfileText() {
        assertThat(InfluencerProfileText.of(List.of("게임"), null).hash())
                .isEqualTo("009843d54b51d09a3695e4aa9d57e177a2806b9ef3e536a870e5adab7bc03617");
        assertThat(InfluencerProfileText.of(List.of(), null).hash())
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    @DisplayName("강의 색인 글 해시")
    void lectureSnapshot() {
        LectureSnapshot snapshot = new LectureSnapshot(1L, 2L, "강의", "소개글", List.of("첫째"), true, false, false);

        assertThat(snapshot.textHash()).isEqualTo("1f083d68395ee70febd4aaa3c1a4f52ce29330f1d848bf45f02644f7272d84eb");
    }
}
