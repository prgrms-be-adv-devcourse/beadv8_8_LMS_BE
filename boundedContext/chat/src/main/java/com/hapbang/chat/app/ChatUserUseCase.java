package com.hapbang.chat.app;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.chat.app.exception.ChatErrorCode;
import com.hapbang.chat.app.exception.ChatException;
import com.hapbang.chat.domain.ChatUser;
import com.hapbang.chat.out.ChatUserRepository;
import com.hapbang.shared.member.dto.UserDto;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 회원 정보를 채팅 모듈에 복제한다. 회원 이벤트 리스너({@code ChatUserEventListener})가 {@link ChatUserFacade}를 거쳐 호출한다.
 * <ul>
 *     <li>채팅 회원은 가입 이벤트로만 생성한다. 이미 있으면 무시한다(같은 가입 이벤트의 재처리).</li>
 *     <li>변경 이벤트는 있는 회원만 갱신한다. 없으면(가입 이벤트가 아직 처리되지 않음) 예외를 던져 처리 실패로 남기고,
 *     가입 이벤트가 처리된 뒤 다시 처리될 때 반영한다.</li>
 * </ul>
 * 생성하는 쪽이 가입 이벤트 하나라, 가입·변경이 동시에 생성하다 충돌하는 일이 없다.
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class ChatUserUseCase {

    private final ChatUserRepository chatUserRepository;

    public void createIfAbsent(UserDto user) {
        validate(user);
        if (chatUserRepository.existsById(user.id())) {
            log.debug("이미 복제된 채팅 회원이라 가입 이벤트를 무시한다 userId={}", user.id());
            return;
        }
        chatUserRepository.save(ChatUser.create(user));
    }

    /**
     * 복제 정보를 갱신한다. 회원 변경 이벤트는 프로필 수정에도 오므로, 이번 변경으로 채팅할 수 없게 됐는지
     * (채팅 가능 → 불가: 정지·탈퇴·일반 유저로 강등)를 갱신 전후로 비교해 돌려준다.
     *
     * @return 이번 변경으로 채팅할 수 없게 됐으면 true. 프로필 수정이나 채팅 가능 여부가 그대로인 변경은 false
     */
    public boolean update(UserDto user) {
        validate(user);
        ChatUser chatUser = chatUserRepository.findById(user.id())
                .orElseThrow(() -> new ChatException(ChatErrorCode.CHAT_USER_NOT_FOUND));
        boolean couldChat = chatUser.canChat();
        chatUser.syncFrom(user);
        return couldChat && !chatUser.canChat();
    }

    private void validate(UserDto user) {
        if (!ChatUser.isReplicable(user)) {
            throw new ChatException(ChatErrorCode.INVALID_REQUEST);
        }
    }
}
