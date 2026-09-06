# 10년차 개발자 학습 가이드

## 1. 핵심 목표

10년차 개발자의 핵심 목표는 개별 기능보다 시스템 전체가 장기간 올바르게
운영되도록 기술적 방향과 검증 체계를 만드는 것이다.

다음 책임을 목표로 한다.

- 비즈니스 불변식을 시스템 설계의 최상위 기준으로 둔다.
- 불확실성과 부분 실패를 정상적인 상태로 모델링한다.
- application, DB, 외부 PG 사이의 일관성 한계와 수렴 방법을 설계한다.
- 복수 인스턴스와 재시작 이후에도 correctness가 유지되는지 검증한다.
- migration, 배포, 복구, 관측, 보안과 용량을 함께 판단한다.
- 불필요한 분산 기술 도입을 막고 실제 문제에 비례한 구조를 선택한다.
- 팀이 같은 판단을 반복할 수 있도록 ADR, evidence, runbook 체계를 운영한다.

## 2. 왜 이것을 알아야 하는가

결제 시스템의 위험은 단순 코드 오류만이 아니다. 외부 PG는 독립적으로 상태를
변경하고, 네트워크는 응답을 유실하며, webhook은 중복되거나 순서가 바뀐다. DB
commit 직후 프로세스가 종료될 수도 있고 여러 인스턴스가 같은 작업을 동시에
처리할 수도 있다.

이 환경에서는 완벽한 동시 원자성을 가정할 수 없다. 따라서 다음을 설계해야 한다.

- 무엇을 즉시 강하게 일관되게 만들 것인가?
- 무엇은 일시적인 불일치를 허용할 것인가?
- 불일치는 어떤 authoritative source를 기준으로 수렴할 것인가?
- 수렴이 멈췄음을 어떻게 탐지할 것인가?
- 자동 복구가 불가능할 때 사람이 안전하게 개입할 방법은 무엇인가?

## 3. 핵심 개념

### 3.1 불변식과 authoritative state

시스템 구성 요소마다 책임지는 사실을 명확히 한다.

- 로컬 DB는 주문, 처리 이력, idempotency 기록의 근거가 된다.
- PG는 실제 승인·취소·환불 결과의 authoritative source가 될 수 있다.
- webhook은 사실 자체가 아니라 PG 상태를 전달하는 하나의 통신 수단이다.
- timeout은 결과가 아니라 관측 실패다.

불변식마다 위반 경로와 방어 계층을 작성한다.

| 불변식 | 주요 위반 경로 | 우선 방어 수단 |
|---|---|---|
| 재고는 음수가 아님 | 동시 차감 | conditional update, check constraint |
| 주문당 성공 결제 최대 하나 | 중복 요청, retry race | unique constraint, idempotency |
| timeout을 실패로 확정하지 않음 | 응답 유실 | `UNKNOWN`, PG 재조회 |
| 최종 상태가 역행하지 않음 | 오래된 webhook | 단조로운 상태 전이, event version |
| 환불 효과는 한 번만 발생 | client와 worker 재시도 | idempotency key, unique constraint |
| 상태가 최종적으로 수렴 | 재시작, webhook 유실 | durable job, reconciliation |

Application code 하나에 모든 보장을 맡기지 않는다. 가능한 불변식은 DB constraint,
unique index, transaction boundary, 상태 전이 규칙과 중복해서 방어한다.

### 3.2 상태 머신과 단조성

결제 상태는 단순 enum이 아니라 업무 상태 머신이다. 각 전이는 다음 정보를 가져야
한다.

- 허용되는 시작 상태와 도착 상태
- 전이를 발생시킨 명령 또는 외부 사실
- 중복 시 결과
- 더 오래된 이벤트를 판단하는 기준
- transaction 안에서 함께 기록할 audit 정보
- 전이 실패 시 후속 처리

최종 상태가 이전 상태로 역행하지 않도록 상태의 우선순위만 비교하는 방식은 주의가
필요하다. 환불처럼 별도의 lifecycle이 생기면 단일 선형 순서가 맞지 않을 수 있다.
Payment와 Refund의 책임을 분리하고 각 상태 머신의 관계를 명시한다.

### 3.3 Exactly-once라는 표현의 경계

네트워크를 사이에 둔 전체 과정을 손쉽게 exactly-once로 만들 수 있다고 약속하지
않는다. 실무에서는 보통 다음을 조합한다.

- at-least-once delivery
- durable event 또는 command 기록
- idempotent consumer
- unique constraint
- 상태 전이 검증
- 재처리 가능한 worker
- reconciliation

목표는 메시지가 정확히 한 번 전달되는 것이 아니라, 중복 전달되어도 비즈니스
효과가 한 번만 발생하는 것이다.

### 3.4 Local transaction과 외부 효과

DB commit과 PG 요청을 하나의 원자적 transaction으로 만들 수 없다. 가능한 실행
순서마다 crash point를 분석한다.

```text
로컬 상태 기록
  → PG 요청
  → PG 성공
  → 로컬 최종 상태 기록
```

각 화살표 사이에서 프로세스가 종료될 수 있다. 요청 전 상태를 durable하게 남기고,
PG idempotency 기능을 활용하며, 불명확한 결과는 `UNKNOWN`으로 두고, 재시작 후
worker가 계속 처리하도록 설계한다.

Outbox 역시 만능 원자성 도구가 아니다. 로컬 DB 변경과 event publish 의도를 같은
transaction에 기록해 누락을 막지만, broker publish 중복은 consumer idempotency로
처리해야 한다.

### 3.5 Reconciliation

Reconciliation은 예외적인 수동 작업이 아니라 장기간의 정합성을 보장하는 핵심
기능이다.

다음 요소를 정의한다.

- 스캔 대상: `PENDING`, `UNKNOWN`, 오래된 처리 중 상태
- 조회 주기와 최대 처리 지연
- PG API rate limit과 batch 크기
- 재시도 횟수, backoff, jitter
- 복수 worker의 중복 실행 제어
- authoritative state와 불일치 시 전이 규칙
- 자동 해결 불가능한 항목의 dead-letter 또는 수동 검토
- backlog age와 수렴 시간 metric

정상 여부는 backlog 개수뿐 아니라 가장 오래된 항목의 age와 최종 상태 도달 시간을
함께 본다.

### 3.6 PostgreSQL을 이용한 correctness

기술 선택은 불변식과 contention 특성에 맞춰야 한다.

- constraint로 표현 가능한 불변식은 constraint로 보호한다.
- 단일 행 조건 변경은 conditional update를 우선 검토한다.
- lock이 필요하면 획득 순서를 통일해 deadlock 가능성을 줄인다.
- deadlock과 serialization failure는 전체 transaction 재시도 대상으로 설계한다.
- partial unique index가 상태 조건부 유일성을 표현할 수 있는지 검토한다.
- index는 조회 성능뿐 아니라 lock 범위, 쓰기 비용, storage를 함께 평가한다.

Isolation level을 높이는 결정에는 실제 anomaly, 부하 특성, 재시도 비용에 대한
근거가 있어야 한다. 모든 transaction을 무조건 `SERIALIZABLE`로 바꾸는 것은 설계
대신 비용을 전가할 수 있다.

### 3.7 무중단 schema 변경

DB migration과 application 배포는 별도 시점에 실패할 수 있다. 다음 호환성 구간을
검토한다.

```text
구버전 application + 확장된 schema
신버전 application + 전환 중인 schema
복수 버전 application의 동시 실행
```

대용량 변경에는 expand-and-contract를 적용하고 다음을 evidence로 확인한다.

- 예상 lock 수준과 지속 시간
- table rewrite 여부
- backfill batch 크기와 부하
- replication 또는 backup 영향
- 실패 후 재실행 가능성
- forward fix와 rollback 기준

### 3.8 복수 인스턴스

처음부터 MSA를 도입할 필요는 없지만 동일 application의 복수 인스턴스에서는
다음 문제가 이미 발생한다.

- in-memory lock이 인스턴스 간 공유되지 않음
- scheduler가 중복 실행됨
- 같은 webhook을 여러 인스턴스가 처리함
- cache와 local state가 서로 다름
- 배포 중 구버전과 신버전이 동시에 동작함

분산 lock을 먼저 도입하기보다 DB unique constraint, atomic update, lease, `SKIP
LOCKED` 같은 현재 저장소의 일관성 수단으로 해결 가능한지 검토한다.

### 3.9 장애 모델과 테스트 전략

“서버 장애”라는 하나의 표현으로 묶지 않고 crash point를 구체화한다.

- DB commit 직전과 직후 프로세스 종료
- PG가 처리하기 전 연결 실패
- PG가 처리한 뒤 응답 유실
- webhook 저장 전과 저장 후 종료
- outbox claim 직후 worker 종료
- reconciliation 조회 중 일부 항목 실패
- connection pool 고갈
- DB lock timeout과 deadlock

테스트 계층은 목적에 따라 나눈다.

- 순수 상태 전이와 계산: 빠른 단위 테스트
- SQL, constraint, transaction: 실제 PostgreSQL integration test
- 경쟁 순서: deterministic concurrency test
- 프로세스와 네트워크 장애: failure injection 또는 reproduction script
- 장시간 수렴과 복수 인스턴스: system scenario test
- 용량과 tail latency: 조건이 기록된 performance test

테스트가 성공 상태만 검사하지 않고 종료 후 모든 불변식을 query로 검증하게 한다.

### 3.10 관측 가능성과 SLO

운영자가 코드 없이도 시스템 상태를 판단할 수 있어야 한다.

주요 관측 대상은 다음과 같다.

- 결제 시도와 결과 상태별 수
- `PENDING`과 `UNKNOWN`의 age 분포
- PG 호출 latency와 결과 유형
- webhook 중복·서명 실패·처리 지연
- reconciliation backlog와 수렴 시간
- DB connection pool, lock wait, deadlock
- idempotency 충돌과 중복 방지 횟수

Technical health와 business health를 분리한다. 프로세스가 `UP`이어도 UNKNOWN 결제가
장시간 쌓이면 서비스는 업무적으로 건강하지 않다.

SLO는 측정 가능한 사용자 결과로 정의하고 alert는 사용자가 영향을 받거나 곧 받을
상황에 연결한다. 모든 일시적 오류를 즉시 paging 대상으로 만들지 않는다.

### 3.11 Runbook과 안전한 운영 개입

수동 상태 변경은 위험하다. Admin/Operations 기능에는 다음이 필요하다.

- 권한 분리와 강한 인증
- 변경 전 현재 local/PG 상태 재확인
- 중복 실행 안전성
- 변경 사유와 작업자 audit
- dry-run 또는 영향 범위 미리보기
- 실행 후 검증 query
- 대량 작업의 속도 제한과 중단 방법

Runbook은 탐지, 진단, 완화, 복구, 검증, escalation 순서로 작성하고 정기적으로
실행해 낡은 명령과 dashboard link를 발견한다.

### 3.12 성능과 용량 계획

평균 처리량보다 tail latency와 포화 지점을 본다.

- 요청량과 결제 성공·실패 비율
- Hikari pool과 PostgreSQL `max_connections`
- transaction 시간과 lock 대기
- PG latency 증가 시 connection 점유
- retry amplification
- reconciliation이 정상 트래픽과 경쟁하는 정도
- 장애 후 backlog를 해소하는 catch-up capacity

Virtual thread는 대기 thread 비용을 줄일 수 있지만 DB connection, PG rate limit,
CPU와 lock contention을 늘려주지는 않는다. 측정 없이 활성화 여부나 성능 수치를
결정하지 않는다.

### 3.13 보안과 감사

결제 시스템은 개인정보와 credential 노출 위험이 크다.

- 카드 정보와 민감 token을 로그에 남기지 않는다.
- secret은 이미지, Git, `.env.example`에 포함하지 않는다.
- webhook signature를 검증하고 replay 위험을 고려한다.
- 운영 변경은 누가, 언제, 왜 수행했는지 감사 가능해야 한다.
- Actuator와 admin endpoint의 네트워크 접근과 인증을 분리한다.
- 의존성, container image, SBOM과 취약점 대응 절차를 관리한다.

### 3.14 아키텍처 도입 기준

현재 기본 구조는 하나의 application과 하나의 PostgreSQL을 사용하는 Modular
Monolith다. 서비스 분리, Kafka, Redis distributed lock, Kubernetes, Saga framework는
구체적인 문제 없이 학습 목적만으로 먼저 도입하지 않는다.

도입 전 다음을 ADR로 남긴다.

- 지금 관찰된 문제가 무엇인가?
- 단순한 transaction, constraint, scheduler로 해결할 수 없는가?
- 새 기술이 추가하는 실패 모드와 운영 비용은 무엇인가?
- 성공 여부를 어떤 수치와 실험으로 검증할 것인가?
- 제거하거나 되돌릴 수 있는가?

## 4. 상세 실행 방법

### 4.1 Phase 설계 검토

각 Phase 시작 전에 다음 순서로 review한다.

1. 사용자 시나리오와 acceptance criteria를 확정한다.
2. 관련 불변식과 authoritative source를 정한다.
3. 상태 머신과 허용 전이를 작성한다.
4. 각 crash point에서 남는 local/remote 상태를 분석한다.
5. DB constraint, index, transaction boundary를 설계한다.
6. 중복, timeout, 순서 역전, 재시작 테스트를 정의한다.
7. 관측 지표와 runbook 항목을 정의한다.
8. 이번 Phase에서 의도적으로 보장하지 않을 범위를 기록한다.

### 4.2 Correctness evidence 작성

Evidence에는 최소한 다음을 포함한다.

- source revision과 실행 일시
- Java, Gradle, PostgreSQL, image 버전
- 정확한 실행 명령
- 초기 DB 상태
- 테스트 시나리오와 반복 횟수
- 원본 또는 요약 로그
- 종료 후 불변식 검증 query와 결과
- 실패한 경우 원인과 미해결 여부

성능 evidence에는 부하 생성 조건, warm-up, 측정 구간, latency percentile, 오류율,
자원 사용량을 추가한다.

### 4.3 ADR 작성

ADR은 다음 구조를 권장한다.

```text
Context
Decision drivers
Considered alternatives
Decision
Consequences
Validation plan
Revisit conditions
```

이전 ADR이 더 이상 유효하지 않으면 삭제하거나 조용히 수정하지 말고 새 ADR에서
대체 관계를 남긴다.

### 4.4 운영 준비 검토

Phase 완료 전에 다음 질문에 답한다.

- 배포 중 구버전과 신버전이 공존해도 안전한가?
- application이 재시작되면 진행 중 작업은 누가 다시 찾는가?
- PG와 local state가 다르면 어느 쪽을 기준으로 어떻게 수렴하는가?
- backlog가 멈추면 어떤 metric과 alert가 알려 주는가?
- 운영자가 실행할 안전한 조회와 복구 절차가 있는가?
- 복구 작업 자체가 중복 실행되어도 안전한가?
- 테스트와 evidence가 실제 PostgreSQL과 복수 인스턴스를 다루는가?

## 5. 완료 기준

10년차 수준의 완료는 기능 코드가 merge되는 시점만을 뜻하지 않는다. 다음을
충족해야 한다.

- 불변식과 비보장 범위가 명시돼 있다.
- DB와 application의 방어 책임이 구분돼 있다.
- 모든 허용 상태 전이와 역행 방지가 정의돼 있다.
- timeout과 불확실 상태의 수렴 경로가 있다.
- 중복 요청, webhook, refund가 한 번의 효과만 만든다.
- 재시작과 복수 인스턴스에서도 처리가 계속된다.
- deterministic test 또는 반복 가능한 재현 스크립트가 있다.
- 실제 실행 evidence와 운영 runbook이 있다.
- 성능과 용량 수치는 실행 결과에 근거한다.
- 다음 기술 도입은 관찰된 문제와 trade-off로 정당화된다.

핵심 기준은 다음과 같다.

> 장애를 없다고 가정하지 않고, 장애와 불확실성이 발생해도 핵심 불변식이 유지되고
> 시스템이 최종 상태로 수렴한다는 것을 설계와 증거로 보여 줄 수 있어야 한다.

