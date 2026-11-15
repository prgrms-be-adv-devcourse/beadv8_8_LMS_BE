package com.hapbang.auxiliary.chatbot.llm.recommend;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.hapbang.auxiliary.chatbot.app.RecommendEvalAccess;
import com.hapbang.auxiliary.chatbot.domain.InfluencerProfile;
import com.hapbang.auxiliary.chatbot.domain.InfluencerProfileText;
import com.hapbang.auxiliary.chatbot.domain.LectureSnapshot;
import com.hapbang.auxiliary.chatbot.llm.recommend.RecommendEvalData.Activity;
import com.hapbang.auxiliary.chatbot.llm.recommend.RecommendEvalData.Appearance;
import com.hapbang.auxiliary.chatbot.llm.recommend.RecommendEvalData.AppearanceReview;
import com.hapbang.auxiliary.chatbot.llm.recommend.RecommendEvalData.Influencer;
import com.hapbang.auxiliary.chatbot.llm.recommend.RecommendEvalData.Lecture;
import com.hapbang.auxiliary.chatbot.llm.recommend.RecommendEvalData.Member;
import com.hapbang.auxiliary.chatbot.llm.recommend.RecommendEvalData.Rating;
import com.hapbang.auxiliary.chatbot.support.RecommendRows;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;

/**
 * 더미 데이터를 테스트 DB·ES에 넣는다(#867). 운영에서 이벤트·싱크·색인 처리기가 하는 일을 건너뛰고 결과만 넣는다(추천 코드는 사본과 index만 읽는다).
 * <ul>
 *     <li>사본: {@link RecommendRows}로 SQL을 바로 넣는다. 정답을 숨긴 데이터({@link RecommendEvalHoldout#hide})를 받는다.</li>
 *     <li>벡터: 운영과 같은 글({@link LectureSnapshot#indexText()}, {@link InfluencerProfileText#of})을 임베딩해(캐시) 운영 필드로 넣는다.
 *     넣는 순서는 ID 순이고 refresh는 끝에 한 번이다(같은 입력이면 같은 index).</li>
 * </ul>
 */
final class RecommendEvalLoader {

    private final RecommendRows rows;
    private final RecommendEvalAccess access;

    RecommendEvalLoader(RecommendRows rows, RecommendEvalAccess access) {
        this.rows = rows;
        this.access = access;
    }

    /** 사본 테이블(비어 있어야 한다) */
    void copies(RecommendEvalData visible) {
        for (Member member : visible.members()) {
            rows.member(member.id());
        }
        for (long sellerId : visible.sellerIds()) {
            rows.member(sellerId);
        }
        for (Lecture lecture : visible.lectures()) {
            rows.lecture(lecture.id(), lecture.sellerId(), lecture.title(), lecture.description(), lecture.contentTitles(),
                    true, false, false);
        }
        for (Activity purchase : visible.purchases()) {
            rows.purchase(purchase.id(), purchase.memberId(), purchase.lectureId(), "OWNED", purchase.at());
        }
        for (Activity cart : visible.carts()) {
            rows.cart(cart.id(), cart.memberId(), cart.lectureId(), false, cart.at());
        }
        for (Rating review : visible.lectureReviews()) {
            rows.review(review.id(), review.memberId(), review.lectureId(), review.rating());
        }
        for (Influencer influencer : visible.influencers()) {
            rows.influencer(influencer.id(), influencer.fields(), influencer.bio());
        }
        for (Appearance appearance : visible.appearances()) {
            rows.completedAppearance(appearance.id(), appearance.hostId(), appearance.guestId());
        }
        for (AppearanceReview review : visible.appearanceReviews()) {
            rows.appearanceReview(review.id(), review.appearanceId(), review.revieweeId(), review.rating());
        }
    }

    /** 강의·인플루언서 벡터(index는 비어 있어야 한다) */
    void vectors(RecommendEvalData data) {
        access.ensureIndexes();
        List<LectureSnapshot> lectures = new ArrayList<>();
        List<String> lectureTexts = new ArrayList<>();
        for (Lecture lecture : data.lectures()) {
            LectureSnapshot snapshot = snapshot(lecture);
            lectures.add(snapshot);
            lectureTexts.add(snapshot.indexText());
        }
        List<float[]> lectureVectors = access.embedDocuments(lectureTexts);
        for (int i = 0; i < lectures.size(); i++) {
            access.upsertLecture(lectures.get(i), lectureVectors.get(i));
        }

        Map<String, String> names = data.fieldNames();
        List<InfluencerProfile> profiles = new ArrayList<>();
        List<InfluencerProfileText> texts = new ArrayList<>();
        List<String> influencerTexts = new ArrayList<>();
        for (Influencer influencer : data.influencers()) {
            InfluencerProfileText text = profileText(influencer, names);
            profiles.add(new InfluencerProfile(influencer.id(), Role.INFLUENCER, UserStatus.ACTIVE, influencer.fields(),
                    influencer.bio(), false));
            texts.add(text);
            influencerTexts.add(text.text());
        }
        List<float[]> influencerVectors = access.embedDocuments(influencerTexts);
        for (int i = 0; i < profiles.size(); i++) {
            access.upsertInfluencer(profiles.get(i), texts.get(i), influencerVectors.get(i));
        }
        access.refresh();
    }

    /** 판매 중인 강의 사본. 임베딩하는 글은 운영과 같은 {@link LectureSnapshot#indexText()} */
    static LectureSnapshot snapshot(Lecture lecture) {
        return new LectureSnapshot(lecture.id(), lecture.sellerId(), lecture.title(), lecture.description(),
                lecture.contentTitles(), true, false, false);
    }

    /** 운영과 같은 인플루언서 임베딩 글(분야 이름 + 자기소개) */
    static InfluencerProfileText profileText(Influencer influencer, Map<String, String> fieldNames) {
        List<String> categoryNames = new ArrayList<>();
        for (String code : influencer.fields()) {
            categoryNames.add(fieldNames.get(code));
        }
        return InfluencerProfileText.of(categoryNames, influencer.bio());
    }
}
