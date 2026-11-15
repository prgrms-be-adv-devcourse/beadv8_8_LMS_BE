package com.hapbang.auxiliary.search.lecture.app;

import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.search.common.app.ZeroResultKeywords;
import com.hapbang.auxiliary.search.lecture.out.LectureIndexDefinition;

/** 강의 검색의 0건 검색어(#883 L19). 회원은 회원 ID, 비회원은 client IP 해시로 센다. 생성자만 둔다 */
@Component
public class LectureZeroResultKeywords extends ZeroResultKeywords {

    public LectureZeroResultKeywords() {
        super(LectureIndexDefinition.METRIC_TARGET);
    }
}
