package com.hapbang.auxiliary.chatbot.app;

import java.util.List;

import com.hapbang.auxiliary.chatbot.domain.MemberEventType;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.shared.member.event.UserJoinedEvent;
import com.hapbang.shared.member.event.UserMaskedEvent;
import com.hapbang.shared.member.event.UserModifiedEvent;

import tools.jackson.databind.json.JsonMapper;

/**
 * 실패 기록에 남기는 회원 이벤트 원문(JSON) (#976). 다시 처리할 때 같은 이벤트로 되읽는다.
 * <p>
 * 닉네임도 남긴다. 사본 해시({@code CopyMapper.member})가 닉네임을 포함해, 빼고 다시 처리하면 다음 매시 싱크가 원본과 다르다고 센다.
 * 회원이 마스킹되면 {@link #withoutPersonal}로 닉네임·분야·자기소개를 비운다.
 */
final class MemberEventPayload {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private MemberEventPayload() {
    }

    static String write(Object event) {
        return JSON.writeValueAsString(event);
    }

    static Object read(MemberEventType type, String payload) {
        return JSON.readValue(payload, eventClass(type));
    }

    /** 닉네임·분야·자기소개를 비운 원문. 가입·변경이 아니면 그대로 */
    static String withoutPersonal(MemberEventType type, String payload) {
        Object event = read(type, payload);
        return switch (event) {
            case UserJoinedEvent joined -> write(new UserJoinedEvent(withoutPersonal(joined.user())));
            case UserModifiedEvent modified -> write(new UserModifiedEvent(withoutPersonal(modified.user())));
            default -> payload;
        };
    }

    private static UserDto withoutPersonal(UserDto user) {
        return new UserDto(user.id(), null, user.role(), user.status(), user.version(), List.of(), null);
    }

    private static Class<?> eventClass(MemberEventType type) {
        return switch (type) {
            case JOINED -> UserJoinedEvent.class;
            case MODIFIED -> UserModifiedEvent.class;
            case MASKED -> UserMaskedEvent.class;
            case UNREADABLE -> throw new IllegalArgumentException("읽지 못한 메시지는 원문이 없습니다.");
        };
    }
}
