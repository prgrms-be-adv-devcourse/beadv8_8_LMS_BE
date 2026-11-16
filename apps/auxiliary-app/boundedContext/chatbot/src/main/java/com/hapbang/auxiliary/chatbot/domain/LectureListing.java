package com.hapbang.auxiliary.chatbot.domain;

import org.jspecify.annotations.Nullable;

/**
 * 추천이 읽는 강의 사본 한 줄 (#835). 색인 글에만 쓰는 콘텐츠 제목은 읽지 않는다(색인은 {@link LectureSnapshot}).
 *
 * @param description  소개(버튼 경로 프롬프트의 후보 줄). 없으면 null
 * @param sellerVisible 판매자를 추천에 보여도 되는지(회원 보임 규칙, {@code MemberVisibility}와 같은 규칙을 같은 조회에서 본다)
 */
public record LectureListing(
        long id,
        long sellerId,
        String title,
        @Nullable String description,
        boolean onSale,
        boolean deleted,
        boolean missingInSource,
        boolean sellerVisible) {

    /** 추천 후보가 될 수 있는 상태. {@link LectureSnapshot#recommendable()}과 같은 규칙 */
    public boolean recommendable() {
        return LectureSnapshot.recommendable(onSale, deleted, missingInSource);
    }
}
