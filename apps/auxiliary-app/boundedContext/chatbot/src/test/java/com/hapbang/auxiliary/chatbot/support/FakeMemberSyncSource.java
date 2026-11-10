package com.hapbang.auxiliary.chatbot.support;

import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;

import com.hapbang.auxiliary.chatbot.out.MemberSyncSource;
import com.hapbang.shared.member.dto.MemberPageDto;
import com.hapbang.shared.member.dto.UserDto;

/**
 * 회원 원본을 메모리로 흉내 낸다 (#717). {@code @TestBean(methodName = FakeMemberSyncSource.FACTORY)}로 바꿔 끼우고 테스트마다
 * {@link #reset()}한다.
 */
public final class FakeMemberSyncSource implements MemberSyncSource {

    public static final String FACTORY = "com.hapbang.auxiliary.chatbot.support.FakeMemberSyncSource#source";

    private static final FakeMemberSyncSource INSTANCE = new FakeMemberSyncSource();

    private final Map<Long, UserDto> members = new TreeMap<>();

    public static FakeMemberSyncSource source() {
        return INSTANCE;
    }

    public synchronized void reset() {
        members.clear();
    }

    public synchronized void put(UserDto... items) {
        for (UserDto item : items) {
            members.put(item.id(), item);
        }
    }

    @Override
    public synchronized MemberPageDto members(Long afterId, int size) {
        return FakeKeysetPages.page(new ArrayList<>(members.values()), UserDto::id, afterId, size, MemberPageDto::new);
    }
}
