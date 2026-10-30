package com.hapbang.chat.out;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import com.hapbang.chat.app.ChatMemberSnapshotSource;
import com.hapbang.chat.app.ChatMemberSourceException;
import com.hapbang.chat.app.ChatMemberSourceException.Reason;
import com.hapbang.chat.out.member.ChatMemberInternalApi;
import com.hapbang.shared.member.dto.MemberPageDto;

import lombok.RequiredArgsConstructor;

/**
 * 회원 내부 HTTP API로 회원 원본 스냅샷을 읽는다. chat-app 전용 HTTP Interface({@link ChatMemberInternalApi})로 부른다.
 * 프록시는 {@code hapbang.clients.member.base-url}이 있을 때만 만들어지므로, 없으면 설정되지 않은 것으로 본다.
 * <p>
 * 실패는 모두 {@link ChatMemberSourceException}이다. 오류 상태(4xx 거절, 5xx 호출 불가)는 프록시 설정이 바꾸고, 여기서는
 * 연결 실패·시간 초과와 성공 상태인데 쓸 수 없는 응답(본문 없음·{@code data} 없음·깨진 JSON·계약에 없는 값)을 호출 불가로 바꾼다.
 */
@Component
@RequiredArgsConstructor
public class HttpChatMemberSnapshotSource implements ChatMemberSnapshotSource {

    private final ObjectProvider<ChatMemberInternalApi> memberInternalApi;

    @Override
    public boolean isConfigured() {
        return memberInternalApi.getIfAvailable() != null;
    }

    @Override
    public MemberPageDto readPage(long afterId, int size) {
        ChatMemberInternalApi api = memberInternalApi.getIfAvailable();
        if (api == null) {
            throw new IllegalStateException("회원 내부 API가 설정되지 않았습니다(hapbang.clients.member.base-url 미설정).");
        }
        return read(api, afterId, size);
    }

    /** 설정한 프록시로 한 페이지를 읽는다. 어댑터 테스트가 컨텍스트 없이 부른다 */
    static MemberPageDto read(ChatMemberInternalApi api, Long afterId, int size) {
        ResponseEntity<ChatMemberInternalApi.Envelope> response;
        try {
            response = api.getMembers(size, afterId);
        } catch (ChatMemberSourceException e) {
            throw e;
        } catch (ResourceAccessException e) {
            throw new ChatMemberSourceException(Reason.UNAVAILABLE, 0, null,
                    "회원 내부 API에 연결하지 못했거나 응답 시간이 지났습니다.", e);
        } catch (RestClientException e) {
            // 오류 상태는 프록시가 먼저 바꾸므로 여기 오는 것은 성공 상태의 읽을 수 없는 본문이다(상태 값은 남지 않는다)
            throw new ChatMemberSourceException(Reason.UNAVAILABLE, 0, null, "회원 내부 API 응답을 읽지 못했습니다.", e);
        }
        ChatMemberInternalApi.Envelope body = response.getBody();
        if (body == null || body.data() == null) {
            throw new ChatMemberSourceException(Reason.UNAVAILABLE, response.getStatusCode().value(), null,
                    "회원 내부 API 응답에 data가 없습니다.", null);
        }
        return body.data();
    }
}
