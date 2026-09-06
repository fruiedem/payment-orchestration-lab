# 5년차 개발자 학습 가이드

## 1. 핵심 목표

5년차 개발자의 핵심 목표는 하나의 기능을 설계하고 구현하면서 정상 경로뿐 아니라
동시성, 중복, 부분 실패와 운영까지 책임지는 것이다.

다음 능력을 목표로 한다.

- 비즈니스 규칙을 불변식과 상태 전이로 표현한다.
- application validation과 DB constraint를 함께 설계한다.
- transaction 범위와 isolation 전략을 근거를 들어 선택한다.
- 실제 PostgreSQL에서 경쟁 조건을 반복 가능하게 재현한다.
- timeout, 중복 요청, 재시도를 안전하게 처리한다.
- migration의 운영 영향과 배포 호환성을 검토한다.
- 지표, evidence, runbook까지 기능 완료 범위에 포함한다.

## 2. 왜 이것을 알아야 하는가

정상 요청 하나를 처리하는 코드는 비교적 쉽게 작성할 수 있다. 실제 장애는 동일한
데이터를 두 요청이 동시에 수정하거나, DB commit은 성공했지만 응답이 유실되거나,
외부 PG 결과를 알 수 없는 상황에서 발생한다.

5년차부터는 “코드가 읽기 좋다”뿐 아니라 다음 질문에 답해야 한다.

- 동시에 두 요청이 들어와도 데이터가 안전한가?
- 요청자가 응답을 받지 못하고 재시도하면 어떻게 되는가?
- application 인스턴스가 중간에 종료되면 어떤 상태가 남는가?
- 운영자가 문제를 어떻게 탐지하고 복구하는가?

## 3. 핵심 개념

### 3.1 불변식 중심 설계

불변식은 시스템이 어떤 상황에서도 깨뜨리면 안 되는 조건이다.

이 프로젝트의 대표 불변식은 다음과 같다.

- 재고는 0 미만이 되지 않는다.
- 하나의 주문에는 성공한 결제가 최대 하나만 존재한다.
- timeout이나 connection failure만으로 결제를 `FAILED`로 확정하지 않는다.
- 오래된 webhook이 최종 결제 상태를 이전 상태로 되돌리지 않는다.
- 중복 이벤트와 환불 요청의 비즈니스 효과는 한 번만 발생한다.

기능 구현 전 불변식을 문장으로 작성하고 각각을 어디에서 보장할지 결정한다.

| 보장 수단 | 역할 | 예시 |
|---|---|---|
| Application validation | 빠르고 친절한 거부 | 수량은 1 이상 |
| DB constraint | 최종 데이터 무결성 | `CHECK (quantity >= 0)` |
| Unique constraint | 동시 중복 방지 | 동일 idempotency key 한 번만 저장 |
| Transaction | 여러 변경의 원자성 | 주문 생성과 재고 예약 |
| 상태 전이 규칙 | 역행과 잘못된 변경 방지 | `SUCCEEDED → PENDING` 금지 |
| Reconciliation | 불확실 상태의 최종 수렴 | PG authoritative state 재조회 |

### 3.2 PostgreSQL MVCC와 격리 수준

PostgreSQL은 MVCC로 동시 transaction의 읽기와 쓰기를 관리한다. 기본 격리 수준인
`READ COMMITTED`에서는 SQL statement마다 새로운 snapshot을 볼 수 있다.

다음 패턴은 경쟁 조건에 취약하다.

```text
재고 조회
  → 애플리케이션에서 충분한지 확인
  → 재고 감소
```

두 transaction이 같은 재고를 읽고 모두 성공이라고 판단할 수 있다. 대안은 문제에
따라 다르다.

#### Conditional update

```sql
UPDATE payment_orchestration.inventory
SET quantity = quantity - :amount
WHERE product_id = :productId
  AND quantity >= :amount;
```

영향받은 행 수가 1인지 확인한다. 재고 감소처럼 간단한 조건에는 강하고 효율적이다.

#### Pessimistic lock

```sql
SELECT quantity
FROM payment_orchestration.inventory
WHERE product_id = :productId
FOR UPDATE;
```

복잡한 판단 전에 행을 잠글 수 있지만 lock 대기, 처리량 감소, deadlock 가능성을
관리해야 한다.

#### Optimistic lock

version을 조건으로 갱신하고 충돌 시 재시도한다. 충돌이 드문 환경에 적합하며,
무제한 재시도 대신 횟수와 backoff를 정해야 한다.

#### Serializable

모든 문제를 자동 해결하는 설정이 아니다. PostgreSQL이 직렬 실행과 모순되는
transaction을 실패시키면 애플리케이션이 전체 transaction을 재시도해야 한다.

### 3.3 Transaction boundary

Transaction은 짧고 명확해야 한다. 특히 DB transaction을 연 채로 외부 PG 호출을
기다리는 구조는 다음 위험이 있다.

- connection pool 점유
- row lock 장기 유지
- timeout 시 rollback과 외부 결과 불일치
- 처리량 저하와 연쇄 장애

DB transaction과 외부 시스템 작업은 하나의 ACID transaction으로 묶을 수 없다.
따라서 상태 기록, idempotency, outbox, reconciliation 같은 별도 패턴이 필요하다.
단, 현재 Phase의 요구가 없으면 다음 단계 패턴을 미리 구현하지 않는다.

### 3.4 Idempotency

Idempotency는 같은 요청을 반복해도 효과가 한 번만 발생하도록 하는 성질이다.

좋은 설계는 다음을 고려한다.

- idempotency key의 범위와 생성 주체
- 같은 key에 다른 payload가 들어올 때의 처리
- 처리 중, 성공, 실패, 불확실 상태 저장
- 여러 application 인스턴스의 동시 요청
- key 보존 기간
- DB unique constraint

“먼저 조회하고 없으면 insert”만 사용하면 두 transaction이 동시에 없다고 판단할
수 있다. 최종 중복 방지는 unique constraint와 충돌 처리까지 포함해야 한다.

### 3.5 결제 상태와 불확실성

외부 PG 호출 결과는 세 가지로 나누어 생각한다.

- 명확한 성공 응답: 성공 후보
- 명확한 거절 응답: 실패 후보
- timeout, connection reset, 응답 해석 실패: 결과 불명

결과 불명은 `FAILED`가 아니라 `UNKNOWN`으로 표현한다. 이후 PG 조회 API나 webhook으로
authoritative state를 확인하고 최종 상태로 수렴시킨다.

상태 전이는 코드 곳곳의 `if`문보다 명시적인 규칙으로 중앙화한다.

```text
PENDING → SUCCEEDED
PENDING → FAILED
PENDING → UNKNOWN
UNKNOWN → SUCCEEDED
UNKNOWN → FAILED
SUCCEEDED → PENDING  (금지)
```

실제 전이 집합은 해당 Phase의 요구사항과 PG 계약을 근거로 정의한다.

### 3.6 Webhook 처리

Webhook은 다음 조건을 기본 가정으로 둔다.

- 중복 전달될 수 있다.
- 지연될 수 있다.
- 순서가 바뀔 수 있다.
- 서명 검증에 실패할 수 있다.
- 처리 응답이 유실되어 PG가 재전송할 수 있다.

따라서 event ID unique constraint, 서명 검증, 처리 기록, 허용된 상태 전이, 원본
payload 보존과 민감정보 제거 정책을 검토한다.

### 3.7 Flyway와 호환 가능한 배포

Migration은 로컬에서 성공하는 것만으로 충분하지 않다.

- 큰 테이블 변경이 장시간 lock을 잡는가?
- 기존 데이터가 새 constraint를 만족하는가?
- 구버전 application이 새 schema와 함께 잠시 동작할 수 있는가?
- 새 application이 migration 완료 전 시작될 가능성이 있는가?
- rollback 시 DB를 실제로 되돌릴 것인가, application을 forward fix할 것인가?

안전한 변경은 보통 다음 순서를 사용한다.

```text
확장 가능한 schema 추가
  → 기존 데이터 backfill
  → application 읽기/쓰기 전환
  → 검증
  → 사용하지 않는 schema 제거
```

현재 V1은 application schema만 생성한다. 이후에는 Flyway history와 비즈니스 테이블이
어느 schema에 생성되는지 명시적으로 결정해야 한다.

### 3.8 Compose 재현성과 테스트 격리

현재 Compose는 실제 PostgreSQL을 제공하지만 named volume은 이전 상태를 유지한다.
따라서 다음 테스트를 구분한다.

- 기존 schema에 신규 migration을 적용하는 upgrade 테스트
- 빈 DB에서 모든 migration을 적용하는 bootstrap 테스트
- 각 테스트 데이터가 격리된 business integration test

현재 test service는 Gradle 8.14.5 image의 `gradle test`를 사용하고 wrapper 설정은
Gradle 8.14다. 재현성 기준을 정할 때 컨테이너 Gradle과 wrapper 중 어느 것을
기준으로 할지 통일하는 것이 좋다.

### 3.9 Health, metric, log

Health를 세 가지 관점으로 나눈다.

- Liveness: 프로세스를 재시작해야 하는가?
- Readiness: 지금 요청을 받아도 되는가?
- Business health: 불확실 결제나 reconciliation backlog가 위험한가?

외부 PG 장애를 liveness 실패로 연결하면 불필요한 재시작이 반복될 수 있다.
외부 의존성 상태는 readiness나 별도 운영 지표로 표현하는 것이 일반적으로 낫다.

로그에는 correlation ID, order ID, payment ID, idempotency key처럼 흐름을 추적할
식별자가 필요하다. 카드 정보, token, credential 같은 민감정보는 남기지 않는다.

### 3.10 Evidence와 runbook

기능 완료에는 코드와 테스트 외에도 다음이 포함된다.

- 정확한 실행 명령
- 실행 환경과 소스 revision
- 테스트 결과와 반복 횟수
- 경쟁 조건 재현 결과
- 알려진 한계
- 운영 탐지와 복구 절차

Runbook은 “로그를 확인한다”보다 구체적이어야 한다. 어떤 지표와 query를 보고,
어떤 상태라면 무슨 작업을 하며, 정상화는 어떻게 확인하는지 기록한다.

## 4. 상세 설계 및 검증 방법

### 4.1 기능 구현 전 작성할 문서

다음 순서로 한 페이지 안에 정리한다.

1. 사용 사례와 입력·출력
2. 보장해야 할 불변식
3. 상태와 허용된 상태 전이
4. transaction 경계
5. DB constraint와 index
6. 중복·timeout·재시도 동작
7. 관측할 log와 metric
8. acceptance criteria

### 4.2 동시성 테스트를 결정적으로 만들기

단순히 thread를 많이 실행하고 가끔 실패하기를 기대하지 않는다.

```text
Transaction A가 값을 읽음
  → barrier에서 대기
Transaction B도 같은 값을 읽음
  → 두 transaction을 정해진 순서로 진행
  → commit 결과와 불변식 확인
```

각 작업은 별도 transaction과 별도 DB connection을 사용해야 한다. 실패 재현
테스트와 수정 후 방지 테스트를 모두 남긴다.

### 4.3 Failure test 설계

PG 호출을 예로 들면 실패 지점을 분리한다.

- 연결 전 실패
- 요청 전송 중 연결 종료
- PG 처리 후 응답 유실
- 느린 응답으로 client timeout
- 잘못된 응답 형식
- 조회 API도 일시적으로 실패

각 지점에서 local payment 상태, 재시도 가능성, 중복 효과, reconciliation 동작을
검증한다.

### 4.4 성능 테스트 설계

TPS 하나만 기록하지 않는다.

- p50, p95, p99 latency
- 성공률과 오류 유형
- DB connection pool 사용량
- lock wait와 deadlock
- CPU, memory, GC
- 입력 데이터 분포
- warm-up과 측정 시간
- 테스트 종료 후 불변식 검사

측정 조건과 결과를 함께 보관해야 다음 변경과 비교할 수 있다.

### 4.5 코드 리뷰 질문

- 동일 요청이 동시에 두 번 오면 어떻게 되는가?
- 조회와 수정 사이에 다른 transaction이 개입할 수 있는가?
- DB constraint 없이 application code만 믿고 있지는 않은가?
- 외부 호출 timeout을 실패로 단정하지 않는가?
- 예외를 잡은 뒤 transaction이 의도치 않게 commit되지 않는가?
- 상태 전이가 이전 상태로 역행할 수 있는가?
- 테스트가 실제 PostgreSQL의 동작을 검증하는가?
- 장애 후 운영자가 상태를 확인하고 복구할 수 있는가?

## 5. 완료 기준

하나의 Phase를 맡았을 때 다음 산출물을 만들고 설명할 수 있어야 한다.

- Phase가 보장하는 불변식 목록
- schema와 constraint 설계
- transaction과 상태 전이 설계
- 정상·중복·동시성·timeout 테스트
- 반복 가능한 reproduction script
- 실제 실행 결과 evidence
- 장애 확인과 복구 runbook
- 아직 보장하지 않는 항목
- 다음 Phase에서 해결할 문제

핵심 기준은 다음과 같다.

> 정상 동작을 구현하는 데서 끝내지 않고, 동시 실행과 부분 실패가 있어도 데이터가
> 왜 안전한지 코드, DB constraint, 테스트 결과로 설명할 수 있어야 한다.

