package com.hapbang.auxiliary.notification.support;

import com.hapbang.auxiliary.notification.app.NotificationMemberSource;
import com.hapbang.shared.member.dto.MemberPageDto;
import com.hapbang.shared.member.dto.UserDto;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 회원 목록 내부 API(`docs/api/internal-members.md` 1장)를 흉내 낸다. 회원 ID 오름차순 키셋 페이지이고, 남은 실패 횟수만큼
 * 일시 장애(예외)를 낸다. 부른 횟수를 센다.
 */
public class FakeMemberSource implements NotificationMemberSource {

    private final List<UserDto> members = new CopyOnWriteArrayList<>();
    private final AtomicInteger failuresLeft = new AtomicInteger();
    private final AtomicInteger calls = new AtomicInteger();

    public void reset(List<UserDto> all) {
        members.clear();
        members.addAll(all);
        failuresLeft.set(0);
        calls.set(0);
    }

    /** 다음 n번의 호출을 일시 장애로 실패시킨다. {@link Integer#MAX_VALUE}면 풀 때까지 계속 실패한다 */
    public void failNext(int n) {
        failuresLeft.set(n);
    }

    public int calls() {
        return calls.get();
    }

    @Override
    public MemberPageDto members(Long afterId, int size) {
        calls.incrementAndGet();
        if (failuresLeft.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            throw new IllegalStateException("회원 API 일시 장애(시험)");
        }
        List<UserDto> sorted = new ArrayList<>(members);
        sorted.sort(Comparator.comparing(UserDto::id));
        List<UserDto> page = sorted.stream().filter(u -> afterId == null || u.id() > afterId).limit(size).toList();
        boolean more = sorted.stream().anyMatch(u -> !page.isEmpty() && u.id() > page.getLast().id());
        return new MemberPageDto(page, more ? page.getLast().id() : null);
    }

    /** 시험 컨텍스트에 이 소스를 운영 소스 대신 등록한다 */
    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {

        @Bean
        @Primary
        FakeMemberSource fakeMemberSource() {
            return new FakeMemberSource();
        }
    }
}
