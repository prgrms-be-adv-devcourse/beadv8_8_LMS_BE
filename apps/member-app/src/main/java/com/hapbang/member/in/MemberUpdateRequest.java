package com.hapbang.member.in;

import java.util.List;

/**
 * 내 정보 수정 요청. 보내지 않은 값(null)은 바꾸지 않는다.
 * contentCategories(분야 코드 목록)·bio(자기소개)는 빈 목록·빈 문자열을 보내면 지운다 (#711)
 */
public record MemberUpdateRequest(String nickname, List<String> contentCategories, String bio) {
}
