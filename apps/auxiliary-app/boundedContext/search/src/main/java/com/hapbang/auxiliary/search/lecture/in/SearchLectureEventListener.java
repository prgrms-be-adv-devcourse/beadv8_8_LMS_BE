package com.hapbang.auxiliary.search.lecture.in;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaNull;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import com.hapbang.auxiliary.search.common.out.IndexNotReadyException;
import com.hapbang.auxiliary.search.in.kafka.MissingTypeEvent;
import com.hapbang.auxiliary.search.in.kafka.SearchKafkaSettings;
import com.hapbang.auxiliary.search.in.kafka.SearchLectureKafkaConfig;
import com.hapbang.auxiliary.search.lecture.app.LectureIndexer;
import com.hapbang.auxiliary.search.lecture.app.LectureIndexer.LectureIndexResult;
import com.hapbang.auxiliary.search.lecture.app.LectureIndexer.ReviewIndexResult;
import com.hapbang.shared.lecture.event.LectureChangedEvent;
import com.hapbang.shared.lecture.event.LectureReviewChangedEvent;

import lombok.extern.slf4j.Slf4j;

/**
 * 강의 이벤트({@code lecture.events.v1}, 그룹 {@code auxiliary-app.search-lecture})로 강의 검색 문서를 넣고 숨기고 평점을 고친다(#882).
 * <ul>
 *     <li>강의 변경({@link LectureChangedEvent})·리뷰 변경({@link LectureReviewChangedEvent})만 쓴다. 구매·장바구니·이의 제기 등 다른 타입은
 *     기본 처리기가 건너뛴다. 이 앱에 클래스가 없는 새 타입({@link MissingTypeEvent})도 건너뛴다(Q3).</li>
 *     <li>최소 한 번 전달·순서 보장 없음이라 문서 쓰기·사본이 강의·리뷰 version으로 멱등 처리한다.</li>
 *     <li>ES 장애는 예외로 두어 재시도 뒤 DLT로 보낸다. 반영이 끝나야 offset을 커밋한다.</li>
 *     <li>alias가 없으면(처음 적재 전) 사본만 고치고 문서 쓰기는 건너뛴다. 처음 적재(#884)가 강의 동기화 API와 사본으로 채운다(L14).</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnBooleanProperty(name = SearchLectureKafkaConfig.ENABLED, matchIfMissing = true)
@KafkaListener(id = "search-lecture", idIsGroup = false, topics = "#{__listener.topic}",
        groupId = "#{__listener.groupId}", containerFactory = SearchLectureKafkaConfig.LECTURE_EVENT_CONTAINER_FACTORY)
public class SearchLectureEventListener {

    private final LectureIndexer indexer;
    private final SearchKafkaSettings settings;

    public SearchLectureEventListener(LectureIndexer indexer,
            @Qualifier(SearchLectureKafkaConfig.LECTURE_EVENT_SETTINGS) SearchKafkaSettings settings) {
        this.indexer = indexer;
        this.settings = settings;
    }

    @KafkaHandler
    public void on(LectureChangedEvent event) {
        try {
            LectureIndexResult result = indexer.apply(event.lecture());
            log.info("강의 이벤트를 검색 문서에 반영했다 lectureId={}, version={}, visible={}, outcome={}",
                    result.lectureId(), result.version(), result.visible(), result.outcome());
        } catch (IndexNotReadyException e) {
            log.debug("강의 검색 index를 처음 적재하기 전이라 강의 이벤트를 건너뛴다 lectureId={}",
                    event.lecture() == null ? null : event.lecture().id());
        }
    }

    @KafkaHandler
    public void on(LectureReviewChangedEvent event) {
        try {
            ReviewIndexResult result = indexer.apply(event.review());
            log.info("강의 리뷰 이벤트를 검색 문서 평점에 반영했다 lectureReviewId={}, lectureId={}, version={}, copied={}, outcome={}",
                    result.reviewId(), result.lectureId(), result.version(), result.copied(), result.outcome());
        } catch (IndexNotReadyException e) {
            log.debug("강의 검색 index를 처음 적재하기 전이라 리뷰는 사본에만 반영했다 lectureReviewId={}",
                    event.review() == null ? null : event.review().id());
        }
    }

    /** 이 앱에 클래스가 없는 새 강의 이벤트(강의 앱이 먼저 배포됨). 검색이 쓰지 않으므로 DLT로 보내지 않고 넘긴다 */
    @KafkaHandler
    public void on(MissingTypeEvent event) {
        log.debug("클래스가 없는 강의 이벤트를 건너뛴다 type={}", event.typeId());
    }

    /**
     * 쓰지 않는 강의 이벤트를 건너뛴다. 강의 이벤트 패키지 밖의 타입(역직렬화기가 지울 수 없는 기본 신뢰 패키지 {@code java.util}·{@code java.lang}
     * 등)은 강의 앱이 보낸 이벤트가 아니므로 다시 시도하지 않고 DLT로 보낸다(알림 {@code SkippablePayloads}와 같다).
     *
     * @throws IllegalArgumentException 강의 이벤트 패키지 밖의 타입
     */
    @KafkaHandler(isDefault = true)
    public void skip(Object event) {
        if (event instanceof KafkaNull) {
            log.debug("값이 없는 강의 이벤트(tombstone)를 건너뛴다");
            return;
        }
        if (!SearchKafkaSettings.LECTURE_TRUSTED_PACKAGE.equals(ClassUtils.getPackageName(event.getClass()))) {
            throw new IllegalArgumentException("강의 이벤트 패키지 밖의 타입입니다: " + event.getClass().getName());
        }
        log.debug("검색이 쓰지 않는 강의 이벤트를 건너뛴다 type={}", event.getClass().getSimpleName());
    }

    public String getTopic() {
        return settings.topic();
    }

    public String getGroupId() {
        return settings.groupId();
    }
}
