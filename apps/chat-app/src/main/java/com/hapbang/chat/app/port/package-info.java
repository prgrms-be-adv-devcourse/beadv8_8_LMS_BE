/**
 * 채팅 app이 바깥(DB, Redis, 회원·외부 앱 내부 API, 이 JVM의 WebSocket 연결, 메모리 캐시)에 요구하는 계약. 기술 port의 구현은
 * {@code out}에, 로컬 연결·전송 callback({@code LocalChatConnections}·{@code LocalChatSender})의 구현은 {@code in/websocket}에 두고,
 * {@code XxxRepository}의 구현은 Spring Data가 만든다.
 * <p>
 * DB 계약은 {@code XxxRepository}다. Spring Data Commons {@code Repository}만 상속하고, 쓰는 기본 메서드와 메서드 이름 쿼리,
 * {@code @Query}·{@code @Modifying}·{@code @Lock}을 여기에 선언한다. QueryDSL 조회는 {@code XxxRepositoryCustom}에 선언하고
 * {@code out}의 {@code XxxRepositoryCustomImpl}이 구현한다(연결은 {@code META-INF/spring.factories}). 계약에만 쓰는 결과 값(조회
 * 묶음 {@code XxxRows}, 큐 작업 record)과 어댑터가 계약 대신 던지는 예외도 이 패키지에 둔다. {@code in}도 보는 값은
 * {@code app/dto}에 둔다.
 */
package com.hapbang.chat.app.port;
