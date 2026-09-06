# Phase 1 증거: DB transaction 내부의 느린 PG 호출

작성일: 2026-09-06 (Asia/Seoul)

## 실제 실행한 명령

```powershell
& 'C:\Users\fruie\.gradle\wrapper\dists\gradle-8.14-bin\38aieal9i53h9rfe7vjup95b9\gradle-8.14\bin\gradle.bat' testClasses --no-daemon
docker compose --env-file .env.example --profile test run --build --rm test
docker compose --env-file .env.example up -d --build application
.\scripts\reproduce\phase1-slow-pg-lock.ps1
```

## Integration test 결과

최종 Docker 테스트는 `BUILD SUCCESSFUL`로 끝났다. 총 7개 테스트가 실행됐고 실패는 없었다.
Phase 1 테스트 5개는 11.479초가 걸렸다. 같은 SKU에 재고 2개를 두고 실행한 `DELAY_3S`
테스트는 6.165초가 걸렸다. 같은 SKU에 재고 1개를 둔 테스트에서는 음수 재고 없이 요청
하나가 성공하고 하나가 conflict로 끝났다. NORMAL 승인과 HTTP 500 rollback도 assertion과
일치했으며, timeout은 `FAILED`가 아닌 `UNKNOWN`으로 저장됐다.

## 10초 지연 재현 결과

초기화된 측정 환경에서 두 요청을 동시에 실행한 결과는 다음과 같다.

- Request latency: 10,203ms, 19,954ms
- Request latency 지표: count 2, total 29.859초, max 19.798초
- Transaction duration 지표: count 2, total 29.831초, max 19.795초
- 최대 active DB connection: 2개
- 최대 pending DB connection: 0개
- 최대 PostgreSQL lock waiter: 1개
- 전체 17개 sample 중 8개에서 lock waiter 관찰

원본 local sample은 `build/phase1-evidence/phase1-20260906-225454.csv`에 생성됐다.
이 파일은 build 결과물이므로 source control 대상이 아니다. 두 번째 요청은 첫 transaction의
상품 행 잠금이 풀리기를 기다린 후 자신의 PG 호출을 실행했다. 그 결과 latency가 설정한
PG 지연의 거의 두 배가 됐다.

## 확인된 실패 특성

외부 시스템의 latency가 DB transaction duration과 request latency를 직접 늘린다.
Transaction은 DB 작업을 수행하지 않고 PG 응답을 기다리는 동안에도 connection과 행 잠금을
계속 점유한다. 동일 상품에 대한 동시 작업은 직렬화되므로 대기열이 길어질수록 latency가
누적된다.

이 Evidence는 의도적으로 해결책을 제안하거나 구현하지 않는다.

## Connection pool 압력 재현 결과

같은 상품에 `DELAY_3S` 요청 6개를 동시에 실행했다. 성공한 요청의 latency는 각각
3,190ms, 5,864ms, 7,421ms, 11,540ms, 14,172ms, 16,809ms였다.
전체 15개 sample에서 다음 결과를 관찰했다.

- Active connection은 설정된 최대치인 5개에 도달했다.
- Pending connection 획득 요청은 2개 sample에서 1개까지 증가했다.
- PostgreSQL lock waiter는 최대 4개에 도달했다.
- 12개 sample에서 lock waiter가 존재했다.

이 결과는 connection pool 압력과 행 잠금 대기열을 모두 보여 준다. 이번 실행에서는
connection timeout이 발생하지 않았으므로 timeout이 발생했다고 주장하지 않는다.
