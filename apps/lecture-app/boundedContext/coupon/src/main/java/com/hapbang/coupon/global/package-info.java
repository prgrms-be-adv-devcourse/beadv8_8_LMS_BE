/**
 * 쿠폰 전용 공통 코드(오류 응답 처리기, 배치 켜기 애노테이션, 인증 principal 읽기, in·out이 함께 쓰는 Kafka 이름) 자리.
 * 강의의 global을 가져다 쓰지 않는다.
 * <ul>
 *     <li>{@code response}: 성공 응답 {@code RsData}(강의와 같은 모양)</li>
 *     <li>{@code exception}: 쿠폰 업무 예외의 응답 핸들러. 오류 코드 {@code CouponErrorCode}와 업무 예외 {@code CouponException}은
 *     {@code app/exception}에 있다. 나머지 오류는 같은 JVM의 강의 공통 핸들러가 응답한다</li>
 *     <li>{@code kafka}: 발급 소비자의 그룹·DLT 이름({@code CouponIssueConsumerNames}). 소비 설정({@code in/kafka})과 복구 경계 확인
 *     ({@code out/kafka})이 함께 쓴다</li>
 *     <li>{@code auth}: 로그인 회원 ID 파라미터 {@code @LoginUserId}와 리졸버(등록은 {@code config})</li>
 * </ul>
 */
package com.hapbang.coupon.global;
