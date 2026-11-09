package com.hapbang.member.app;

import static java.util.stream.Collectors.toUnmodifiableMap;

import java.util.Map;

import org.springframework.stereotype.Service;

import com.hapbang.member.domain.ContentCategory;
import com.hapbang.member.out.ContentCategoryRepository;

import lombok.RequiredArgsConstructor;

/** 콘텐츠 분야 목록 조회 (#711) */
@Service
@RequiredArgsConstructor
public class MemberContentCategoryUseCase {

    private final ContentCategoryRepository contentCategoryRepository;

    /**
     * 분야 코드 → 이름. 응답에 분야 이름을 붙일 때 쓴다. 이미 고른 회원의 값은 계속 보이므로 목록에서 꺼진 분야도 넣는다 (Q50).
     * 분야는 수십 개라 모두 읽는다
     */
    public Map<String, String> names() {
        return contentCategoryRepository.findAll().stream()
                .collect(toUnmodifiableMap(ContentCategory::getCode, ContentCategory::getName));
    }
}
