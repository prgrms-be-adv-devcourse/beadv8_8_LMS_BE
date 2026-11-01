package com.hapbang.chat.in.event.kafka;

import org.springframework.kafka.support.KafkaNull;
import org.springframework.util.ClassUtils;

/**
 * 기본 처리기({@code @KafkaHandler(isDefault = true)})가 받은 값을 건너뛸지 정한다. 건너뛰는 것은 셋뿐이다: tombstone,
 * {@link MissingTypeEvent}, 패키지가 정확히 신뢰 패키지인 다른 레코드. 그 밖(역직렬화기 기본 신뢰 타입 {@code java.lang}·
 * {@code java.util} 등)은 신뢰 밖 타입이라 {@link RejectedEventException}으로 재시도 없이 DLT로 보낸다.
 */
public final class SkippablePayloads {

    public static final String TOMBSTONE = "빈 값(tombstone)";

    private SkippablePayloads() {
    }

    /**
     * @return 건너뛸 수 있으면 로그에 쓸 종류 이름
     * @throws RejectedEventException 신뢰 패키지 밖 타입
     */
    public static String typeToSkip(Object payload, String trustedPackage) {
        if (payload == null || payload instanceof KafkaNull) {
            return TOMBSTONE;
        }
        if (payload instanceof MissingTypeEvent missing) {
            return missing.typeId();
        }
        Class<?> type = payload.getClass();
        if (trustedPackage.equals(ClassUtils.getPackageName(type))) {
            return type.getName();
        }
        throw new RejectedEventException("이벤트 신뢰 패키지 밖의 타입입니다: " + type.getName(), null);
    }
}
