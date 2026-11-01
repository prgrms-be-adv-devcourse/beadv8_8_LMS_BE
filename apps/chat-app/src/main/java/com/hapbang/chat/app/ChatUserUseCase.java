package com.hapbang.chat.app;

import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.chat.app.dto.ChatUserSyncResult;
import com.hapbang.chat.app.dto.ChatUserSyncResult.Outcome;
import com.hapbang.chat.app.exception.ChatErrorCode;
import com.hapbang.chat.app.exception.ChatException;
import com.hapbang.chat.domain.ChatUser;
import com.hapbang.chat.out.ChatUserRepository;
import com.hapbang.shared.member.dto.UserDto;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 회원 정보를 채팅 모듈에 복제하는 규칙. 트랜잭션 경계와 연결 제어 기록은 {@link DefaultChatUserSyncService}가 정하고,
 * 이 클래스는 그 트랜잭션에 합류해 복제본만 다룬다.
 * <ul>
 *     <li>채팅 회원은 가입 이벤트로만 생성한다. 이미 있으면 무시한다(같은 가입 이벤트의 재처리).</li>
 *     <li>변경 이벤트는 있는 회원만 갱신한다. 없으면(가입 이벤트가 아직 처리되지 않음) 예외를 던져 처리 실패로 남기고,
 *     만들지 않는다.</li>
 *     <li>백필은 회원 원본 스냅샷으로 없으면 만들고 있으면 변경과 같은 버전 규칙으로 갱신한다(설계 문서 5.4).
 *     가입·변경 규칙을 일반 upsert로 바꾸지 않도록 백필 전용 메서드로 둔다.</li>
 * </ul>
 * 갱신은 {@code ReplicaUser.syncFrom}의 버전 규칙을 따른다: 이미 반영한 회원 버전보다 크지 않으면 무시한다.
 * 같은 회원을 동시에 만들거나 고치면 PK 유일 제약·복제본 낙관적 락으로 늦은 쪽이 실패하고, 호출한 쪽이 다시 처리한다.
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class ChatUserUseCase {

    private final ChatUserRepository chatUserRepository;

    public ChatUserSyncResult createIfAbsent(UserDto user) {
        validate(user);
        return chatUserRepository.findById(user.id())
                .map(existing -> {
                    log.debug("이미 복제된 채팅 회원이라 가입 이벤트를 무시한다 userId={}", user.id());
                    return result(existing, Outcome.SKIPPED_EXISTING);
                })
                .orElseGet(() -> result(chatUserRepository.save(ChatUser.create(user)), Outcome.CREATED));
    }

    /**
     * 기존 복제본을 갱신한다. 회원 변경 이벤트는 프로필 수정에도 오므로, 결과의 {@code canChat}은 갱신 뒤 현재 자격이다.
     *
     * @throws ChatException 복제본이 없으면 {@code CHAT_USER_NOT_FOUND}
     */
    public ChatUserSyncResult update(UserDto user) {
        validate(user);
        ChatUser chatUser = chatUserRepository.findById(user.id())
                .orElseThrow(() -> new ChatException(ChatErrorCode.CHAT_USER_NOT_FOUND));
        return apply(chatUser, user);
    }

    /**
     * 백필 스냅샷을 반영한다. 없으면 만들고, 있으면 {@link #update}와 같은 버전 규칙으로 갱신한다.
     * 생성 충돌(가입 이벤트와 동시 생성)이 이 트랜잭션 안에서 드러나도록 바로 flush한다.
     */
    public ChatUserSyncResult applySnapshot(UserDto user) {
        validate(user);
        return chatUserRepository.findById(user.id())
                .map(existing -> apply(existing, user))
                .orElseGet(() -> result(chatUserRepository.saveAndFlush(ChatUser.create(user)), Outcome.CREATED));
    }

    // 버전 규칙은 ReplicaUser.syncFrom이 판단한다. 반영 전 버전이 없으면 언제나 반영되고, 있으면 반영될 때만 버전이 바뀐다
    private ChatUserSyncResult apply(ChatUser chatUser, UserDto user) {
        Long before = chatUser.getMemberVersion();
        chatUser.syncFrom(user);
        boolean applied = before == null || !Objects.equals(before, chatUser.getMemberVersion());
        if (!applied) {
            log.debug("이미 반영한 회원 버전이라 무시한다 userId={}, memberVersion={}, incoming={}", user.id(), before,
                    user.version());
            return result(chatUser, Outcome.SKIPPED_STALE);
        }
        // 동시 갱신 충돌(낙관적 락)을 연결 제어 기록 전에 드러낸다
        chatUserRepository.flush();
        return result(chatUser, Outcome.UPDATED);
    }

    private static ChatUserSyncResult result(ChatUser chatUser, Outcome outcome) {
        return new ChatUserSyncResult(chatUser.getId(), outcome, chatUser.getMemberVersion(), chatUser.canChat());
    }

    private void validate(UserDto user) {
        if (!ChatUser.isReplicable(user)) {
            throw new ChatException(ChatErrorCode.INVALID_REQUEST);
        }
    }
}
