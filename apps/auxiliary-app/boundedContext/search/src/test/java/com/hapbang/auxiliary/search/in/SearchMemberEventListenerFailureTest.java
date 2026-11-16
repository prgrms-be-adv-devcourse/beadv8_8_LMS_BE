package com.hapbang.auxiliary.search.in;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.auxiliary.search.in.kafka.SearchKafkaSettings;
import com.hapbang.auxiliary.search.influencer.app.InfluencerMemberIndexer;
import com.hapbang.auxiliary.search.lecture.app.SellerIndexer;
import com.hapbang.auxiliary.search.lecture.app.SellerIndexer.SellerIndexResult;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.shared.member.event.UserModifiedEvent;

/** 회원 이벤트를 대상마다 반영할 때 한 대상의 실패가 다른 대상의 반영을 막지 않는다(#882 코드 리뷰) */
class SearchMemberEventListenerFailureTest {

    private static final UserDto USER = new UserDto(7L, "정지된", Role.VERIFIED_INFLUENCER, UserStatus.SUSPENDED, 3L);

    private final InfluencerMemberIndexer influencers = mock(InfluencerMemberIndexer.class);
    private final SellerIndexer sellers = mock(SellerIndexer.class);
    private final SearchMemberEventListener listener = new SearchMemberEventListener(influencers, sellers,
            SearchKafkaSettings.memberDefaults());

    @Test
    @DisplayName("인플루언서 반영이 실패해도 판매자 반영은 하고, 실패는 그 뒤에 던져 재시도·DLT로 보낸다")
    void influencerFailureStillAppliesSeller() {
        IllegalStateException failure = new IllegalStateException("ES가 인플루언서 문서를 거절");
        when(influencers.apply(any())).thenThrow(failure);
        when(sellers.apply(any())).thenReturn(new SellerIndexResult(7L, 3L, true, 2));

        assertThatThrownBy(() -> listener.on(new UserModifiedEvent(USER))).isSameAs(failure);
        verify(sellers).apply(USER);
    }

    @Test
    @DisplayName("둘 다 실패하면 앞의 실패를 던지고 뒤의 실패는 suppressed로 붙인다")
    void bothFail() {
        IllegalStateException first = new IllegalStateException("인플루언서");
        IllegalStateException second = new IllegalStateException("판매자");
        when(influencers.apply(any())).thenThrow(first);
        when(sellers.apply(any())).thenThrow(second);

        assertThatThrownBy(() -> listener.on(new UserModifiedEvent(USER))).isSameAs(first)
                .satisfies(e -> org.assertj.core.api.Assertions.assertThat(e.getSuppressed()).containsExactly(second));
    }
}
