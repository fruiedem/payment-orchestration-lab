# Payment Orchestration 학습 가이드

이 디렉터리는 현재 bootstrap 환경을 기준으로 2년차, 5년차, 10년차 개발자가
각각 무엇을 이해하고 설명하며 검증할 수 있어야 하는지 정리한다.

연차는 절대적인 실력 기준이 아니다. 여기서는 다음과 같은 책임 범위의 차이를
표현하기 위한 편의적인 구분으로 사용한다.

- 2년차: 주어진 구조를 이해하고 실행하며 문제를 진단한다.
- 5년차: 기능 단위의 설계와 데이터 정합성을 책임진다.
- 10년차: 시스템 전체의 불변식, 장애 복구, 운영 가능성을 책임진다.

## 문서 목록

- [2년차 개발자 학습 가이드](Phase0-02-year-developer.md)
- [2년차 완료 기준 상세 답안](Phase0-02-year-developer-detailed-answers.md)
- [5년차 개발자 학습 가이드](Phase0-05-year-developer.md)
- [10년차 개발자 학습 가이드](Phase0-10-year-developer.md)

## 현재 프로젝트의 출발점

현재 프로젝트는 결제 기능 자체가 아니라 이후 실험을 위한 실행 기반만 제공한다.

- Java 21과 Spring Boot 3.5.6
- PostgreSQL 17.11과 Flyway
- Docker Compose 기반 DB, application, integration test 실행
- Actuator health endpoint
- 실제 PostgreSQL을 사용하는 infrastructure integration test
- ADR, evidence, runbook, concurrency/failure/performance 디렉터리

현재 확인 대상으로 구현된 것은 애플리케이션 부팅, PostgreSQL 연결, V1 migration,
health endpoint다. Order, Inventory, Payment, Refund와 관련된 비즈니스 불변식은 아직
구현하거나 검증하지 않았다.

## 연차별 성장 축

| 구분 | 2년차 | 5년차 | 10년차 |
|---|---|---|---|
| 질문 | 어떻게 실행되는가 | 왜 이 경계와 방법을 선택했는가 | 어떤 실패에도 무엇을 보장해야 하는가 |
| 코드 | 계층과 설정 이해 | 기능과 transaction 설계 | 상태 모델과 시스템 경계 설계 |
| DB | SQL과 constraint 기초 | 동시성과 격리 수준 적용 | 불변식의 최종 방어선 설계 |
| 테스트 | 테스트 실행과 실패 분석 | 재현 가능한 통합·동시성 테스트 | correctness evidence와 회귀 전략 |
| 장애 | 로그로 원인 탐색 | timeout·중복·재시도 처리 | 불확실성·복구·상태 수렴 설계 |
| 운영 | health와 컨테이너 확인 | 지표·runbook 작성 | SLO·용량·복구 체계와 의사결정 |
| 문서 | 사용법을 정확히 기록 | 구현 판단과 증거를 기록 | 조직이 재사용할 원칙과 trade-off 기록 |

## 공통 학습 원칙

### 1. 주장과 증거를 구분한다

테스트 코드가 존재한다는 사실은 테스트가 통과했다는 증거가 아니다. 테스트를
실제로 실행하지 않았다면 통과했다고 표현하지 않는다. 성능 역시 측정하지 않은
수치를 추정해 보고하지 않는다.

### 2. 애플리케이션 검증과 DB 보장을 구분한다

애플리케이션 검증은 빠르고 친절한 오류를 제공한다. DB constraint는 우회 경로나
동시 요청이 있어도 최종 데이터를 보호한다. 중요한 불변식은 두 계층을 함께
검토한다.

### 3. timeout과 실패를 구분한다

외부 PG 요청의 timeout은 응답을 받지 못했다는 뜻이지 결제가 실패했다는 뜻이
아니다. 결과가 확정되지 않았다면 `UNKNOWN`과 같은 명시적인 상태를 사용하고
authoritative state를 다시 조회해야 한다.

### 4. 정상 경로만 테스트하지 않는다

중복 요청, 동시 요청, 지연된 webhook, 순서가 바뀐 이벤트, 프로세스 재시작,
DB 장애처럼 실제 운영에서 발생할 실패를 반복 가능하게 재현해야 한다.

### 5. 현재 Phase의 범위를 지킨다

다음 단계 기능을 미리 구현하지 않는다. 현재 단계에서 의도적으로 실패해야 하는
동작은 실패하도록 두고, 이를 테스트나 재현 스크립트로 고정한다.
