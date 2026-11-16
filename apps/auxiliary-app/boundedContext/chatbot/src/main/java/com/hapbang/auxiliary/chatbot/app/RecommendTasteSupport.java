package com.hapbang.auxiliary.chatbot.app;

import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.chatbot.config.ChatbotRecommendLlmProperties;
import com.hapbang.auxiliary.chatbot.config.ChatbotRecommendLlmProperties.Taste;
import com.hapbang.auxiliary.chatbot.domain.InfluencerProfile;
import com.hapbang.auxiliary.chatbot.domain.RecommendTaste;
import com.hapbang.auxiliary.chatbot.out.InfluencerRecommendStore;

import lombok.RequiredArgsConstructor;

/**
 * 버튼 경로 리랭킹에 넣을 요청 회원의 취향을 모은다(#948). {@code rerank.taste}가 {@link Taste#PUBLIC}일 때만 읽는다.
 * <ul>
 *     <li>관심사: 이번 요청에 본인이 입력한 문장(쿼리 확장 전 원문).</li>
 *     <li>공개 프로필: 요청 회원이 인플루언서 역할이고 마스킹되지 않았을 때만 분야 이름과 자기소개. 분야·자기소개는 회원 모듈에서
 *     인플루언서만 넣을 수 있다(#711). 강등된 회원에게 남은 값은 쓰지 않는다.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class RecommendTasteSupport {

    private final InfluencerRecommendStore store;
    private final ChatbotCategoryNames categoryNames;
    private final ChatbotRecommendLlmProperties properties;

    /**
     * @param interest 관심사 원문. 없으면 null
     * @return {@code taste}가 {@code NONE}이면 {@link RecommendTaste#NOT_USED}(회원 사본을 읽지 않는다). 취향을 쓸지는 여기서만 정한다
     */
    public RecommendTaste read(long memberId, @Nullable String interest) {
        if (properties.rerank().taste() != Taste.PUBLIC) {
            return RecommendTaste.NOT_USED;
        }
        InfluencerProfile profile = store.profiles(List.of(memberId)).get(memberId);
        if (profile == null || profile.masked() || !profile.isInfluencer()) {
            return RecommendTaste.of(interest, List.of(), null);
        }
        String bio = profile.bio() == null ? null : TextCut.head(profile.bio(), properties.rerank().descriptionMaxLength());
        return RecommendTaste.of(interest, categoryNames.namesOf(profile.categories()), bio);
    }
}
