# Phase 1 구현 보고서

작성일: 2026-09-06

## 결과

Phase 1에서 요구한 의도적으로 잘못된 동기식 흐름을 구현했다. 재고 행 잠금, 재고 차감,
주문 및 결제 생성, 외부 시스템 역할의 Fake PG 호출, 결제 상태 확정을 모두 하나의 DB
transaction 안에서 수행한다. Transaction boundary 개선, 재시도, outbox, reconciliation은
추가하지 않았다.

## 변경 파일

- `build.gradle`: Prometheus registry를 추가했다.
- `application.yml`: connection pool 크기를 5로 설정하고 지표 endpoint를 노출했다.
- `V2__phase1_order_inventory_payment.sql`: 상품, 주문, 결제 테이블과 constraint를 추가했다.
- `order/*`: HTTP API와 넓은 transaction 범위를 가진 service를 구현했다.
- `payment/*`: 동작을 제어할 수 있는 Fake PG와 오류 타입을 구현했다.
- `observability/RequestLatencyFilter.java`: request latency timer를 추가했다.
- `Phase1ConcurrentOrderIntegrationTest.java`: 실제 PostgreSQL을 사용하는 API 동시성 및 실패 테스트를 추가했다.
- `scripts/reproduce/phase1-slow-pg-lock.ps1`: 동시 요청과 DB connection/lock sampling script를 추가했다.
- README, ADR 0003, Evidence, Runbook과 테스트 문서를 갱신했다.

기존 Phase 0 lesson, evidence, report의 working tree 변경은 보존했으며 Phase 1 작업에서
수정하지 않았다.

## 설계 이유

`SELECT ... FOR UPDATE`와 DB 재고 검사를 함께 사용해 재고가 음수가 되지 않는 불변식을
보호하면서 경합을 관찰할 수 있게 했다. `TransactionTemplate`을 사용해 Fake PG 호출이 같은
transaction 안에 있다는 사실을 명시적으로 드러냈다. Partial unique index는 application
검사와 독립적으로 주문당 성공한 결제가 최대 하나라는 불변식을 보호한다.

Timeout은 transaction 안에서 잡아 `UNKNOWN`으로 저장하며 `FAILED`로 변환하지 않는다.
HTTP 500은 전체 transaction을 rollback한다. 두 동작 모두 의도적으로 최소한만 구현했으며,
완전한 결제 생명주기를 제공하지 않는다.

## 실행한 명령과 결과

- 로컬 Gradle `testClasses --no-daemon`: compile 성공.
- 첫 로컬 `gradlew testClasses`: 설정된 Gradle home이 쓸 수 없는 `C:\.gradle`로 해석되어
  build 시작 전에 실패했다. 이 실행에 대해서는 테스트 결과를 주장하지 않는다.
- 이후 직접 실행한 로컬 `gradle test`: `native-platform.dll`을 불러오지 못해 실패했다.
  문서화된 재현 가능 환경인 Docker에서 테스트를 실행했다.
- `docker compose --env-file .env.example --profile test run --build --rm test`: 최종 실행은
  `BUILD SUCCESSFUL`, 총 7개 테스트, 실패 0개였다. Phase 1 테스트 5개는 11.479초가 걸렸다.
- `docker compose --env-file .env.example up -d --build application`: 성공.
- 기본 재현(요청 2개, `DELAY_10S`): 성공. Latency는 10.203초와 19.954초였고,
  최대 transaction duration은 19.795초, 최대 active connection은 2개,
  최대 lock waiter는 1개였다.
- Connection pool 압력 재현(요청 6개, `DELAY_3S`): 성공. 최대 active connection은 5개,
  최대 pending connection은 1개, 최대 lock waiter는 4개, 최대 request latency는
  16.809초였다.

## Phase 1에서 보장하는 불변식

- 재고가 0 미만으로 내려가지 않는다: DB check constraint와 직렬화된 행 잠금으로 보장한다.
- 주문 하나에 성공한 결제가 최대 하나만 존재한다: partial unique DB index로 보장한다.
- Timeout을 최종 실패로 확정하지 않는다: 결제를 `UNKNOWN`, 주문을 `PENDING`으로 저장한다.

이 불변식을 application validation만으로 보장한다고 주장하지 않는다. 앞의 두 항목은 DB가
명시적으로 강제한다. 수량과 금액에도 DB check constraint가 있다.

## 아직 보장하지 않는 것

- 재시작 또는 장애 이후 `UNKNOWN`이나 `PENDING` 상태의 수렴
- PG의 authoritative state와 로컬 상태의 reconciliation
- 주문, webhook, event, 환불의 멱등 처리
- 오래된 webhook에 의한 상태 역행 방지
- PG 승인은 성공했지만 DB commit이 실패한 경우의 복구
- Connection pool 고갈 또는 긴 lock 대기열 상황에서의 가용성
- 복수 instance 환경의 correctness

## 다음 Phase에서 해결할 문제(미구현)

결제와 재고의 correctness를 잃지 않으면서 관찰된 transaction boundary를 다시 검토해야 한다.
결과가 불명확한 PG 요청에는 durable reconciliation이 필요하다. 멱등성, 상태 전이 규칙,
crash recovery는 각각의 acceptance criteria를 정한 뒤 다음 Phase에서 구현해야 한다.
