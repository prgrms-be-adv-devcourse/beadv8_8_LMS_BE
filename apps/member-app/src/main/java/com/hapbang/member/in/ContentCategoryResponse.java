package com.hapbang.member.in;

import java.util.List;
import java.util.Map;

/** 콘텐츠 분야 (#711). 코드는 바뀌지 않고, 이름은 바뀔 수 있다 */
public record ContentCategoryResponse(String code, String name) {

    /** 회원이 고른 순서대로 코드에 이름을 붙인다. names는 꺼진 분야도 담은 코드 → 이름 */
    public static List<ContentCategoryResponse> of(List<String> codes, Map<String, String> names) {
        return codes.stream().map(code -> new ContentCategoryResponse(code, names.get(code))).toList();
    }
}
