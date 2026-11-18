/**
 * 채팅 설정 자리: app이 읽는 업무 설정값({@code @ConfigurationProperties})과 그 등록({@code XxxConfig}). 한 기술 어댑터에만 붙는
 * 설정(HTTP 클라이언트·Kafka 소비·Redis 연결)은 {@code in}·{@code out}의 그 어댑터 옆에 둔다.
 */
package com.hapbang.chat.config;
