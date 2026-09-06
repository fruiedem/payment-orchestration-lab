# Failure tests

`Phase1ConcurrentOrderIntegrationTest` fixes the following Phase 1 behaviors:

- Fake PG timeout persists payment `UNKNOWN`, never `FAILED`.
- Fake PG HTTP 500 rolls back the deliberately broad transaction.
- Same-product concurrency queues on the locked inventory row.

There is intentionally no retry, reconciliation, or transaction-boundary correction yet.

향후 PG timeout, connection failure, delayed webhook 등 실패 시나리오를 둔다. 현재 단계에서는 비어 있다.
