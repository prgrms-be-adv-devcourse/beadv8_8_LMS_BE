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
    private final Map<String, String> categoryNames = new TreeMap<>();
    private RuntimeException categoryFailure;
    private int categoryCalls;

    public static FakeMemberSyncSource source() {
        return INSTANCE;
    }

    public synchronized void reset() {
        members.clear();
        categoryNames.clear();
        categoryNames.putAll(Map.of("GAME", "게임", "MUSIC", "음악", "COOKING", "요리"));
        categoryFailure = null;
        categoryCalls = 0;
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

    /** 분야 이름 조회를 실패하게 한다(회원 API 장애). reset()으로 되돌린다 */
    public synchronized void failCategoryNames(RuntimeException failure) {
        categoryFailure = failure;
    }

    /** 분야 이름을 부른 횟수 */
    public synchronized int categoryCalls() {
        return categoryCalls;
    }

    /** 분야 이름 하나를 바꾼다 */
    public synchronized void putCategoryName(String code, String name) {
        categoryNames.put(code, name);
    }

    @Override
    public synchronized Map<String, String> categoryNames() {
        categoryCalls++;
        if (categoryFailure != null) {
            throw categoryFailure;
        }
        return Map.copyOf(categoryNames);
    }
}
