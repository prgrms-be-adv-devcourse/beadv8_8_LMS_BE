package com.hapbang.auxiliary.chatbot.domain;

import java.math.BigDecimal;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * 강의 사본({@code chatbot.lecture_copy})의 지금 상태 중 추천·색인에 쓰는 값 (#718).
 *
 * @param contentTitles 삭제되지 않은 콘텐츠 제목(sequence 순)
 */
public record LectureSnapshot(
        long id,
        long sellerId,
        String title,
        @Nullable String description,
        List<String> contentTitles,
        boolean onSale,
        boolean deleted,
        boolean missingInSource) {

    /**
     * 해시에서 글과 제목 비율 사이에 넣는 표시: 줄바꿈 + NUL 문자 + {@code title-weight=}. 강의 글은 PostgreSQL text·jsonb 사본에서
     * 읽고, PostgreSQL은 이 두 타입에 NUL 문자를 담지 못하므로 어떤 강의 글도 이 표시를 포함할 수 없다
     * (그래서 비율 0의 해시인 "글만"과 우연히 같아지지 않는다)
     */
    public static final String TITLE_WEIGHT_MARK = "\n" + (char) 0 + "title-weight=";

    public LectureSnapshot {
        contentTitles = List.copyOf(contentTitles);
    }

    /** 임베딩하는 글: 제목 + 소개 + 콘텐츠 제목들(줄바꿈으로 잇는다). PDF·영상 본문은 넣지 않는다 */
    public String indexText() {
        StringBuilder text = new StringBuilder(title);
        if (description != null && !description.isBlank()) {
            text.append('\n').append(description);
        }
        for (String contentTitle : contentTitles) {
            text.append('\n').append(contentTitle);
        }
        return text.toString();
    }

    /**
     * 색인 벡터를 만든 재료의 SHA-256(16진수). 같으면 다시 임베딩하지 않는다.
     * <p>
     * 제목 비율 {@code titleWeight}(#1007)도 벡터를 바꾸므로 넣는다. 0이면 글만 해시해 #1007 전과 같은 값이다(제목 비율을 쓰지 않는
     * 실행이 배포만으로 전체를 다시 임베딩하지 않게). 0이 아니면 글 뒤에 {@link #TITLE_WEIGHT_MARK}(줄바꿈 + NUL 문자 +
     * {@code title-weight=})와 비율을 붙인다. 비율은 끝의 0을 뗀 십진수라 0.3과 0.30이 같은 해시다.
     */
    public String textHash(double titleWeight) {
        if (titleWeight == 0) {
            return Sha256.hex(indexText());
        }
        return Sha256.hex(indexText() + TITLE_WEIGHT_MARK
                + BigDecimal.valueOf(titleWeight).stripTrailingZeros().toPlainString());
    }

    /** 추천 후보가 될 수 있는 상태(판매 중, 삭제 안 됨, 원본에 있음). 판매자 숨김은 따로 본다 */
    public boolean recommendable() {
        return recommendable(onSale, deleted, missingInSource);
    }

    /** 추천 후보가 될 수 있는 상태의 규칙 한 곳({@link LectureListing}도 쓴다) */
    static boolean recommendable(boolean onSale, boolean deleted, boolean missingInSource) {
        return onSale && !deleted && !missingInSource;
    }
}
