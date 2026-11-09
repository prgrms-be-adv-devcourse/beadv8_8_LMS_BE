package com.hapbang.member.in;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.member.app.MemberFacade;
import com.hapbang.member.global.response.RsData;

import lombok.RequiredArgsConstructor;

/** 콘텐츠 분야 API (#711) */
@RestController
@RequestMapping("/api/v1/members/content-categories")
@RequiredArgsConstructor
public class ApiV1MemberContentCategoryController implements ApiV1MemberContentCategorySpec {

    private final MemberFacade memberFacade;

    /** 콘텐츠 분야 목록 (고를 수 있는 것만, 많이 고른 순) */
    @Override
    @GetMapping
    public RsData<List<ContentCategoryResponse>> contentCategories() {
        return RsData.ok("콘텐츠 분야 목록을 조회했습니다.", memberFacade.findContentCategories().stream()
                .map(category -> new ContentCategoryResponse(category.getCode(), category.getName()))
                .toList());
    }
}
