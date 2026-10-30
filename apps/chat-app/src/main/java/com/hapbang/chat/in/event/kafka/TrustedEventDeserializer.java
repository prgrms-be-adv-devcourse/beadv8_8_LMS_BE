package com.hapbang.chat.in.event.kafka;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.serialization.Deserializer;
import org.springframework.kafka.support.mapping.DefaultJacksonJavaTypeMapper;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;

/**
 * chat-app 이벤트 소비의 value 역직렬화기. {@link #errorHandling}으로 {@link ErrorHandlingDeserializer}에 감싸 쓴다.
 * <ul>
 *     <li>{@link JacksonJsonDeserializer}(Jackson 3)가 {@code __TypeId__} 헤더의 클래스 이름으로 타입을 정한다. 신뢰 패키지는 넘긴
 *     실제 이벤트 패키지 하나다(와일드카드 불가). spring-kafka의 기본 신뢰 패키지({@code java.util}, {@code java.lang})는 지울 수
 *     없으므로 리스너가 신뢰 패키지 밖 타입을 거부한다.</li>
 *     <li>{@code __TypeId__} 헤더를 지우지 않는다. 본문을 읽지 못해 DLT로 보낸 원본에 타입이 남아야 고쳐 다시 넣을 수 있다.</li>
 *     <li>{@code __TypeId__}가 신뢰 패키지 <b>바로 안</b>(하위 패키지 아님)의 클래스 이름인데 클래스패스에 없으면 본문을 읽지 않고
 *     {@link MissingTypeEvent}를 돌려준다(발행 앱이 새 이벤트를 먼저 배포한 경우, 리스너가 건너뛴다). 신뢰 검사는 넓히지 않는다.
 *     한계: 신뢰 패키지 안의 잘못 적은 클래스 이름도 같은 값이 된다.</li>
 *     <li>값 바이트가 있는데 읽은 결과가 null이면(예: {@code java.lang.Void}, JSON {@code null}) 역직렬화 실패로 남겨 진짜
 *     tombstone(바이트 null)과 구분한다.</li>
 *     <li>그 밖의 실패(신뢰 밖 타입, 타입 헤더 없음, 깨진 본문)는 역직렬화 실패이고, 오류 처리기가 재시도 없이 DLT로 보낸다.</li>
 * </ul>
 */
public final class TrustedEventDeserializer implements Deserializer<Object> {

    private final JacksonJsonDeserializer<Object> delegate;
    private final String trustedPackage;

    private TrustedEventDeserializer(String trustedPackage) {
        Assert.hasText(trustedPackage, "신뢰 패키지가 필요합니다.");
        Assert.isTrue(!trustedPackage.contains("*"), () -> "신뢰 패키지에 와일드카드를 쓸 수 없습니다: " + trustedPackage);
        this.trustedPackage = trustedPackage;
        this.delegate = new JacksonJsonDeserializer<>();
        this.delegate.addTrustedPackages(trustedPackage);
        this.delegate.setRemoveTypeHeaders(false);
    }

    /** ConsumerFactory에 넣을 value 역직렬화기 */
    public static ErrorHandlingDeserializer<Object> errorHandling(String trustedPackage) {
        return new ErrorHandlingDeserializer<>(new TrustedEventDeserializer(trustedPackage));
    }

    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {
        delegate.configure(configs, isKey);
    }

    @Override
    public Object deserialize(String topic, byte[] data) {
        return delegate.deserialize(topic, data);
    }

    @Override
    public Object deserialize(String topic, Headers headers, byte[] data) {
        if (data == null) {
            return delegate.deserialize(topic, headers, data);
        }
        String typeId = typeId(headers);
        if (typeId != null && isMissingTrustedClass(typeId)) {
            return new MissingTypeEvent(typeId);
        }
        Object value = delegate.deserialize(topic, headers, data);
        if (value == null) {
            // 받는 쪽이 tombstone으로 오인해 건너뛰지 않도록 역직렬화 실패로 남긴다(값은 메시지에 넣지 않는다)
            throw new SerializationException("값 바이트가 있는데 읽은 결과가 null입니다. type=" + typeId);
        }
        return value;
    }

    @Override
    public void close() {
        delegate.close();
    }

    private boolean isMissingTrustedClass(String typeId) {
        // 패키지가 신뢰 패키지와 정확히 같을 때만 본다(하위 패키지·배열 표기는 그대로 역직렬화기가 판단한다)
        if (!trustedPackage.equals(ClassUtils.getPackageName(typeId))) {
            return false;
        }
        try {
            ClassUtils.forName(typeId, ClassUtils.getDefaultClassLoader());
            return false;
        } catch (ClassNotFoundException missing) {
            return true;
        } catch (LinkageError broken) {
            return false;
        }
    }

    private static String typeId(Headers headers) {
        Header header = headers.lastHeader(DefaultJacksonJavaTypeMapper.DEFAULT_CLASSID_FIELD_NAME);
        return header == null || header.value() == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
