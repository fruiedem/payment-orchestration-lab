# ADR 0003: Phase 1에서는 PG 호출을 DB transaction 내부에 유지한다

- 상태: Phase 1에 한해 승인
- 작성일: 2026-09-06

## 배경

이 Phase의 목적은 지나치게 넓은 transaction boundary가 만드는 실패 특성을 관찰하는 것이다.
PG 호출을 transaction 밖으로 옮기거나 outbox 또는 비동기 reconciliation을 도입하면 이번
Phase에서 확인해야 할 동작이 가려진다.

## 결정

하나의 `TransactionTemplate` 범위에 상품 행 잠금, 재고 차감, 주문 및 결제 insert,
동기식 `FakePaymentGateway.approve` 호출, 최종 상태 update를 모두 포함한다. 재고 행을
`FOR UPDATE`로 조회하므로 3초 또는 10초 동안 Fake PG 응답을 기다리는 내내 행 잠금과
Hikari에서 빌린 connection을 유지한다.

Timeout을 실패로 확정하지 않는다. 결제는 `UNKNOWN`으로 commit하고 주문은 `PENDING`으로
유지한다. PG가 HTTP 500을 반환하면 오류를 발생시켜 전체 transaction을 rollback한다.

## 결과

- DB constraint와 행 잠금으로 재고가 0 미만이 되는 것을 막는다.
- Partial unique index로 주문 하나에 `SUCCESS` 결제가 둘 이상 생기는 것을 막는다.
- 같은 SKU에 대한 요청은 외부 호출을 사이에 두고 직렬화된다.
- 느린 PG 응답이 transaction duration, lock wait, connection 점유 시간, request latency를 늘린다.
- Connection pool 고갈은 관련 없는 transaction의 connection 획득 실패를 유발할 수 있다.
- Phase 1에는 `UNKNOWN` convergence worker가 없으므로 상태가 무기한 남을 수 있다.

이 Phase에는 transaction boundary를 바로잡는 설계를 포함하지 않는다.
