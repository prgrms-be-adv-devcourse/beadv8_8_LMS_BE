# JPA와 쿼리

마이그레이션 규칙은 [DB 마이그레이션](../db-migration.md)에 있다.

## 엔티티

- 엔티티는 `private` 생성자 + 정적 팩토리(`from`/`create`/`register`)로 만든다.
- `open-in-view: false`이므로 엔티티 → 응답 DTO 변환은 서비스 레이어 트랜잭션 안에서 끝낸다.
- Hibernate는 flush 때 INSERT를 UPDATE보다 먼저 실행한다. 같은 트랜잭션에서 Soft Delete 후 같은 키로 새 행을 넣으면 부분 유일 인덱스에 걸리므로, 삭제 표시 뒤에 `flush()`를 먼저 한다 (강의 D47).

## 유일 인덱스 위반 (D48)

- 동시 요청의 유일 인덱스 위반은 강의 모듈 `UniqueIndexViolation.isOf(e, 인덱스 이름...)`(PSQLException의 SQLState 23505 + 제약 이름)로 판별해 그 인덱스만 409로 바꾸고 나머지는 그대로 던진다.
- Hibernate `getConstraintName()`은 DB 오류 메시지의 영어 문구에서 뽑아서, 한국어 메시지면 null이다.
- DB 없는 테스트는 `PostgresErrorFixture.violation(sqlState, 제약)`을 쓴다.

## HQL

- 집계 정렬은 HQL `order by (select avg(...) ...) desc nulls last`(상관 서브쿼리)로 된다 (`LectureRepository.findOnSaleOrderByRating`).
- `@Query`의 LIKE 검색어는 `like concat('%', :#{escape(#kw)}, '%') escape :#{escapeCharacter()}`로 `%`·`_`를 글자 그대로 찾는다 (파생 쿼리 `ContainingIgnoreCase`와 같은 동작).
- HQL CASE 식의 결과·파라미터로 엔티티 밖 enum(shared 등)은 쓸 수 없다 (`Unsupported domain type`, `Could not determine ValueMapping`). 상수 이름 문자열로 내고 record 생성자에서 `valueOf`한다.
- 파라미터가 든 식을 `group by`에 다시 쓰면 PostgreSQL이 다른 식으로 본다 (`must appear in the GROUP BY`). 파생 테이블(`from (select … ) e group by e.x`)로 한 번만 쓴다 (`LectureOrderRepository.summarizeEarningsByName`).
