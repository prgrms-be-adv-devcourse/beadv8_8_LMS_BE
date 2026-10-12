/**
 * 나중에 전역 {@code global} 모듈로 옮길 후보.
 * <p>
 * 로그인 회원 처리, 오류 응답 형식처럼 모든 모듈에 필요하지만 아직 팀 공통 방식이 정해지지 않은 것들을 모아 둔다.
 * 지금은 출연 모듈이 빌드상 {@code global}에 의존하지 않아서 출연 모듈 안에 둔다.
 * <ul>
 *     <li>{@code web}: {@code @LoginMember}, 회원 ID 헤더 처리 — 세션 키가 정해지면 팀 공통으로 옮긴다</li>
 *     <li>{@code exception}: Problem Detail 오류 응답 — 팀 공통 응답·예외 형식이 정해지면 옮긴다</li>
 * </ul>
 * 옮길 때는 채팅 모듈의 같은 역할 클래스({@code ChatHeaders}, {@code ChatExceptionHandler})와 함께 합친다.
 */
package com.hapbang.appearance.global;
