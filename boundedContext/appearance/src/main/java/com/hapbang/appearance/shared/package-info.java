/**
 * 나중에 전역 {@code shared} 모듈로 옮길 후보.
 * <p>
 * 업무 규칙이 없고 여러 모듈이 그대로 같이 쓸 수 있는 것들을 모아 둔다.
 * <ul>
 *     <li>{@code time}: 서비스 시간대 (Asia/Seoul)</li>
 * </ul>
 * 생성·수정 시각은 전역 {@code shared}의 {@code BaseEntity}를 쓴다 (#27에서 추가되어 #31에서 교체).
 * 출연 모듈이 다른 모듈에 알리는 이벤트는 여기가 아니라 처음부터 전역 {@code shared}의 appearance 패키지에 둔다.
 */
package com.hapbang.appearance.shared;
