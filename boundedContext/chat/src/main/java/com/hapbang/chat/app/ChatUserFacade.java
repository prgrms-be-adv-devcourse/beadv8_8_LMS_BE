package com.hapbang.chat.app;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import com.hapbang.shared.member.dto.UserDto;

import lombok.RequiredArgsConstructor;

/**
 * 회원 이벤트 리스너가 회원 복제 기능을 쓰는 진입점. in 계층은 Service를 직접 쓰지 않고 이 Facade를 통한다.
 * <p>
 * 가입·변경 이벤트가 동시에 처리되면 둘 다 "없음"을 보고 생성하다가 PK가 충돌할 수 있다.
 * 충돌한 쪽은 자기 트랜잭션만 롤백되므로, 한 번 더 시도하면 이미 있는 회원으로 처리된다(가입은 무시, 변경은 갱신).
 */
@Component
@RequiredArgsConstructor
public class ChatUserFacade {

    private final ChatUserService chatUserService;

    public void syncJoined(UserDto user) {
        retryOnceOnConflict(() -> chatUserService.createIfAbsent(user));
    }

    public void syncModified(UserDto user) {
        retryOnceOnConflict(() -> chatUserService.createOrUpdate(user));
    }

    private void retryOnceOnConflict(Runnable sync) {
        try {
            sync.run();
        } catch (DataIntegrityViolationException conflict) {
            sync.run();
        }
    }
}
