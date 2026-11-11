package com.hapbang.auxiliary.search.common.out;

/**
 * 검색 alias가 아직 없다. 처음 적재(#773)가 끝나기 전이다(처음 배포, ES를 새로 만듦).
 * <p>
 * 쓸 곳이 없으므로 이벤트 소비·증분 보정·통계 동기화·정리는 이 예외를 받으면 실패가 아니라 "건너뜀"으로 처리한다(#773 Q1). 건너뛴 변경은
 * 처음 적재가 시각 T 뒤에 회원 목록을 읽고, alias를 붙인 뒤 T − 1분부터 따라잡아 반영한다.
 * <p>
 * {@link IllegalStateException}의 하위라 검색 API는 지금처럼 503 {@code SEARCH_UNAVAILABLE}이다.
 */
public class IndexNotReadyException extends IllegalStateException {

    public IndexNotReadyException(String message) {
        super(message);
    }
}
