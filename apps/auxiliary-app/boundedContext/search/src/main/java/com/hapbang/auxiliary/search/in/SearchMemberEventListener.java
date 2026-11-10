package com.hapbang.auxiliary.search.in;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.search.app.InfluencerMemberIndexer;
import com.hapbang.auxiliary.search.app.InfluencerMemberIndexer.MemberIndexResult;
import com.hapbang.auxiliary.search.config.ConditionalOnSearchEnabled;
import com.hapbang.auxiliary.search.in.kafka.SearchKafkaConfig;
import com.hapbang.auxiliary.search.in.kafka.SearchKafkaSettings;
import com.hapbang.shared.member.event.UserJoinedEvent;
import com.hapbang.shared.member.event.UserModifiedEvent;

import lombok.extern.slf4j.Slf4j;

/**
 * 회원 이벤트({@code member.events.v1}, 그룹 {@code auxiliary-app.search-member})로 인플루언서 검색 문서를 넣고 숨긴다(#771).
 * <ul>
 *     <li>가입({@link UserJoinedEvent})·변경({@link UserModifiedEvent})만 쓴다. 파기({@code UserMaskedEvent})는 받지 않는다:
 *     숨길 때 이미 개인정보를 지웠고 파기에는 version이 없다(#767). 로그아웃 등 다른 타입은 기본 처리기가 건너뛴다.</li>
 *     <li>최소 한 번 전달·순서 보장 없음이라 문서 쓰기가 회원 version으로 멱등 처리한다.</li>
 *     <li>ES 장애는 예외로 두어 재시도 뒤 DLT로 보낸다. 반영이 끝나야 offset을 커밋한다.</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnSearchEnabled
@ConditionalOnBooleanProperty(name = SearchKafkaConfig.ENABLED, matchIfMissing = true)
@KafkaListener(id = "search-member", idIsGroup = false, topics = "#{__listener.topic}",
        groupId = "#{__listener.groupId}", containerFactory = SearchKafkaConfig.MEMBER_EVENT_CONTAINER_FACTORY)
public class SearchMemberEventListener {

    private final InfluencerMemberIndexer indexer;
    private final SearchKafkaSettings settings;

    public SearchMemberEventListener(InfluencerMemberIndexer indexer,
            @Qualifier(SearchKafkaConfig.MEMBER_EVENT_SETTINGS) SearchKafkaSettings settings) {
        this.indexer = indexer;
        this.settings = settings;
    }

    @KafkaHandler
    public void on(UserJoinedEvent event) {
        logResult(indexer.apply(event.user()));
    }

    @KafkaHandler
    public void on(UserModifiedEvent event) {
        logResult(indexer.apply(event.user()));
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
