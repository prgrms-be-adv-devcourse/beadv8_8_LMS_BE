# DB 마이그레이션 (Flyway)

모든 환경(local·dev·prod·테스트)의 DB 스키마는 Flyway 마이그레이션 SQL로 만든다. Hibernate는 테이블을 만들지 않고, 엔티티와 테이블이 맞는지만 검사한다(`ddl-auto: validate`). 어긋나면 앱 시작과 테스트가 실패한다.

엔티티가 기준이고, SQL은 엔티티에서 생성한 뒤 검토해서 커밋한다.

```
엔티티 수정 → IntelliJ(JPA Buddy)로 마이그레이션 생성 → SQL 검토·보완 → 커밋 → 배포 시 앱 시작과 함께 자동 적용
```

## 파일 위치와 이름

각 모듈의 `src/main/resources/db/migration/`에 둔다 (Flyway·JPA Buddy 기본 위치).

```
boundedContext/{모듈}/src/main/resources/db/migration/V{yyyyMMddHHmm}__{설명}.sql
예) boundedContext/lecture/src/main/resources/db/migration/V202610121104__create_lecture_tables.sql
```

- 버전은 **작성 시각(KST, 분 단위)**으로 쓴다. 여러 모듈이 Flyway 하나와 이력 테이블(`public.flyway_schema_history`) 하나를 함께 쓰므로, 1·2·3 같은 순번을 쓰면 팀원끼리 번호가 겹친다.
- 먼저 작성했지만 늦게 병합된 마이그레이션(이미 적용된 버전보다 낮은 버전)도 실행되도록 `out-of-order: true`로 설정했다. 모듈마다 스키마가 달라 실행 순서가 바뀌어도 안전하다. 같은 모듈 안에서는 앞선 마이그레이션에 의존하는 파일이 먼저 병합되지 않게 한다.
- `V`는 대문자, 버전과 설명 사이는 밑줄 **두 개**(`__`). 형식이 틀리면 Flyway가 오류 없이 파일을 무시한다.
- 설명은 영어 소문자와 `_`로 쓰고 **모듈 이름을 넣는다** (`create_lecture_tables`, `add_lecture_level`). 앱 실행 시에는 모든 모듈의 SQL이 한 폴더로 합쳐지므로, 이름으로 어느 모듈 것인지 알 수 있게 한다.

## IntelliJ(JPA Buddy)로 생성하기

1. `Cmd + Shift + A` → `Flyway Versioned Migration` (또는 `View → Tool Windows → JPA Explorer`에서 생성)
2. Source는 `Model`(엔티티), Target은 비교할 DB. 모듈의 첫 마이그레이션은 빈 DB와 비교한다.
3. **Scope는 해당 모듈의 main만** 고른다 (예: `hapbang.lecture.main`). 프로젝트 전체를 고르면 다른 모듈 테이블이 섞인다.
4. DB에서 비교할 스키마를 고를 수 있으면 해당 모듈 스키마만 고른다. 다른 스키마를 넣으면 엔티티에 없는 테이블을 지우는 `DROP`이 생성된다.
5. 저장 폴더는 그 모듈의 `src/main/resources/db/migration`. IntelliJ가 제안하는 `src/main/generated`(어노테이션 처리용 폴더)가 아니다.
6. 버전 칸에 작성 시각(`yyyyMMddHHmm`)을 넣는다.

## 생성 후 확인

- [ ] **모듈의 첫 마이그레이션이면 맨 위에 `CREATE SCHEMA IF NOT EXISTS {스키마};`를 직접 넣는다 (예: `lectures`, `members`).** JPA에는 스키마 생성 표현이 없어 생성기가 넣어 주지 않는다. 없으면 빈 DB(테스트·서버)에서 실패한다.
- [ ] 다른 모듈 스키마의 테이블이나 `DROP` 문이 없다.
- [ ] `./gradlew :{모듈}:test`가 통과한다 (`validate`로 엔티티와 SQL 일치 확인).

## 엔티티로 할 것, SQL로 직접 할 것

| 엔티티로 표현 (생성기가 SQL로 옮김) | SQL을 직접 쓰거나 고침 |
| --- | --- |
| 테이블, 컬럼, 타입, 길이, NOT NULL | 스키마 생성 (모듈 첫 마이그레이션에 한 번) |
| PK, FK, 인덱스(`@Index`), 유일 제약 | 기존 행 값 채우기 (NOT NULL 컬럼 추가 시) |
| | 컬럼·테이블 이름 변경 (`RENAME`). 생성기는 삭제 후 추가로 만들어 데이터가 사라진다 |
| | 부분 인덱스, 지연 검사 제약 등 JPA로 표현할 수 없는 것 |

도메인 메서드나 검증 로직만 바뀌고 테이블 구조가 그대로면 마이그레이션은 필요 없다.

## 규칙

- **dev에 병합된 마이그레이션 파일은 고치지 않는다.** Flyway가 체크섬을 검사하므로 이미 적용된 서버에서 시작이 실패한다. 바꿀 내용은 새 파일로 추가한다. 병합 전(PR 진행 중)에는 고쳐도 된다.
- 엔티티를 바꾸는 PR에는 마이그레이션을 함께 넣는다. 빠뜨리면 `validate`에 걸려 테스트가 실패한다.
- 다른 모듈 스키마의 테이블을 만들거나 참조하지 않는다. 스키마 간 FK는 금지다 (AGENTS.md 3장).
- 돈이 오가는 데이터는 Hard Delete 하지 않는다. 컬럼·테이블 삭제 마이그레이션은 팀과 먼저 확인한다.

## 실행 시점

| 환경 | 실행 |
| --- | --- |
| local·dev·prod | 앱이 시작할 때 자동으로 실행한다 (Spring Boot Flyway 자동 설정) |
| 모듈 테스트 (`@RepositoryTest`, `@IntegrationTest`) | Testcontainers DB에 그 모듈의 마이그레이션만 실행한다 (클래스패스에 그 모듈 SQL만 있음) |
| `apps` 테스트 | 모든 모듈의 마이그레이션을 실행한다 |

이미 테이블(`event_publication` 등)이 있는 DB에 처음 적용할 때는 버전 0으로 baseline을 기록한 뒤 모든 마이그레이션을 실행한다(`baseline-on-migrate`).

## 로컬 DB 초기화

Flyway 도입 전에 `ddl-auto`로 테이블이 만들어진 로컬 DB는, 마이그레이션의 `CREATE TABLE`이 "이미 존재함"으로 실패한다. 로컬 DB를 한 번 초기화한다. **로컬 데이터가 모두 지워진다.**

```bash
docker compose -f docker/compose.yaml down -v
```
