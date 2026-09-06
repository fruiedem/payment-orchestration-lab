# Phase 1 느린 PG 관찰 Runbook

## 실행

```powershell
docker compose --env-file .env.example up -d --build application
```

`http://localhost:8080/actuator/health`가 `UP`을 반환하는지 확인한다.

## 행 잠금으로 인한 지연 증폭 재현

```powershell
.\scripts\reproduce\phase1-slow-pg-lock.ps1
```

예상되는 잘못된 동작은 첫 요청이 약 10초, 두 번째 요청이 약 20초 걸리는 것이다.
`build/phase1-evidence/*.csv`에서 `lock_waiters` 값이 0보다 큰 sample을 확인한다.

## Connection pool 압력 재현

```powershell
.\scripts\reproduce\phase1-slow-pg-lock.ps1 -GatewayMode DELAY_3S -ConcurrentRequests 6
```

설정된 connection pool 크기는 5다. 생성된 CSV에서 active connection과 pending connection을
확인한다. 요청이 설정된 5초의 connection 획득 timeout 이후 실패할 수 있다. 이 실패는
이번 Phase에서 수집할 증거이며 여기서 해결할 장애가 아니다.

## 실시간 지표 확인

- `/actuator/metrics/payment.request.latency`
- `/actuator/metrics/payment.transaction.duration`
- `/actuator/metrics/hikaricp.connections.active`
- `/actuator/metrics/hikaricp.connections.pending`
- `/actuator/prometheus`

현재 lock wait를 확인하려면 다음 query를 실행한다.

```sql
SELECT pid, state, wait_event_type, wait_event, query
FROM pg_stat_activity
WHERE datname = current_database() AND wait_event_type = 'Lock';
```

## 복구

설정한 Fake PG 지연이 끝날 때까지 기다린다. 실험 실행을 중단해야 한다면 application
container를 중지한 후 다시 시작한다. 모든 실험 데이터를 의도적으로 초기화하려는 경우가
아니면 PostgreSQL volume을 삭제하지 않는다.
