package com.hapbang.coupon.app;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import com.hapbang.shared.coupon.event.CouponIssueRequested;
import com.hapbang.shared.lecture.event.OrderItemCancelCompletedEvent;
import com.hapbang.shared.lecture.event.OrderItemPaidEvent;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * DLT 격리 기록의 원문(이벤트 JSON) 읽기·쓰기. 재처리·영구 거절이 원문에서 이벤트를 다시 읽는다.
 * 역직렬화에 실패해 격리된 메시지는 받은 바이트를 {@link #rawText}로 남긴 값이라 이벤트로 읽지 못한다(재처리 불가, 영구 거절로 닫는다).
 * <ul>
 *     <li>발급 요청: {@link CouponIssueRequested}.</li>
 *     <li>주문 결제·취소 완료: {@link OrderItemPaidEvent}·{@link OrderItemCancelCompletedEvent}. 원문만으로 어느 이벤트인지 가르려고
 *     결정적 eventId의 접두어({@code order-item-paid:}·{@code order-item-cancel-completed:})를 본다.</li>
 * </ul>
 */
public final class CouponDltPayload {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private CouponDltPayload() {
    }

    /** {@link #rawText}가 그대로 저장할 수 없는 원문 앞에 붙이는 접두어. 뒤는 받은 바이트의 Base64(표준 알파벳, 패딩 있음)다 */
    public static final String BASE64_PREFIX = "base64:";

    /**
     * 역직렬화에 실패한 메시지의 받은 바이트를 격리 기록 원문(PostgreSQL text)으로 바꾼다. 관리자 DLT 조회 응답에 이 값이 그대로 보인다.
     * <ul>
     *     <li>UTF-8로 바르게 읽히고 NUL(0x00)이 없으면 그 문자열 그대로다.</li>
     *     <li>아니면 {@value #BASE64_PREFIX} + 받은 바이트의 Base64다. text 컬럼은 NUL을 저장하지 못해, 그대로 두면 격리 기록 저장이 계속
     *     실패하고 DLT로 보내지 못해 그 partition 소비가 멈춘다. 원래 바이트는 Base64를 풀어 되찾는다.</li>
     *     <li>그대로 읽히는 문자열이라도 {@value #BASE64_PREFIX}로 시작하면 Base64로 남긴다. 접두어가 붙은 값은 언제나 인코딩한 값이다.</li>
     * </ul>
     */
    public static String rawText(byte[] data) {
        if (data == null) {
            return "";
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data))
                    .toString();
            if (text.indexOf('\0') < 0 && !text.startsWith(BASE64_PREFIX)) {
                return text;
            }
        } catch (CharacterCodingException exception) {
            // UTF-8로 읽히지 않는 바이트다. 아래에서 Base64로 남긴다
        }
        return BASE64_PREFIX + Base64.getEncoder().encodeToString(data);
    }

    public static String write(CouponIssueRequested event) {
        return JSON.writeValueAsString(event);
    }

    /** 원문을 발급 요청 이벤트로 읽는다. 읽지 못하면 빈 값 */
    public static Optional<CouponIssueRequested> read(String payload) {
        if (payload == null || payload.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(JSON.readValue(payload, CouponIssueRequested.class));
        } catch (JacksonException exception) {
            return Optional.empty();
        }
    }

    /** 주문 결제·취소 완료 이벤트를 원문으로 쓴다 */
    public static String writeOrderEvent(Object event) {
        if (!(event instanceof OrderItemPaidEvent) && !(event instanceof OrderItemCancelCompletedEvent)) {
            throw new IllegalArgumentException("주문 결제·취소 완료 이벤트가 아닙니다: " + event.getClass().getName());
        }
        return JSON.writeValueAsString(event);
    }

    /**
     * 원문을 주문 결제·취소 완료 이벤트로 읽는다. eventId 접두어로 타입을 정한다.
     *
     * @return {@link OrderItemPaidEvent} 또는 {@link OrderItemCancelCompletedEvent}. 읽지 못하면 빈 값
     */
    public static Optional<Object> readOrderEvent(String payload) {
        if (payload == null || payload.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode eventId = JSON.readTree(payload).get("eventId");
            String value = eventId == null || !eventId.isString() ? "" : eventId.asString();
            if (value.startsWith(OrderItemPaidEvent.EVENT_ID_PREFIX)) {
                return Optional.ofNullable(JSON.readValue(payload, OrderItemPaidEvent.class));
            }
            if (value.startsWith(OrderItemCancelCompletedEvent.EVENT_ID_PREFIX)) {
                return Optional.ofNullable(JSON.readValue(payload, OrderItemCancelCompletedEvent.class));
            }
            return Optional.empty();
        } catch (JacksonException exception) {
            return Optional.empty();
        }
    }
}
