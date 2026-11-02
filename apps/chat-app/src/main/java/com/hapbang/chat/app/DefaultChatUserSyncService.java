package com.hapbang.chat.app;

import java.time.Instant;
import java.util.Objects;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.chat.app.dto.ChatUserSyncResult;
import com.hapbang.chat.app.exception.ChatErrorCode;
import com.hapbang.chat.app.exception.ChatException;
import com.hapbang.chat.domain.ChatControlReason;
import com.hapbang.shared.member.dto.UserDto;

import lombok.extern.slf4j.Slf4j;

/**
 * {@link ChatUserSyncService}의 업무 구현. 회원 반영({@link ChatUserUseCase})과 연결 제어 기록({@link ChatConnectionControl})을
 * 하나의 로컬 트랜잭션으로 묶는다. 커밋되면 둘 다 남고, 롤백되면 둘 다 남지 않는다.
 * <p>
 * 연결 재검사 작업은 <b>기존 복제본을 이번에 갱신했고 갱신 뒤 상태가 채팅 불가</b>일 때만 남긴다(계약 control-recording.md).
 * 생성(가입·백필 생성)과 옛 버전이라 건너뛴 경우에는 남기지 않는다. 채팅 가능 → 불가 전이만으로 판단하지 않는다.
 */
@Slf4j
@Service
public class DefaultChatUserSyncService implements ChatUserSyncService {

    private final ChatUserUseCase chatUserUseCase;
    private final ChatConnectionControl chatConnectionControl;
    /** 실시간 전달을 끈 실행에는 없다(Redis) */
    private final ObjectProvider<ChatLogoutMarkers> chatLogoutMarkers;
    private final TransactionTemplate backfillTransaction;
    private final TransactionTemplate logoutTransaction;
    private final int backfillMaxAttempts;

    public DefaultChatUserSyncService(ChatUserUseCase chatUserUseCase, ChatConnectionControl chatConnectionControl,
            ObjectProvider<ChatLogoutMarkers> chatLogoutMarkers, PlatformTransactionManager transactionManager,
            ChatUserBackfillProperties backfillProperties) {
        this.chatUserUseCase = chatUserUseCase;
        this.chatConnectionControl = chatConnectionControl;
        this.chatLogoutMarkers = chatLogoutMarkers;
        this.logoutTransaction = new TransactionTemplate(transactionManager);
        // 백필은 회원마다 독립된 새 트랜잭션: 충돌로 실패한 시도는 그 트랜잭션만 롤백되고, 다음 시도는 새 영속성 컨텍스트에서 다시 읽는다
        this.backfillTransaction = new TransactionTemplate(transactionManager);
        this.backfillTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.backfillMaxAttempts = backfillProperties.conflictMaxAttempts();
    }

    @Override
    @Transactional
    public ChatUserSyncResult syncJoined(UserDto user) {
        return chatUserUseCase.createIfAbsent(user);
    }

    @Override
    @Transactional
    public ChatUserSyncResult syncModified(UserDto user) {
        ChatUserSyncResult result = chatUserUseCase.update(user);
        requestRecheckIfCannotChat(result, ChatControlReason.MEMBER_MODIFIED);
        return result;
    }

    /**
     * 가입·변경 이벤트와 동시에 같은 회원을 만들거나 고치면 PK 유일 제약·낙관적 락 충돌이 난다. 그 시도는 롤백하고
     * 새 트랜잭션에서 다시 읽어 생성 여부·버전 비교부터 다시 한다. 정해진 횟수 안에 끝나지 않으면 마지막 충돌을 던진다.
     */
    @Override
    public ChatUserSyncResult syncBackfill(UserDto user) {
        RuntimeException lastConflict = null;
        for (int attempt = 1; attempt <= backfillMaxAttempts; attempt++) {
            try {
                return Objects.requireNonNull(backfillTransaction.execute(status -> {
                    ChatUserSyncResult result = chatUserUseCase.applySnapshot(user);
                    requestRecheckIfCannotChat(result, ChatControlReason.MEMBER_BACKFILL);
                    return result;
                }));
            } catch (DataIntegrityViolationException | ObjectOptimisticLockingFailureException conflict) {
                lastConflict = conflict;
                log.info("채팅 회원 백필 동시 반영 충돌, 다시 시도 userId={}, attempt={}", user.id(), attempt);
            }
        }
        throw Objects.requireNonNull(lastConflict);
    }

    /**
     * 연결 종료 작업을 한 트랜잭션에 남기고, 커밋한 뒤 트랜잭션 밖에서 로그아웃 표식을 남긴다(하트비트의 두 번째 확인, 설계 문서 6장).
     * 표식 시각은 처리를 시작한 시각이다. 표식을 남기지 못해도 실패로 보지 않는다(제어 경로와 만료가 남는다). 다시 처리하면 같은
     * 일을 반복할 뿐이다.
     */
    @Override
    public void syncLoggedOut(Long userId) {
        if (userId == null || userId < 1) {
            throw new ChatException(ChatErrorCode.INVALID_REQUEST, "로그아웃한 회원 ID가 올바르지 않습니다.");
        }
        Instant processedAt = Instant.now();
        logoutTransaction.executeWithoutResult(status -> chatConnectionControl.requestLogout(userId));
        ChatLogoutMarkers markers = chatLogoutMarkers.getIfAvailable();
        if (markers == null) {
            return;
        }
        try {
            markers.markLoggedOut(userId, processedAt);
        } catch (RuntimeException exception) {
            log.warn("로그아웃 표식을 남기지 못함. 제어 경로와 연결 만료가 닫는다 userId={}", userId, exception);
        }
    }

    private void requestRecheckIfCannotChat(ChatUserSyncResult result, ChatControlReason reason) {
        if (result.updated() && !result.canChat()) {
            chatConnectionControl.requestRecheck(result.userId(), result.memberVersion(), reason);
        }
    }
}
