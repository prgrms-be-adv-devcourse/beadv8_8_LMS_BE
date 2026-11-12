package com.hapbang.auxiliary.search.lecture.app;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.hapbang.auxiliary.search.common.out.IndexNotReadyException;
import com.hapbang.auxiliary.search.lecture.out.LectureDocumentStore;
import com.hapbang.auxiliary.search.lecture.out.LectureIndexSetup;
import com.hapbang.auxiliary.search.lecture.out.SearchMemberCopyStore;
import com.hapbang.auxiliary.search.lecture.out.Seller;
import com.hapbang.shared.member.dto.UserDto;

/**
 * 회원 정보를 회원 사본에 적고, 판매자 닉네임·상태가 바뀌었으면 그 판매자의 강의 문서를 고친다(#882 L5·L8·Q1).
 * <p>
 * 모든 회원을 사본에 둔다(누가 판매자가 될지 모른다). 문서를 고치는 일은 닉네임·상태가 바뀌었을 때만 한다(update_by_query). 사본은 alias가 없어도
 * (처음 적재 전) 고친다(L14).
 */
@Service
public class SellerIndexer {

    /** 사본 반영 시각의 시간대. 검색 모듈의 다른 DB 시각과 같다 */
    static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private final LectureIndexSetup indexSetup;
    private final LectureDocumentStore documents;
    private final SearchMemberCopyStore members;
    private final Clock clock;

    @Autowired
    public SellerIndexer(LectureIndexSetup indexSetup, LectureDocumentStore documents, SearchMemberCopyStore members) {
        this(indexSetup, documents, members, Clock.system(ZONE));
    }

    SellerIndexer(LectureIndexSetup indexSetup, LectureDocumentStore documents, SearchMemberCopyStore members,
            Clock clock) {
        this.indexSetup = indexSetup;
        this.documents = documents;
        this.members = members;
        this.clock = clock;
    }

    /**
     * @return 판매자 정보가 바뀌어 강의 문서를 고쳤으면 고친 문서 수, 고칠 일이 없으면 비어 있다
     * @throws IllegalArgumentException 회원 ID·상태가 없는 정보(다시 처리해도 같다)
     * @throws IndexNotReadyException   alias가 없는 경우(처음 적재 전). 사본은 이미 고쳤다. 부르는 쪽이 건너뛴다
     * @throws IllegalStateException    강의 문서를 고치지 못한 경우(다시 처리하면 사본의 판매자 정보로 다시 고친다)
     */
    public SellerIndexResult apply(UserDto user) {
        if (user == null || user.id() == null || user.status() == null) {
            throw new IllegalArgumentException("회원 ID·상태가 없는 회원 정보는 반영할 수 없습니다.");
        }
        Optional<Seller> changed = members.apply(user, LocalDateTime.now(clock));
        if (changed.isEmpty()) {
            return new SellerIndexResult(user.id(), user.version(), false, 0);
        }
        indexSetup.ensureReady();
        long documentCount = documents.updateSeller(changed.get());
        return new SellerIndexResult(user.id(), user.version(), true, documentCount);
    }

    /**
     * @param userId        회원 ID
     * @param memberVersion 회원 version(null이면 모름)
     * @param sellerChanged 판매자 정보(닉네임·상태)를 강의 문서에 다시 맞췄는지
     * @param documents     고친 강의 문서 수
     */
    public record SellerIndexResult(long userId, Long memberVersion, boolean sellerChanged, long documents) {
    }
}
