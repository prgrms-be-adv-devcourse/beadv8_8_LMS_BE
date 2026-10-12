package com.hapbang.chat.app;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.chat.domain.ChatUser;
import com.hapbang.chat.out.ChatUserRepository;
import com.hapbang.shared.member.dto.UserDto;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 회원 정보를 채팅 모듈에 복제한다. 회원 이벤트 리스너({@code ChatUserEventListener})가 {@link ChatUserFacade}를 거쳐 호출한다.
 * <ul>
 *     <li>가입 이벤트는 없을 때만 생성한다. 가입 이벤트 처리가 실패해 나중에 재처리되어도,
 *     그 사이 반영된 변경 내용을 옛 가입 정보로 덮어쓰지 않는다(변경은 항상 가입 뒤에 일어난다).</li>
 *     <li>변경 이벤트는 있으면 갱신하고, 없으면 생성한다(가입 이벤트 처리가 실패한 경우 대비).</li>
 * </ul>
 * 이벤트 리스너 트랜잭션과 분리된 새 트랜잭션에서 저장한다. 동시 생성으로 PK가 충돌하면 이 트랜잭션만 롤백되고,
 * {@link ChatUserFacade}가 다시 시도한다. {@code saveAndFlush}로 충돌을 메서드 안에서 드러낸다.
 */
@Slf4j
@Service
@Transactional(propagation = Propagation.REQUIRES_NEW)
@RequiredArgsConstructor
public class ChatUserService {

    private final ChatUserRepository chatUserRepository;

    public void createIfAbsent(UserDto user) {
        if (chatUserRepository.existsById(user.id())) {
            log.debug("이미 복제된 채팅 회원이라 가입 이벤트를 무시한다 userId={}", user.id());
            return;
        }
        chatUserRepository.saveAndFlush(ChatUser.create(user));
    }

    public void createOrUpdate(UserDto user) {
        chatUserRepository.findById(user.id()).ifPresentOrElse(
                chatUser -> chatUser.syncFrom(user),
                () -> chatUserRepository.saveAndFlush(ChatUser.create(user))
        );
    }
}
