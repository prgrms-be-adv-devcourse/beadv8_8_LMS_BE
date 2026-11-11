package com.hapbang.auxiliary.chatbot.app;

import java.util.Collection;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.chatbot.out.MemberCopyStore;

/**
 * 회원을 추천에 보여도 되는지 판단하는 한 곳 (#717, 탈퇴 1단계).
 * <p>
 * <b>보임 = 회원 사본 있음 ∧ 탈퇴({@code WITHDRAWN}) 아님 ∧ 마스킹 안 됨.</b> 사본이 아직 없으면 숨긴다.
 * 숨긴 회원은 추천 후보·추천 대상 모두에서 빠진다(강의 추천 #718, 인플루언서 추천 #719). 데이터는 지우지 않으므로 복구({@code ACTIVE})되면
 * 다시 보인다.
 */
@Component
public class MemberVisibility {

    private final MemberCopyStore memberCopyStore;

    public MemberVisibility(MemberCopyStore memberCopyStore) {
        this.memberCopyStore = memberCopyStore;
    }

    public boolean isVisible(long userId) {
        return !memberCopyStore.findVisible(Set.of(userId)).isEmpty();
    }

    /** 주어진 회원 중 보이는 회원의 ID */
    public Set<Long> visibleOf(Collection<Long> userIds) {
        return memberCopyStore.findVisible(userIds);
    }
}
