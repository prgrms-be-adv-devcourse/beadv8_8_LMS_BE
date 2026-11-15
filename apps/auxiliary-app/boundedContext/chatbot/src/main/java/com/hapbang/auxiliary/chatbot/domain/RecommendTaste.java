package com.hapbang.auxiliary.chatbot.domain;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * 리랭킹 프롬프트에 넣는 요청 회원의 취향(#948). <b>본인이 이번에 입력한 글과 공개 프로필만 담는다.</b>
 * 구매·장바구니·리뷰·함께 출연한 상대·회원 ID·날짜는 담지 않는다(담을 자리가 없다).
 * <p>
 * 취향을 쓸지는 설정({@code rerank.taste})을 읽는 쪽이 한 번만 정한다. 쓰지 않으면 {@link #NOT_USED}이고, 쓰는데 넣을 것이 없으면
 * {@link #isUsedButEmpty()}다(근거 없이 순서를 바꾸지 않는다).
 *
 * @param used          취향을 쓰는 설정인지
 * @param interest      이번 요청의 관심사 문장(이미 임베딩·쿼리 확장에 쓰는 글). 없으면 null
 * @param categoryNames 인플루언서의 공개 프로필 분야 이름(일반 회원은 비어 있다, #711)
 * @param bio           인플루언서의 공개 자기소개(잘라서). 없으면 null
 */
public record RecommendTaste(boolean used, @Nullable String interest, List<String> categoryNames, @Nullable String bio) {

    /** 취향을 쓰지 않는다(#720과 같은 프롬프트) */
    public static final RecommendTaste NOT_USED = new RecommendTaste(false, null, List.of(), null);

    public RecommendTaste {
        interest = blankToNull(interest);
        categoryNames = List.copyOf(categoryNames);
        bio = blankToNull(bio);
    }

    /** 취향을 쓰는 설정에서 모은 취향 */
    public static RecommendTaste of(@Nullable String interest, List<String> categoryNames, @Nullable String bio) {
        return new RecommendTaste(true, interest, categoryNames, bio);
    }

    /** 취향을 쓰는 설정인데 넣을 것이 없다(관심사 없는 일반 회원) */
    public boolean isUsedButEmpty() {
        return used && interest == null && categoryNames.isEmpty() && bio == null;
    }

    /** 프롬프트에 넣는 줄. 쓰지 않거나 비어 있는 항목은 뺀다 */
    public List<String> lines() {
        List<String> lines = new ArrayList<>();
        if (!used) {
            return lines;
        }
        if (interest != null) {
            lines.add("관심사: " + oneLine(interest));
        }
        if (!categoryNames.isEmpty()) {
            lines.add("활동 분야: " + String.join(", ", categoryNames));
        }
        if (bio != null) {
            lines.add("자기소개: " + oneLine(bio));
        }
        return lines;
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    // 줄바꿈으로 프롬프트의 다른 줄처럼 보이지 않게 한 줄로 만든다
    private static String oneLine(String value) {
        return value.replaceAll("\\s+", " ");
    }
}
