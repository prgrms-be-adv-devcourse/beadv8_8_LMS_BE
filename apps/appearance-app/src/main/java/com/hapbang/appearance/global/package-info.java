/**
 * 나중에 전역 {@code global} 모듈로 옮길 후보.
 * <p>
 * 모든 모듈에 필요하지만 아직 팀 공통 방식이 정해지지 않은 것들을 모아 둔다.
 * <ul>
 *     <li>{@code exception}: Problem Detail 오류 응답 — #71에서 전역 {@code global}의 {@code GlobalExceptionHandler}로 옮겼다.</li>
 * </ul>
 * 로그인 회원 ID는 전역 {@code global}의 {@code @LoginUserId}를 쓴다 (#27에서 추가되어 #31에서 교체).
 */
package com.hapbang.appearance.global;
