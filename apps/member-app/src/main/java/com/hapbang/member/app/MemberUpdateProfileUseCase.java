package com.hapbang.member.app;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import com.hapbang.member.domain.User;
import com.hapbang.shared.member.event.UserModifiedEvent;

import lombok.RequiredArgsConstructor;

/** 내 정보 수정. 닉네임이 바뀌면 UserModifiedEvent 발행 */
@Service
@RequiredArgsConstructor
public class MemberUpdateProfileUseCase {

    private final MemberSupport memberSupport;
    private final ApplicationEventPublisher eventPublisher;

    /** nickname이 null이면 바꾸지 않는다 (PATCH) */
    public User update(Long userId, String nickname) {
        User user = memberSupport.findUser(userId);
        if (nickname != null) {
            user.changeNickname(nickname);
            // 역할 변경과 겹치면 409 (이벤트 발행 전)
            memberSupport.flush();
            eventPublisher.publishEvent(new UserModifiedEvent(user.toDto()));
        }
        return user;
    }
}
