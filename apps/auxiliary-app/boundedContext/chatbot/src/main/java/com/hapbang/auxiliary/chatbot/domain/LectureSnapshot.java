package com.hapbang.auxiliary.chatbot.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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

    /** 임베딩하는 글의 SHA-256(16진수). 같으면 다시 임베딩하지 않는다 */
    public String textHash() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(indexText().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없습니다", e);
        }
    }

    /** 추천 후보가 될 수 있는 상태(판매 중, 삭제 안 됨, 원본에 있음). 판매자 숨김은 따로 본다 */
    public boolean recommendable() {
        return onSale && !deleted && !missingInSource;
    }
}
