package com.hapbang.auxiliary.search.lecture.app.dto;

/**
 * 강의 검색어 자동완성 후보 하나(#973). 누르면 그 강의로 바로 가거나({@code lectureId}), 제목으로 검색할 수 있게({@code title}) 둘 다 준다.
 * 검색어가 걸린 곳 표시(highlight)는 없다(초성·자모로 맞은 후보는 원문에 강조할 자리가 없다).
 *
 * @param lectureId      강의 ID
 * @param title          제목(원문)
 * @param sellerNickname 판매자 닉네임. 아직 모르면 null
 */
public record LectureSuggestion(long lectureId, String title, String sellerNickname) {
}
