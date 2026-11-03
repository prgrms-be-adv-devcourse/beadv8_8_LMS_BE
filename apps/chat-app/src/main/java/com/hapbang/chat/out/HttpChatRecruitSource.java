package com.hapbang.chat.out;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import com.hapbang.chat.app.ChatRecruitSource;
import com.hapbang.chat.app.ChatRecruitSourceException;
import com.hapbang.chat.app.ChatRecruitSourceException.Reason;
import com.hapbang.chat.out.appearance.ChatAppearanceInternalApi;

import lombok.RequiredArgsConstructor;

/**
 * 출연 내부 HTTP API로 모집글·지원 이력을 읽는다. chat-app 전용 HTTP Interface({@link ChatAppearanceInternalApi})로 부른다.
 * 프록시는 {@code hapbang.clients.appearance.base-url}이 있을 때만 만들어지므로, 없으면 호출 불가로 본다.
 * <p>
 * 실패는 모두 {@link ChatRecruitSourceException}이다. 오류 상태는 프록시 설정이 바꾸고, 여기서는 연결 실패·시간 초과와 성공 상태인데
 * 쓸 수 없는 응답(본문·{@code data} 없음, 깨진 JSON, 필수 값 없음)을 호출 불가로 바꾼다.
 */
@Component
@RequiredArgsConstructor
public class HttpChatRecruitSource implements ChatRecruitSource {

    /** 단체방 제목 컬럼 길이 */
    private static final int MAX_TITLE_LENGTH = 255;

    private final ObjectProvider<ChatAppearanceInternalApi> appearanceInternalApi;

    @Override
    public ChatRecruit getRecruit(Long recruitId) {
        ChatAppearanceInternalApi.Recruit recruit = call(() -> api().getRecruit(recruitId),
                ChatAppearanceInternalApi.RecruitEnvelope::data);
        if (recruit.hostId() == null || recruit.status() == null || recruit.title() == null
                || recruit.title().isBlank() || recruit.title().length() > MAX_TITLE_LENGTH) {
            throw unreadable("모집글 응답에 필요한 값이 없거나 형식이 맞지 않습니다.");
        }
        return new ChatRecruit(recruitId, recruit.hostId(), recruit.title(), recruit.status());
    }

    @Override
    public ChatApplicantPage getApplicants(Long recruitId, Long afterUserId, int size) {
        ChatAppearanceInternalApi.Applicants applicants = call(
                () -> api().getApplicants(recruitId, afterUserId, size),
                ChatAppearanceInternalApi.ApplicantsEnvelope::data);
        if (applicants.userIds() == null || applicants.userIds().stream().anyMatch(Objects::isNull)) {
            throw unreadable("지원자 목록 응답에 회원 ID가 없습니다.");
        }
        return new ChatApplicantPage(List.copyOf(applicants.userIds()), applicants.nextAfterUserId());
    }

    @Override
    public Set<Long> findApplied(Long recruitId, Collection<Long> userIds) {
        ChatAppearanceInternalApi.Applied applied = call(() -> api().findApplied(recruitId, List.copyOf(userIds)),
                ChatAppearanceInternalApi.AppliedEnvelope::data);
        if (applied.appliedUserIds() == null || applied.appliedUserIds().stream().anyMatch(Objects::isNull)) {
            throw unreadable("지원 이력 확인 응답에 회원 ID가 없습니다.");
        }
        return Set.copyOf(applied.appliedUserIds());
    }

    private ChatAppearanceInternalApi api() {
        ChatAppearanceInternalApi api = appearanceInternalApi.getIfAvailable();
        if (api == null) {
            throw new ChatRecruitSourceException(Reason.UNAVAILABLE, 0, null,
                    "출연 내부 API가 설정되지 않았습니다(hapbang.clients.appearance.base-url 미설정).", null);
        }
        return api;
    }

    /** 부르고 성공 봉투에서 {@code data}를 꺼낸다. 오류 상태는 프록시가 먼저 바꾼다 */
    private static <E, D> D call(Supplier<ResponseEntity<E>> request, Function<E, D> data) {
        ResponseEntity<E> response;
        try {
            response = request.get();
        } catch (ChatRecruitSourceException e) {
            throw e;
        } catch (ResourceAccessException e) {
            throw new ChatRecruitSourceException(Reason.UNAVAILABLE, 0, null,
                    "출연 내부 API에 연결하지 못했거나 응답 시간이 지났습니다.", e);
        } catch (RestClientException e) {
            // 오류 상태는 프록시가 먼저 바꾸므로 여기 오는 것은 성공 상태의 읽을 수 없는 본문이다
            throw new ChatRecruitSourceException(Reason.UNAVAILABLE, 0, null, "출연 내부 API 응답을 읽지 못했습니다.", e);
        }
        E body = response.getBody();
        D value = body == null ? null : data.apply(body);
        if (value == null) {
            throw new ChatRecruitSourceException(Reason.UNAVAILABLE, response.getStatusCode().value(), null,
                    "출연 내부 API 응답에 data가 없습니다.", null);
        }
        return value;
    }

    private static ChatRecruitSourceException unreadable(String message) {
        return new ChatRecruitSourceException(Reason.UNAVAILABLE, 0, null, message, null);
    }
}
