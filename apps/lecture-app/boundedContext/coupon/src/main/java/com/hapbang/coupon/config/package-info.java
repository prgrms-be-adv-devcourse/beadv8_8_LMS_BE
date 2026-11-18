/**
 * 쿠폰 설정 자리: app이 읽는 설정값({@code *Properties})과 그 등록({@code *Config}), 시계. 어댑터 설정은 그 어댑터 옆(in·out)에
 * 둔다: Kafka 소비 설정은 {@code in/kafka}, 매진 빠른 경로 필터 등록은 {@code in}, 토픽·{@code @HttpExchange} 클라이언트는
 * {@code out}.
 */
package com.hapbang.coupon.config;
