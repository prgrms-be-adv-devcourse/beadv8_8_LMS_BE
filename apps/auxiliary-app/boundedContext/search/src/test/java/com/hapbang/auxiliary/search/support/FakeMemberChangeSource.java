package com.hapbang.auxiliary.search.support;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.hapbang.auxiliary.search.out.MemberChangeSource;
import com.hapbang.shared.member.dto.MemberChangePageDto;
import com.hapbang.shared.member.dto.UserDto;

/**
 * 회원 앱 대신 변경분을 주는 가짜(#772). #769 계약대로 (updatedAt, 회원 ID)가 커서보다 뒤인 회원을 그 순서로 자르고, 항목이 있으면 마지막 페이지에도
 * 다음 커서를 준다. {@link #failOnCall}로 회차 중간 장애를 흉내 내고, 받은 커서를 {@link #requests()}로 본다.
 */
public class FakeMemberChangeSource implements MemberChangeSource {

    private static final Comparator<Change> ORDER = Comparator.comparing(Change::updatedAt)
            .thenComparing(change -> change.user().id());

    private final List<Change> changes = new ArrayList<>();
    private final List<Request> requests = new ArrayList<>();
    private int pageSize = Integer.MAX_VALUE;
    private int failOnCall;

    /** 이 시각에 바뀐 회원 */
    public FakeMemberChangeSource change(LocalDateTime updatedAt, UserDto user) {
        changes.add(new Change(updatedAt, user));
        changes.sort(ORDER);
        return this;
    }

    /** 페이지 크기를 실제 상한보다 작게 해 여러 페이지를 흉내 낸다 */
    public FakeMemberChangeSource pageSize(int pageSize) {
        this.pageSize = pageSize;
        return this;
    }

    /** n번째 호출(1부터)에서 실패한다. 0이면 실패하지 않는다 */
    public FakeMemberChangeSource failOnCall(int call) {
        this.failOnCall = call;
        return this;
    }

    /** 지금까지 받은 요청의 커서 */
    public List<Request> requests() {
        return List.copyOf(requests);
    }

    @Override
    public MemberChangePageDto changes(LocalDateTime afterUpdatedAt, long afterId, int size) {
        requests.add(new Request(afterUpdatedAt, afterId));
        if (requests.size() == failOnCall) {
            throw new IllegalStateException("회원 앱 장애");
        }
        List<Change> after = changes.stream()
                .filter(change -> change.updatedAt().isAfter(afterUpdatedAt)
                        || change.updatedAt().isEqual(afterUpdatedAt) && change.user().id() > afterId)
                .toList();
        List<Change> page = after.stream().limit(Math.min(size, pageSize)).toList();
        if (page.isEmpty()) {
            return new MemberChangePageDto(List.of(), null, null, false);
        }
        Change last = page.getLast();
        return new MemberChangePageDto(page.stream().map(Change::user).toList(), last.updatedAt(), last.user().id(),
                after.size() > page.size());
    }

    private record Change(LocalDateTime updatedAt, UserDto user) {
    }

    /** 받은 커서 */
    public record Request(LocalDateTime afterUpdatedAt, long afterId) {
    }
}
