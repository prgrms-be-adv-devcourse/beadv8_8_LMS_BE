package com.hapbang.auxiliary.search.in;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.search.influencer.app.InfluencerMemberIndexer;
import com.hapbang.auxiliary.search.influencer.app.InfluencerMemberIndexer.MemberIndexResult;
import com.hapbang.auxiliary.search.lecture.app.SellerIndexer;
import com.hapbang.auxiliary.search.lecture.app.SellerIndexer.SellerIndexResult;
import com.hapbang.auxiliary.search.in.kafka.SearchKafkaConfig;
import com.hapbang.auxiliary.search.in.kafka.SearchKafkaSettings;
import com.hapbang.auxiliary.search.common.out.IndexNotReadyException;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.shared.member.event.UserJoinedEvent;
import com.hapbang.shared.member.event.UserModifiedEvent;

import lombok.extern.slf4j.Slf4j;

/**
 * 회원 이벤트({@code member.events.v1}, 그룹 {@code auxiliary-app.search-member})를 검색 대상마다 반영한다. 인플루언서 검색 문서를 넣고
 * 숨기고(#771), 강의 검색의 회원 사본에 적어 판매자 닉네임·상태가 바뀌면 그 판매자의 강의 문서를 고친다(#882 Q1·Q6). 대상 순서는 인플루언서 →
 * 강의 판매자이고, 한 대상이 실패해도 다른 대상은 반영한 뒤 이벤트 전체를 다시 시도한다(두 반영 모두 version으로 멱등이다).
 * <ul>
 *     <li>가입({@link UserJoinedEvent})·변경({@link UserModifiedEvent})만 쓴다. 파기({@code UserMaskedEvent})는 받지 않는다:
 *     숨길 때 이미 개인정보를 지웠고 파기에는 version이 없다(#767). 로그아웃 등 다른 타입은 기본 처리기가 건너뛴다.</li>
 *     <li>최소 한 번 전달·순서 보장 없음이라 문서 쓰기가 회원 version으로 멱등 처리한다.</li>
 *     <li>ES 장애는 예외로 두어 재시도 뒤 DLT로 보낸다. 반영이 끝나야 offset을 커밋한다.</li>
 *     <li>대상마다 alias가 없으면(처음 적재 전) 그 대상만 건너뛴다. 처음 적재가 회원 목록을 읽고 T − 1분부터 따라잡아 반영한다(#773 Q1).
 *     강의 판매자는 회원 사본(DB)을 먼저 고치고 문서 쓰기만 건너뛴다(L14).</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnBooleanProperty(name = SearchKafkaConfig.ENABLED, matchIfMissing = true)
@KafkaListener(id = "search-member", idIsGroup = false, topics = "#{__listener.topic}",
        groupId = "#{__listener.groupId}", containerFactory = SearchKafkaConfig.MEMBER_EVENT_CONTAINER_FACTORY)
public class SearchMemberEventListener {

    private final InfluencerMemberIndexer indexer;
    private final SellerIndexer sellerIndexer;
    private final SearchKafkaSettings settings;

    public SearchMemberEventListener(InfluencerMemberIndexer indexer, SellerIndexer sellerIndexer,
            @Qualifier(SearchKafkaConfig.MEMBER_EVENT_SETTINGS) SearchKafkaSettings settings) {
        this.indexer = indexer;
        this.sellerIndexer = sellerIndexer;
        this.settings = settings;
    }

    @KafkaHandler
    public void on(UserJoinedEvent event) {
        apply(event.user());
    }

    @KafkaHandler
    public void on(UserModifiedEvent event) {
        apply(event.user());
    }

    /**
     * 대상마다 반영한다. 한 대상이 실패해도 다른 대상은 반영하고, 실패는 모두 끝난 뒤 던진다(재시도·DLT). 인플루언서 문서를 ES가 거절해도 판매자 정지·
     * 닉네임 변경은 강의 문서에 반영되게 하기 위해서다. 다시 처리해도 두 반영 모두 version으로 멱등이다.
     */
    private void apply(UserDto user) {
        RuntimeException failure = null;
        try {
            logResult(indexer.apply(user));
        } catch (IndexNotReadyException e) {
            // 처음 적재 전이다. 적재가 회원 목록을 읽고 따라잡기가 반영하므로 DLT로 보내지 않고 넘긴다(#773 Q1)
            log.debug("검색 index를 처음 적재하기 전이라 회원 이벤트를 건너뛴다 userId={}", user.id());
        } catch (RuntimeException e) {
            failure = e;
        }
        try {
            SellerIndexResult result = sellerIndexer.apply(user);
            if (result.sellerChanged()) {
                log.info("판매자 정보를 강의 검색 문서에 반영했다 userId={}, memberVersion={}, documents={}",
                        result.userId(), result.memberVersion(), result.documents());
            }
        } catch (IndexNotReadyException e) {
            log.debug("강의 검색 index를 처음 적재하기 전이라 판매자 정보는 회원 사본에만 반영했다 userId={}", user.id());
        } catch (RuntimeException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    @KafkaHandler(isDefault = true)
    public void skip(Object event) {
        log.debug("검색이 쓰지 않는 회원 이벤트를 건너뛴다 type={}", event.getClass().getSimpleName());
    }

    // 이벤트 종류는 MDC(eventType)에 있다
    private static void logResult(MemberIndexResult result) {
        log.info("회원 이벤트를 검색 문서에 반영했다 userId={}, memberVersion={}, visible={}, outcome={}",
                result.userId(), result.memberVersion(), result.visible(), result.outcome());
    }

    public String getTopic() {
        return settings.topic();
    }

    public String getGroupId() {
        return settings.groupId();
    }
}
