package com.hapbang.chat.in.event.kafka;

/**
 * {@link TrustedEventDeserializer}가 돌려주는 표시 값. {@code __TypeId__}가 신뢰 패키지 바로 안의 클래스 이름인데 chat-app
 * 클래스패스에 그 클래스가 없다는 뜻이다(발행 앱이 새 이벤트를 먼저 배포한 경우). 본문은 읽지 않으며, 리스너는 건너뛴다.
 *
 * @param typeId 받은 {@code __TypeId__} 헤더 값(클래스 이름)
 */
public record MissingTypeEvent(String typeId) {
}
