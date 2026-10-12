/**
 * 나중에 전역 {@code shared} 모듈로 옮길 후보.
 * <p>
 * 업무 규칙이 없고 여러 모듈이 그대로 같이 쓸 수 있는 것들을 모아 둔다.
 * 지금은 {@code shared} 모듈에 JPA 의존성이 없어서 출연 모듈 안에 둔다.
 * <ul>
 *     <li>{@code jpa}: 생성·수정 시각 베이스 엔티티</li>
 *     <li>{@code time}: 서비스 시간대 (Asia/Seoul)</li>
 * </ul>
 * 출연 모듈이 다른 모듈에 알리는 이벤트는 여기가 아니라 처음부터 전역 {@code shared}의 appearance 패키지에 둔다.
 */
package com.hapbang.appearance.shared;
