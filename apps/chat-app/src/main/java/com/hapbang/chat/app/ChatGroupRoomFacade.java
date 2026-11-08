package com.hapbang.chat.app;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

import com.hapbang.chat.app.ChatGroupRoomService.InvitePreparation;
import com.hapbang.chat.app.ChatRecruitSource.ChatApplicantPage;
import com.hapbang.chat.app.ChatRecruitSource.ChatRecruit;
import com.hapbang.chat.app.dto.ChatGroupCloseResponse;
import com.hapbang.chat.app.dto.ChatGroupInviteResponse;
import com.hapbang.chat.app.dto.ChatGroupRoomsResponse;
import com.hapbang.chat.app.dto.ChatInviteCandidatePageResponse;
import com.hapbang.chat.app.dto.ChatRoomCreateResult;
import com.hapbang.chat.app.dto.ChatRoomOpenResult;
import com.hapbang.chat.app.dto.ChatSubscriptionReason;
import com.hapbang.chat.app.exception.ChatErrorCode;
import com.hapbang.chat.app.exception.ChatException;
import com.hapbang.chat.domain.ChatGroupInviteOutcome;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Controller가 모집용 단체방 기능을 쓰는 진입점(설계 3장). 역할 검사({@code @PreAuthorize})는 여기서 한다(팀 규칙).
 * <ul>
 *     <li>출연 앱 호출({@link ChatRecruitSource})은 이 Facade가 저장 트랜잭션 밖에서 하고 결과를 서비스에 넘긴다(D62). 출연 앱이
 *     응답하지 않으면 503 {@code APPEARANCE_UNAVAILABLE}로 거절하고 아무것도 저장하지 않는다. 지원 여부를 추정하지 않는다.</li>
 *     <li>이 Facade는 트랜잭션을 열지 않는다. 서비스 호출마다 새 트랜잭션이다.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatGroupRoomFacade {

    private final ChatGroupRoomService chatGroupRoomService;
    private final ChatRecruitSource chatRecruitSource;
    private final ChatRoomTopicService chatRoomTopicService;
    private final ChatGroupProperties properties;

    /**
     * 모집글의 단체방을 만든다. 현재 방(사용 중·일시 중지)이 있으면 출연 앱을 부르지 않고 그 방을 돌려준다. 없으면 출연 앱에서 호스트·
     * 모집 중 여부·제목을 확인해 만든다. 같은 모집글에 동시에 만들면 한쪽은 부분 유일 인덱스에 걸려 롤백되고, 다시 시도하면 먼저 만든
     * 방을 돌려준다.
     */
    @PreAuthorize(ChatRoles.CHAT)
    public ChatRoomCreateResult createRoom(Long requesterId, Long recruitId) {
        Optional<Long> current = chatGroupRoomService.findCurrentRoomOf(requesterId, recruitId);
        if (current.isPresent()) {
            return new ChatRoomCreateResult(chatGroupRoomService.toRoomResponse(current.get(), requesterId), false);
        }
        ChatRecruit recruit = readRecruit(recruitId);
        ChatRoomOpenResult opened = createWithRetry(requesterId, recruit);
        return new ChatRoomCreateResult(chatGroupRoomService.toRoomResponse(opened.chatRoomId(), requesterId),
                opened.created());
    }

    @PreAuthorize(ChatRoles.CHAT)
    public ChatGroupRoomsResponse getRooms(Long userId, Long recruitId) {
        return chatGroupRoomService.getRooms(userId, recruitId);
    }

    /**
     * 초대 후보를 찾는다. 출연 앱에서 모집글 제목과 지원자 전부(지원 상태 무관)를 읽은 뒤 chat 회원 정보로 거른다.
     */
    @PreAuthorize(ChatRoles.CHAT)
    public ChatInviteCandidatePageResponse getInviteCandidates(Long chatRoomId, Long requesterId, String keyword,
            Long cursor, int size) {
        Long recruitId = chatGroupRoomService.checkManageable(chatRoomId, requesterId);
        ChatRecruit recruit = readRecruit(recruitId);
        List<Long> applicants = readAllApplicants(recruitId);
        return chatGroupRoomService.findInviteCandidates(chatRoomId, requesterId, recruit.title(), applicants,
                keyword, cursor, size);
    }

    /**
     * 초대한다. 중복 ID를 빼고 요청 순서를 지킨다. 같은 요청 ID를 보존 기간 안에 다시 보내면 출연 앱을 부르지 않고 저장한 결과를
     * 돌려준다.
     */
    @PreAuthorize(ChatRoles.CHAT)
    public ChatGroupInviteResponse invite(Long chatRoomId, Long requesterId, UUID requestId, List<Long> userIds) {
        List<Long> distinctUserIds = distinctUserIds(requestId, userIds);
        InvitePreparation preparation = chatGroupRoomService.prepareInvite(chatRoomId, requesterId, requestId);
        if (preparation.retained() != null) {
            return new ChatGroupInviteResponse(chatRoomId, requestId, preparation.retained());
        }
        Set<Long> applied = readApplied(preparation.recruitId(), distinctUserIds);
        List<ChatGroupInviteOutcome> outcomes = chatGroupRoomService.invite(chatRoomId, requesterId, requestId,
                distinctUserIds, applied);
        return new ChatGroupInviteResponse(chatRoomId, requestId, outcomes);
    }

    /**
     * 내보낸다. 내보낸 것이 커밋된 뒤 그 회원의 그 방 로컬 구독 중 끝난 세대 이하만 회수한다(나가기와 같다).
     */
    @PreAuthorize(ChatRoles.CHAT)
    public void kick(Long chatRoomId, Long requesterId, Long targetUserId, int participationVersion) {
        chatGroupRoomService.kick(chatRoomId, requesterId, targetUserId, participationVersion)
                .ifPresent(ended -> chatRoomTopicService.revokeMember(chatRoomId, targetUserId, ended,
                        ChatSubscriptionReason.REMOVED));
    }

    /**
     * 폐쇄한다. 호스트에게는 나가기 대신이므로 나가기처럼 역할 검사를 하지 않는다(강등·정지된 호스트도 폐쇄할 수 있다). 참여자는 남고
     * 방은 조회 만료 전까지 볼 수 있으므로 구독을 회수하지 않는다.
     */
    public ChatGroupCloseResponse close(Long chatRoomId, Long requesterId) {
        return chatGroupRoomService.close(chatRoomId, requesterId);
    }

    private ChatRoomOpenResult createWithRetry(Long requesterId, ChatRecruit recruit) {
        try {
            return chatGroupRoomService.createRoom(requesterId, recruit);
        } catch (DataIntegrityViolationException firstConflict) {
            try {
                return chatGroupRoomService.createRoom(requesterId, recruit);
            } catch (DataIntegrityViolationException secondConflict) {
                throw new ChatException(ChatErrorCode.CHAT_ROOM_BUSY);
            }
        }
    }

    /** 요청 검증과 중복 제거. 한 요청의 인원 상한은 중복을 뺀 수로 센다 */
    private List<Long> distinctUserIds(UUID requestId, List<Long> userIds) {
        if (requestId == null || userIds == null || userIds.isEmpty()) {
            throw new ChatException(ChatErrorCode.INVALID_REQUEST, "요청 ID와 초대할 회원이 필요합니다.");
        }
        if (userIds.stream().anyMatch(userId -> userId == null || userId <= 0)) {
            throw new ChatException(ChatErrorCode.INVALID_REQUEST, "초대할 회원 ID는 양수여야 합니다.");
        }
        List<Long> distinct = List.copyOf(new LinkedHashSet<>(userIds));
        if (distinct.size() > properties.inviteMaxUsers()) {
            throw new ChatException(ChatErrorCode.INVALID_REQUEST,
                    "한 번에 " + properties.inviteMaxUsers() + "명까지 초대할 수 있습니다.");
        }
        return distinct;
    }

    private ChatRecruit readRecruit(Long recruitId) {
        try {
            return chatRecruitSource.getRecruit(recruitId);
        } catch (ChatRecruitSourceException e) {
            throw translate(e);
        }
    }

    /** 지원자 전부를 키셋으로 읽는다. 다음 페이지 값이 앞으로 가지 않으면 읽을 수 없는 응답으로 본다 */
    private List<Long> readAllApplicants(Long recruitId) {
        List<Long> applicants = new ArrayList<>();
        Long after = null;
        try {
            while (true) {
                ChatApplicantPage page = chatRecruitSource.getApplicants(recruitId, after,
                        ChatRecruitSource.MAX_APPLICANT_PAGE_SIZE);
                applicants.addAll(page.userIds());
                Long next = page.nextAfterUserId();
                if (next == null) {
                    return applicants;
                }
                if (after != null && next <= after) {
                    log.warn("출연 지원자 목록의 다음 페이지 값이 앞으로 가지 않음 recruitId={}, after={}, next={}", recruitId,
                            after, next);
                    throw new ChatException(ChatErrorCode.APPEARANCE_UNAVAILABLE);
                }
                after = next;
            }
        } catch (ChatRecruitSourceException e) {
            throw translate(e);
        }
    }

    private Set<Long> readApplied(Long recruitId, List<Long> userIds) {
        Set<Long> applied = new HashSet<>();
        try {
            for (int from = 0; from < userIds.size(); from += ChatRecruitSource.MAX_APPLIED_CHECK_SIZE) {
                List<Long> chunk = userIds.subList(from,
                        Math.min(userIds.size(), from + ChatRecruitSource.MAX_APPLIED_CHECK_SIZE));
                applied.addAll(chatRecruitSource.findApplied(recruitId, chunk));
            }
        } catch (ChatRecruitSourceException e) {
            throw translate(e);
        }
        return applied;
    }

    /**
     * 출연 호출 실패를 채팅 오류로 바꾼다. 모집글이 없으면 404, 그 밖(거절·호출 불가)은 지원 여부를 알 수 없으므로 503이다.
     */
    private static ChatException translate(ChatRecruitSourceException e) {
        if (e.getReason() == ChatRecruitSourceException.Reason.NOT_FOUND) {
            return new ChatException(ChatErrorCode.RECRUIT_NOT_FOUND);
        }
        log.warn("출연 내부 API 호출 실패 reason={}, status={}, code={}, message={}", e.getReason(), e.getStatus(),
                e.getCode(), e.getMessage());
        return new ChatException(ChatErrorCode.APPEARANCE_UNAVAILABLE);
    }
}
