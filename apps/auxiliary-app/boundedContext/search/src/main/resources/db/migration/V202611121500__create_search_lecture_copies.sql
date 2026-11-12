-- 강의 검색 사본(#882). 강의 문서에 넣을 판매자 닉네임·상태와 평균 평점·리뷰 수를 다른 앱을 부르지 않고 채우려고 이벤트 값을 적어 둔다.
-- 강의 자체는 사본을 두지 않는다(강의 이벤트 값을 바로 문서로 쓰고, 처음 적재·보정은 강의 동기화 API를 읽는다, L13·L14).

-- 회원 사본(회원 UserDto, Q1). 누가 판매자가 될지 몰라 모든 회원을 둔다. 강의 문서의 sellerNickname·sellerActive 출처다.
-- 회원 이벤트 소비(auxiliary-app.search-member)가 쓰고, 처음 적재·증분 보정(#884)이 같은 반영 코드로 맞춘다
CREATE TABLE search.member_copy (
    id         BIGINT       PRIMARY KEY,
    -- 닉네임. 파기(마스킹)된 회원은 회원 앱이 보낸 값 그대로다
    nickname   TEXT,
    -- ACTIVE, SUSPENDED, WITHDRAWN. ACTIVE가 아니면 그 판매자의 강의를 검색에서 뺀다(L8)
    status     VARCHAR(20)  NOT NULL,
    -- 회원 원본 version. UserDto 규칙상 비어 있을 수 있다(버전 도입 전 정보)
    version    BIGINT,
    synced_at  TIMESTAMP(6) NOT NULL
);

-- 강의 리뷰 별점 사본(강의 LectureReviewSyncDto, L7·Q4). 강의별 평균 평점·리뷰 수를 다시 셀 때 쓴다. counted는 강의가 판단한 값 그대로다
CREATE TABLE search.lecture_review_copy (
    id         BIGINT       PRIMARY KEY,
    lecture_id BIGINT       NOT NULL,
    -- 별점 1~5
    rating     INTEGER      NOT NULL,
    counted    BOOLEAN      NOT NULL,
    deleted    BOOLEAN      NOT NULL,
    version    BIGINT       NOT NULL,
    synced_at  TIMESTAMP(6) NOT NULL
);

-- 리뷰가 바뀐 강의의 평균·리뷰 수를 다시 센다
CREATE INDEX idx_lecture_review_copy_lecture ON search.lecture_review_copy (lecture_id);
