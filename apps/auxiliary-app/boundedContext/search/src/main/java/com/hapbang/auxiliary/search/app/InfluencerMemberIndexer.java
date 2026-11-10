package com.hapbang.auxiliary.search.app;

import java.time.Clock;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.hapbang.auxiliary.search.config.ConditionalOnSearchEnabled;
import com.hapbang.auxiliary.search.out.InfluencerDocumentStore;
import com.hapbang.auxiliary.search.out.IndexNotReadyException;
import com.hapbang.auxiliary.search.out.InfluencerIndexSetup;
import com.hapbang.auxiliary.search.out.MemberDocument;
import com.hapbang.auxiliary.search.out.MemberProfile;
import com.hapbang.auxiliary.search.out.WriteOutcome;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;

/**
 * 회원 정보({@link UserDto})를 인플루언서 검색 문서에 반영한다(#771).
 * <ul>
 *     <li>색인 대상은 지금 인플루언서 목록과 같은 기준이다: 인플루언서·인증된 인플루언서 + ACTIVE. 대상이면 프로필을 넣거나 덮는다.</li>
 *     <li>대상이 아니면(일반 유저, 강등, 정지, 탈퇴) 숨긴다. 문서가 없어도 숨김 문서를 남긴다(#771 Q6).</li>
 *     <li>회원 version 비교는 문서 쓰기({@link InfluencerDocumentStore})가 한다. 늦게 온 이벤트는 noop이다.</li>
 *     <li>쓰기 전에 index·alias를 준비한다(한 번 성공하면 다시 묻지 않는다).</li>
 * </ul>
 */
@Service
@ConditionalOnSearchEnabled
public class InfluencerMemberIndexer {

    static final Set<Role> INFLUENCER_ROLES = Set.of(Role.INFLUENCER, Role.VERIFIED_INFLUENCER);

    private final InfluencerIndexSetup indexSetup;
    private final InfluencerDocumentStore store;
    private final ContentCategoryNames categoryNames;
    private final Clock clock;

    @Autowired
    public InfluencerMemberIndexer(InfluencerIndexSetup indexSetup, InfluencerDocumentStore store,
            ContentCategoryNames categoryNames) {
        this(indexSetup, store, categoryNames, Clock.systemDefaultZone());
    }

    InfluencerMemberIndexer(InfluencerIndexSetup indexSetup, InfluencerDocumentStore store,
            ContentCategoryNames categoryNames, Clock clock) {
        this.indexSetup = indexSetup;
        this.store = store;
        this.categoryNames = categoryNames;
        this.clock = clock;
    }

    /**
     * @throws IllegalArgumentException 회원 ID·역할·상태가 없는 정보(다시 처리해도 같다)
     * @throws IndexNotReadyException   alias가 없는 경우(처음 적재 전, #773 Q1). 부르는 쪽이 건너뛴다
     * @throws IllegalStateException    ES에 쓰지 못한 경우(다시 처리하면 될 수 있다). ES가 요청 내용 때문에 거절했으면
     *                                  하위 타입 {@link com.hapbang.auxiliary.search.out.DocumentRejectedException}이다(다시 해도 같다)
     */
    public MemberIndexResult apply(UserDto user) {
        requireComplete(user);
        indexSetup.ensureReady();
        boolean visible = isIndexed(user);
        WriteOutcome outcome = visible
                ? store.show(user.id(), user.version(), profile(user))
                : store.hide(user.id(), user.version(), clock.instant());
        return new MemberIndexResult(user.id(), user.version(), visible, outcome);
    }

    /**
     * 처음 적재·재색인(#773)이 새 index에 넣을 문서. 쓰지 않고 만들기만 한다. 정상 인플루언서면 보이는 문서, 아니면 숨김 문서(지금 시각에 숨김)다.
     * 숨김 문서도 넣어 교체 뒤 늦게 온 옛 이벤트를 막는다(#773 리뷰 Q3).
     *
     * @throws IllegalArgumentException 회원 ID·역할·상태가 없는 정보
     */
    MemberDocument documentOf(UserDto user) {
        requireComplete(user);
        return isIndexed(user)
                ? MemberDocument.visible(user.id(), user.version(), profile(user))
                : MemberDocument.hidden(user.id(), user.version(), clock.instant());
    }

    private static void requireComplete(UserDto user) {
        if (user == null || user.id() == null || user.role() == null || user.status() == null) {
            throw new IllegalArgumentException("회원 ID·역할·상태가 없는 회원 정보는 색인할 수 없습니다.");
        }
    }

    /** 검색에 보이는 회원인지: 인플루언서·인증된 인플루언서 + ACTIVE */
    static boolean isIndexed(UserDto user) {
        return INFLUENCER_ROLES.contains(user.role()) && user.status() == UserStatus.ACTIVE;
    }

    private MemberProfile profile(UserDto user) {
        return new MemberProfile(user.nickname(), user.bio(), user.role().name(),
                user.role() == Role.VERIFIED_INFLUENCER,
                user.contentCategories().stream()
                        .map(code -> new MemberProfile.Category(code, categoryNames.nameOf(code))).toList());
    }

    /**
     * @param userId        회원 ID
     * @param memberVersion 회원 version(null이면 모름)
     * @param visible       검색에 보이게 썼는지(false면 숨김)
     * @param outcome       쓰기 결과
     */
    public record MemberIndexResult(long userId, Long memberVersion, boolean visible, WriteOutcome outcome) {
    }
}
