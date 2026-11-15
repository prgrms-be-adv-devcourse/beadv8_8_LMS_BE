package com.hapbang.auxiliary.search.support;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.hapbang.auxiliary.search.out.MemberListSource;
import com.hapbang.shared.member.dto.MemberPageDto;
import com.hapbang.shared.member.dto.UserDto;

/**
 * 회원 앱 대신 회원 목록을 주는 가짜(#773). 회원 ID 오름차순으로 페이지 크기대로 잘라 키셋처럼 준다. {@link #onPage}로 페이지를 준 직후에
 * 할 일(적재 중에 회원이 바뀌는 상황, 다른 pod와 겹치기)을 끼운다.
 */
public class FakeMemberListSource implements MemberListSource {

    private final List<UserDto> users = new ArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();
    private int pageSize = Integer.MAX_VALUE;
    private Runnable onPage = () -> {
    };

    public FakeMemberListSource user(UserDto user) {
        users.add(user);
        users.sort(Comparator.comparing(UserDto::id));
        return this;
    }

    /** 페이지 크기를 실제 상한보다 작게 해 여러 페이지를 흉내 낸다 */
    public FakeMemberListSource pageSize(int pageSize) {
        this.pageSize = pageSize;
        return this;
    }

    /** 페이지를 줄 때마다(돌려주기 직전에) 할 일 */
    public FakeMemberListSource onPage(Runnable onPage) {
        this.onPage = onPage;
        return this;
    }

    /** 지금까지 받은 페이지 요청 수 */
    public int calls() {
        return calls.get();
    }

    @Override
    public MemberPageDto members(Long afterId, int size) {
        calls.incrementAndGet();
        List<UserDto> page = users.stream()
                .filter(user -> afterId == null || user.id() > afterId)
                .limit(Math.min(size, pageSize))
                .toList();
        onPage.run();
        boolean last = page.isEmpty() || page.getLast().id().equals(users.getLast().id());
        return new MemberPageDto(page, last ? null : page.getLast().id());
    }
}
