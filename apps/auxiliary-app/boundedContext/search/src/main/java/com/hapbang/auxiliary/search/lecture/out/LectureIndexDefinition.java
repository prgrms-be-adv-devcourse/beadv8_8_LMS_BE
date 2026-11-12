package com.hapbang.auxiliary.search.lecture.out;

import com.hapbang.auxiliary.search.common.out.IndexDefinition;

/**
 * 강의 검색 index의 이름과 정의(#882). 정의를 만드는 일은 공통 {@link IndexDefinition}이 하고, 여기는 대상 이름과 alias만 둔다.
 * 설정·mapping은 {@code search/lectures/index.json}이고, 사용자 사전·동의어 묶음은 인플루언서와 함께 쓴다(L17).
 */
public final class LectureIndexDefinition {

    /** 대상 이름. {@code search/lectures/index.json}을 읽는다 */
    public static final String TARGET = "lectures";
    /** 검색·쓰기에 쓰는 alias({@code search-lectures}) */
    public static final String ALIAS = IndexDefinition.aliasOf(TARGET);
    /** index 이름 앞부분. 뒤에 설정 해시가 붙는다 */
    public static final String INDEX_PREFIX = ALIAS + "-";
    /** 지표·로그의 대상 태그 값({@code target=lecture}) */
    public static final String METRIC_TARGET = "lecture";

    private LectureIndexDefinition() {
    }

    /** 클래스패스의 레포 파일로 만든다 */
    public static IndexDefinition load() {
        return IndexDefinition.load(TARGET);
    }
}
