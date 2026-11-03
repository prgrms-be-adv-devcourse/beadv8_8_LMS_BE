-- 출연 평점 (#555). 출연 앱의 리뷰 공개 이벤트(AppearanceReviewPublishedEvent)를 받아 회원별로 집계한다.
-- 리뷰 원본은 출연 앱이 갖는다. 여기에는 반영한 리뷰 기록과 회원별 합계만 둔다.

-- 반영한 리뷰. review_id로 같은 리뷰를 한 번만 반영한다(최소 한 번 전달이라 같은 이벤트가 다시 올 수 있다).
-- 이벤트 값을 모두 남겨 합계가 어긋나면 이 표로 다시 계산할 수 있게 한다
create table members.appearance_review_receipt
(
    review_id     BIGINT                         not null,
    appearance_id BIGINT                         not null,
    reviewer_id   BIGINT                         not null,
    reviewee_id   BIGINT                         not null,
    reviewee_role VARCHAR(255)                   not null,
    rating        INTEGER                        not null,
    published_at  TIMESTAMP(6) WITHOUT TIME ZONE not null,
    created_at    TIMESTAMP(6) WITHOUT TIME ZONE not null,
    constraint pk_appearance_review_receipt primary key (review_id),
    constraint ck_appearance_review_receipt_rating check (rating between 1 and 5),
    constraint ck_appearance_review_receipt_reviewee_role check (reviewee_role in ('HOST', 'GUEST'))
);

-- 회원별 출연 평점 합계. 받은 회원이 그 출연에서 맡은 역할(호스트·게스트)로 나눠 쌓고, 평균은 읽을 때 합계 / 개수로 계산한다.
-- 리뷰가 하나라도 반영되면 행이 생긴다(없으면 평점 없음)
create table members.appearance_rating
(
    user_id            BIGINT                         not null,
    host_rating_sum    BIGINT                         not null,
    host_rating_count  BIGINT                         not null,
    guest_rating_sum   BIGINT                         not null,
    guest_rating_count BIGINT                         not null,
    created_at         TIMESTAMP(6) WITHOUT TIME ZONE not null,
    updated_at         TIMESTAMP(6) WITHOUT TIME ZONE not null,
    constraint pk_appearance_rating primary key (user_id)
);

alter table members.appearance_rating
    add constraint FK_APPEARANCE_RATING_ON_USER foreign key (user_id) references members.users (id);
