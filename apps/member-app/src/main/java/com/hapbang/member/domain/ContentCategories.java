package com.hapbang.member.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 콘텐츠 분야 규칙 (#711): 0~5개, 중복 불가, 빈 코드 불가. 고른 순서를 지킨다.
 * 코드가 분야 목록에 있는지·새로 고를 수 있는지(active)는 저장소가 필요해 내 정보 수정에서 확인한다
 */
public final class ContentCategories {

    public static final int MAX_COUNT = 5;

    private ContentCategories() {
    }

    /** 사용자가 보낸 분야 코드 목록 검증. null이면 빈 목록으로 보고, 검증한 목록을 복사해 돌려준다 */
    public static List<String> validate(List<String> codes) {
        if (codes == null) {
            return List.of();
        }
        if (codes.size() > MAX_COUNT) {
            throw new MemberException(MemberErrorCode.INVALID_CONTENT_CATEGORY_COUNT);
        }
        Set<String> seen = new HashSet<>();
        for (String code : codes) {
            if (code == null || code.isBlank()) {
                throw new MemberException(MemberErrorCode.INVALID_CONTENT_CATEGORY);
            }
            if (!seen.add(code)) {
                throw new MemberException(MemberErrorCode.DUPLICATE_CONTENT_CATEGORY);
            }
        }
        return List.copyOf(codes);
    }
}
