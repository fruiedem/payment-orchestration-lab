# Phase 1 단계별 실습 가이드

## 이 가이드의 목적

이 가이드는 Phase 1의 코드를 순서대로 직접 실행하며 다음 질문의 답을 찾는 실습 문서다.

- 주문, 재고, 결제는 어떤 순서로 처리되는가?
- DB constraint와 application validation은 무엇이 다른가?
- 같은 상품을 동시에 주문하면 왜 기다리는 요청이 생기는가?
- 외부 PG 호출을 DB transaction 안에서 실행하면 어떤 문제가 생기는가?
- Timeout을 왜 곧바로 결제 실패로 판단하면 안 되는가?
- Request latency, transaction duration, DB connection, lock wait를 어떻게 관찰하는가?

이번 Phase의 코드는 **일부러 잘못된 transaction boundary를 사용한다.** 실습 도중 PG 호출을
transaction 밖으로 옮기거나 outbox, retry, reconciliation 같은 해결책을 추가하지 않는다.
문제를 먼저 관찰하고 증거로 남기는 것이 학습 목표다.

## 전체 학습 순서

```text
환경 실행
  → DB 구조와 constraint 확인
  → Fake PG 동작 확인
  → 주문 transaction 코드 추적
  → API로 정상·지연·timeout·500 실험
  → 지표 확인
  → Integration test 실행
  → 같은 상품 동시 주문 재현
  → Connection pool 압력 재현
  → 결과와 미보장 범위 정리
```

## 준비 사항

- Docker Engine 또는 Docker Desktop
- Docker Compose v2
- PowerShell
- 이 저장소의 root directory에서 명령 실행

로컬 Java와 Gradle은 필수가 아니다. 테스트는 Docker의 Gradle과 Java를 사용한다.

---

## Step 1. PostgreSQL과 application 실행하기

### 목표

Phase 1 application과 실제 PostgreSQL을 실행하고, 실습할 준비가 됐는지 확인한다.

### 예상 결과

- `postgres` container가 `healthy` 상태가 된다.
- `application` container가 `healthy` 상태가 된다.
- Health endpoint가 `{"status":"UP"}`을 반환한다.
- Flyway가 V1과 V2 migration을 적용한다.

### 결과를 통해 알아야 할 것

- 이 프로젝트의 integration test는 memory DB나 mock DB가 아닌 PostgreSQL을 사용한다.
- Flyway migration은 application이 시작할 때 DB 구조를 준비한다.
- Health가 `UP`이어야 이후 API와 지표 실습 결과를 믿을 수 있다.

### 실습 가이드

저장소 root에서 application을 build하고 실행한다.

```powershell
docker compose --env-file .env.example up -d --build application
```

Container 상태를 확인한다.

```powershell
docker compose --env-file .env.example ps
```

Health endpoint를 확인한다.

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
```

정상이라면 `status`가 `UP`이다. `DOWN`이거나 연결할 수 없다면 log부터 확인한다.

```powershell
docker compose --env-file .env.example logs application
docker compose --env-file .env.example logs postgres
```

---

## Step 2. DB table과 constraint 이해하기

### 목표

상품, 주문, 결제 table의 역할을 알고, 중요한 규칙을 application 코드뿐 아니라 DB에서도
지키는 이유를 이해한다.

### 예상 결과

- `products`, `orders`, `payments` table을 확인할 수 있다.
- 음수 재고를 직접 저장하면 DB가 거부한다.
- 하나의 주문에 `SUCCESS` 결제를 두 개 저장하면 partial unique index가 거부한다.

### 결과를 통해 알아야 할 것

- Application validation은 정상 API 요청을 빠르게 검사하고 친절한 오류를 주는 역할을 한다.
- DB constraint는 application bug나 다른 DB 접근 경로가 있어도 마지막 방어선으로 규칙을
  강제한다.
- 두 보호 장치는 서로 대신하는 것이 아니라 함께 사용할 수 있다.
- `CHECK (stock >= 0)`은 재고가 음수가 되지 않게 한다.
- `one_successful_payment_per_order` index는 주문 하나에 성공 결제가 최대 하나만 존재하게 한다.

### 실습 가이드

먼저 migration을 읽는다.

```powershell
Get-Content src/main/resources/db/migration/V2__phase1_order_inventory_payment.sql
```

PostgreSQL에서 table과 index를 확인한다.

```powershell
docker compose --env-file .env.example exec postgres psql -U payment_app -d payment_orchestration -c "\d payment_orchestration.products"
docker compose --env-file .env.example exec postgres psql -U payment_app -d payment_orchestration -c "\d payment_orchestration.orders"
docker compose --env-file .env.example exec postgres psql -U payment_app -d payment_orchestration -c "\d payment_orchestration.payments"
```

DB constraint가 음수 재고를 거부하는지 확인한다.

```powershell
docker compose --env-file .env.example exec postgres psql -U payment_app -d payment_orchestration -c "INSERT INTO payment_orchestration.products(sku, stock) VALUES ('negative-stock-practice', -1);"
```

`products_stock_non_negative` constraint 위반 오류가 예상 결과다. 이 명령이 실패해야 실습이
성공한 것이다. 실패한 SQL 한 문장만 취소되므로 application을 다시 시작할 필요는 없다.

> 주문당 성공 결제 unique index는 Step 5의 정상 주문으로 데이터를 만든 뒤 다시 확인한다.

---

## Step 3. FakePaymentGateway의 다섯 가지 동작 이해하기

### 목표

실제 외부 결제 회사 없이 정상, 지연, timeout, 서버 오류를 반복해서 만들 수 있는 Fake PG의
역할을 이해한다.

### 예상 결과

요청의 `gatewayMode` 값에 따라 다음 동작이 선택된다.

| Mode | Fake PG 동작 | Application이 관찰하는 결과 |
|---|---|---|
| `NORMAL` | 즉시 승인 | PG transaction ID 반환 |
| `DELAY_3S` | 3초 대기 후 승인 | 느린 성공 |
| `DELAY_10S` | 10초 대기 후 승인 | 매우 느린 성공 |
| `TIMEOUT` | 1초 뒤 timeout 발생 | 성공인지 실패인지 알 수 없음 |
| `ERROR_500` | 즉시 HTTP 500 성격의 오류 발생 | PG 오류로 처리 |

### 결과를 통해 알아야 할 것

- 장애 실습은 우연히 외부 서비스가 느려지기를 기다리지 않고 반복 가능해야 한다.
- Delay는 실패가 아니다. 늦었지만 승인이 도착할 수 있다.
- Timeout은 결제 실패를 뜻하지 않는다. 응답을 못 받았을 뿐 PG에서는 승인됐을 수도 있다.
- HTTP 500과 timeout은 서로 다른 상황이므로 같은 상태로 처리하면 안 된다.

### 실습 가이드

다음 파일을 열어 `switch (mode)`와 각 예외를 확인한다.

```powershell
Get-Content src/main/java/lab/payment/orchestration/payment/FakePaymentGateway.java
Get-Content src/main/java/lab/payment/orchestration/payment/PaymentGatewayException.java
Get-Content src/main/java/lab/payment/orchestration/payment/PaymentOutcomeUnknownException.java
```

코드를 읽으며 다음 질문에 답해 본다.

1. `TIMEOUT`과 `ERROR_500`은 같은 exception을 발생시키는가?
2. 지연 후 성공하면 어떤 모양의 PG transaction ID가 만들어지는가?
3. `InterruptedException`이 발생하면 thread의 interrupt 상태를 왜 다시 설정하는가?

정답은 각각 “아니다”, “`fake-pg-` 뒤에 UUID가 붙는다”, “상위 코드가 중단 사실을 알 수
있게 하기 위해서”다.

---

## Step 4. 일부러 넓게 만든 transaction 따라가기

### 목표

주문 처리에서 DB transaction이 어디서 시작하고 끝나는지, 그 안에서 어떤 자원을 계속
점유하는지 코드로 확인한다.

### 예상 결과

`OrderService.placeOrder`의 하나의 `TransactionTemplate` 안에서 다음 작업이 모두 실행된다.

```text
상품 행 SELECT ... FOR UPDATE
  → 재고 검사 및 차감
  → PENDING 주문 insert
  → PENDING 결제 insert
  → Fake PG 호출
  → 주문과 결제의 최종 상태 update
  → commit
```

### 결과를 통해 알아야 할 것

- `FOR UPDATE` 이후 transaction이 끝날 때까지 상품 행 잠금이 유지된다.
- Transaction이 끝날 때까지 Hikari connection도 반납되지 않는다.
- Fake PG가 10초 기다리면 DB 작업이 없는 10초 동안에도 잠금과 connection이 유지된다.
- 이 지점이 Phase 1에서 의도적으로 남긴 잘못된 transaction boundary다.

### 실습 가이드

`OrderService`를 읽고 아래 네 지점을 순서대로 찾는다.

```powershell
Get-Content src/main/java/lab/payment/orchestration/order/OrderService.java
```

1. `transactionTemplate.execute`
2. `SELECT ... FOR UPDATE`
3. `paymentGateway.approve`
4. `payment.transaction.duration` 기록

종이에 transaction 시작과 끝을 선으로 그리고, 그 선 안에 `paymentGateway.approve`가
들어가는지 표시해 본다. 이 호출을 밖으로 옮기면 어떻게 개선될지 생각할 수는 있지만,
Phase 1 코드에서는 옮기지 않는다.

---

## Step 5. API로 정상 승인과 DB 상태 확인하기

### 목표

상품 재고를 준비하고 정상 주문을 한 건 실행해, API 응답과 DB 상태가 어떻게 연결되는지
확인한다.

### 예상 결과

- 재고 준비 API가 성공한다.
- 주문 API가 HTTP 201을 반환한다.
- 응답의 주문 상태는 `PAID`다.
- 상품 재고는 2개에서 1개로 줄어든다.
- 주문은 `PAID`, 결제는 `SUCCESS`로 저장된다.

### 결과를 통해 알아야 할 것

- API 성공 응답만 보지 말고 DB에 어떤 상태가 commit됐는지 함께 확인해야 한다.
- 정상 경로에서는 재고 차감, 주문 저장, 결제 저장이 하나의 transaction으로 확정된다.
- `pg_transaction_id`는 Fake PG가 발급한 외부 결제 식별자 역할을 한다.

### 실습 가이드

상품 재고를 2개로 준비한다.

```powershell
$stock = @{ sku = 'phase1-normal-practice'; stock = 2 } | ConvertTo-Json
Invoke-RestMethod -Method Put -Uri http://localhost:8080/api/lab/stock -ContentType 'application/json' -Body $stock
```

정상 주문을 보낸다.

```powershell
$order = @{ sku = 'phase1-normal-practice'; quantity = 1; amount = 1000; gatewayMode = 'NORMAL' } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/orders -ContentType 'application/json' -Body $order
```

DB 상태를 확인한다.

```powershell
docker compose --env-file .env.example exec postgres psql -U payment_app -d payment_orchestration -c "SELECT sku, stock FROM payment_orchestration.products WHERE sku = 'phase1-normal-practice';"
docker compose --env-file .env.example exec postgres psql -U payment_app -d payment_orchestration -c "SELECT id, quantity, amount, status FROM payment_orchestration.orders ORDER BY id DESC LIMIT 1;"
docker compose --env-file .env.example exec postgres psql -U payment_app -d payment_orchestration -c "SELECT order_id, status, pg_transaction_id FROM payment_orchestration.payments ORDER BY id DESC LIMIT 1;"
```

주문당 성공 결제 constraint도 직접 확인할 수 있다. 아래 SQL은 방금 생성한 주문에 두 번째
`SUCCESS` 결제를 넣으려고 하므로 실패해야 한다.

```powershell
docker compose --env-file .env.example exec postgres psql -U payment_app -d payment_orchestration -c "INSERT INTO payment_orchestration.payments(order_id, status) SELECT id, 'SUCCESS' FROM payment_orchestration.orders ORDER BY id DESC LIMIT 1;"
```

`one_successful_payment_per_order` unique constraint 위반이 예상 결과다.

---

## Step 6. Timeout과 HTTP 500의 차이 실습하기

### 목표

결과를 알 수 없는 timeout과 명시적인 PG 오류가 local transaction에 서로 다른 결과를
만드는지 확인한다.

### 예상 결과

`TIMEOUT` 요청은 다음 결과를 만든다.

- HTTP 202 Accepted
- 주문 `PENDING`
- 결제 `UNKNOWN`
- 결제를 `FAILED`로 확정하지 않음

`ERROR_500` 요청은 다음 결과를 만든다.

- HTTP 502 Bad Gateway
- 전체 transaction rollback
- 줄였던 재고가 원래 값으로 복구됨
- 해당 요청의 주문과 결제가 저장되지 않음

### 결과를 통해 알아야 할 것

- “응답을 못 받음”과 “실패 응답을 받음”은 다르다.
- Timeout 직전 PG가 승인했을 가능성이 있으므로 `FAILED` 처리 후 다시 결제하면 이중 결제가
  생길 수 있다.
- `UNKNOWN`은 오류를 숨기는 상태가 아니라 확인이 더 필요하다는 사실을 정확히 표현하는 상태다.
- Phase 1에는 `UNKNOWN`을 나중에 확인하는 reconciliation 기능이 아직 없다.

### 실습 가이드

Timeout용 상품을 만들고 주문한다.

```powershell
$stock = @{ sku = 'phase1-timeout-practice'; stock = 1 } | ConvertTo-Json
Invoke-RestMethod -Method Put -Uri http://localhost:8080/api/lab/stock -ContentType 'application/json' -Body $stock
$order = @{ sku = 'phase1-timeout-practice'; quantity = 1; amount = 1000; gatewayMode = 'TIMEOUT' } | ConvertTo-Json
Invoke-WebRequest -Method Post -Uri http://localhost:8080/api/orders -ContentType 'application/json' -Body $order
```

응답 상태가 202이고 본문의 상태가 `UNKNOWN`인지 확인한다. DB도 확인한다.

```powershell
docker compose --env-file .env.example exec postgres psql -U payment_app -d payment_orchestration -c "SELECT o.status AS order_status, p.status AS payment_status FROM payment_orchestration.orders o JOIN payment_orchestration.payments p ON p.order_id = o.id ORDER BY o.id DESC LIMIT 1;"
```

이제 HTTP 500용 상품을 만들고 주문한다. PowerShell은 4xx/5xx 응답을 error로 처리하므로
`try/catch`를 사용한다.

```powershell
$stock = @{ sku = 'phase1-error-practice'; stock = 1 } | ConvertTo-Json
Invoke-RestMethod -Method Put -Uri http://localhost:8080/api/lab/stock -ContentType 'application/json' -Body $stock
$order = @{ sku = 'phase1-error-practice'; quantity = 1; amount = 1000; gatewayMode = 'ERROR_500' } | ConvertTo-Json
try {
    Invoke-WebRequest -Method Post -Uri http://localhost:8080/api/orders -ContentType 'application/json' -Body $order
} catch {
    $_.Exception.Response.StatusCode
    $_.ErrorDetails.Message
}
```

재고가 다시 1인지 확인한다.

```powershell
docker compose --env-file .env.example exec postgres psql -U payment_app -d payment_orchestration -c "SELECT sku, stock FROM payment_orchestration.products WHERE sku = 'phase1-error-practice';"
```

---

## Step 7. 관측 지표 읽기

### 목표

느리다는 느낌에만 의존하지 않고 request, transaction, connection 수치를 직접 확인한다.

### 예상 결과

- 주문 요청 횟수와 누적·최대 latency를 확인할 수 있다.
- Transaction 횟수와 누적·최대 duration을 확인할 수 있다.
- 현재 active/pending connection 수를 확인할 수 있다.
- PG delay를 늘리면 request와 transaction 지표가 함께 증가한다.

### 결과를 통해 알아야 할 것

- Request latency는 사용자가 기다린 전체 시간이다.
- Transaction duration은 DB transaction이 connection과 lock을 붙잡을 수 있는 시간이다.
- 두 값이 PG delay와 함께 비슷하게 늘어난다면 외부 호출이 transaction 수명을 직접 늘린다는
  증거다.
- Active connection은 현재 사용 중인 통로이고 pending connection은 통로가 비기를 기다리는
  요청이다.
- 측정하지 않은 성능 수치를 추정해서는 안 된다.

### 실습 가이드

현재 지표를 확인한다.

```powershell
Invoke-RestMethod http://localhost:8080/actuator/metrics/payment.request.latency | ConvertTo-Json -Depth 6
Invoke-RestMethod http://localhost:8080/actuator/metrics/payment.transaction.duration | ConvertTo-Json -Depth 6
Invoke-RestMethod http://localhost:8080/actuator/metrics/hikaricp.connections.active | ConvertTo-Json -Depth 6
Invoke-RestMethod http://localhost:8080/actuator/metrics/hikaricp.connections.pending | ConvertTo-Json -Depth 6
```

각 응답에서 다음 값을 찾아 기록한다.

| 지표 | COUNT | TOTAL_TIME 또는 VALUE | MAX |
|---|---:|---:|---:|
| `payment.request.latency` |  |  |  |
| `payment.transaction.duration` |  |  |  |
| Active connection | 해당 없음 |  | 해당 없음 |
| Pending connection | 해당 없음 |  | 해당 없음 |

Application을 재시작하면 in-memory metric count가 초기화된다. 서로 다른 실험 결과를 정확히
비교하려면 동일한 초기 조건에서 측정한다.

---

## Step 8. Integration test로 동시성 동작 고정하기

### 목표

수동 실습에서 본 정상·실패·동시성 동작을 반복 가능한 자동 테스트로 확인한다.

### 예상 결과

- 전체 Gradle test가 성공한다.
- Phase 1의 다섯 테스트가 실행된다.
- 같은 상품과 3초 PG 지연을 사용한 테스트의 느린 요청이 최소 5.5초 이상 걸린다.
- 재고 1개 경쟁에서 HTTP 201 한 건과 HTTP 409 한 건이 발생한다.
- 최종 재고는 0이며 음수가 되지 않는다.

### 결과를 통해 알아야 할 것

- `CountDownLatch`는 두 worker가 준비된 뒤 거의 동시에 요청을 시작하게 한다.
- `SELECT ... FOR UPDATE`가 같은 상품 요청을 직렬화한다.
- 느린 요청의 최소 시간을 assertion으로 남기면 잘못된 transaction boundary의 현상을
  regression test로 고정할 수 있다.
- 테스트 성공은 좋은 성능을 뜻하지 않는다. 예상한 나쁜 동작까지 정확히 재현됐다는 뜻이다.

### 실습 가이드

먼저 테스트 코드를 읽는다.

```powershell
Get-Content src/test/java/lab/payment/orchestration/Phase1ConcurrentOrderIntegrationTest.java
```

실제 PostgreSQL을 사용해 전체 테스트를 실행한다.

```powershell
docker compose --env-file .env.example --profile test run --build --rm test
```

Phase 1 테스트만 실행하려면 다음 명령을 사용한다.

```powershell
docker compose --env-file .env.example --profile test run --build --rm test gradle test --tests '*Phase1ConcurrentOrderIntegrationTest' --no-daemon
```

테스트를 실행하지 않았다면 통과했다고 기록하지 않는다. 실패하면 test report와 container log를
확인한 뒤 실제 결과를 그대로 Evidence에 남긴다.

---

## Step 9. 10초 PG 지연과 lock wait 재현하기

### 목표

같은 상품 주문 두 개를 동시에 실행해 첫 요청의 외부 PG 대기가 두 번째 요청의 latency까지
늘리는 현상을 직접 측정한다.

### 예상 결과

- 두 요청 모두 성공한다.
- 한 요청은 약 10초, 다른 요청은 약 20초 걸린다.
- Active DB connection이 최대 2개까지 증가한다.
- PostgreSQL lock waiter가 1개 이상 관찰된다.
- CSV sample이 `build/phase1-evidence/` 아래에 생성된다.

정확한 시간은 컴퓨터 상태에 따라 달라질 수 있다. “반드시 정확히 10초와 20초”가 아니라
두 번째 요청이 첫 번째 요청보다 크게 느리고 lock wait가 실제로 관찰되는지가 핵심이다.

### 결과를 통해 알아야 할 것

- 첫 transaction이 상품 행을 잠근 채 PG 응답을 기다린다.
- 두 번째 transaction은 connection을 빌렸지만 같은 상품 행의 잠금을 얻지 못하고 기다린다.
- 첫 요청이 끝난 뒤 두 번째 요청이 자신의 10초 PG 호출을 시작한다.
- 외부 지연이 요청 대기열을 통해 뒤의 요청으로 전파된다.

### 실습 가이드

Application이 실행 중인 상태에서 reproduction script를 실행한다.

```powershell
.\scripts\reproduce\phase1-slow-pg-lock.ps1
```

출력 표에서 두 요청의 `Status`와 `LatencyMs`를 확인한다. 생성된 최신 CSV를 읽는다.

```powershell
$sample = Get-ChildItem build/phase1-evidence/phase1-*.csv | Sort-Object LastWriteTime | Select-Object -Last 1
Import-Csv $sample.FullName | Format-Table -AutoSize
```

최댓값과 lock wait가 관찰된 sample 수를 요약한다.

```powershell
$rows = Import-Csv $sample.FullName
[pscustomobject]@{
    MaxActive = ($rows.active_connections | Measure-Object -Maximum).Maximum
    MaxPending = ($rows.pending_connections | Measure-Object -Maximum).Maximum
    MaxLockWaiters = ($rows.lock_waiters | Measure-Object -Maximum).Maximum
    LockWaitSamples = ($rows | Where-Object { [int]$_.lock_waiters -gt 0 }).Count
}
```

실제 Phase 1 Evidence에서는 request latency 10.203초와 19.954초, 최대 transaction duration
19.795초, 최대 active connection 2개, 최대 lock waiter 1개가 관찰됐다. 자신의 실습에서는
자신이 실제로 얻은 값을 기록한다.

---

## Step 10. Connection pool 압력 재현하고 결론 내리기

### 목표

설정된 DB connection pool 크기보다 많은 동시 요청을 보내 active, pending connection과
여러 lock waiter가 동시에 생기는 모습을 확인한다.

### 예상 결과

- Hikari connection pool의 최대 크기인 5개에 active connection이 도달할 수 있다.
- 여섯 번째 요청은 connection을 얻기 위해 pending 상태가 될 수 있다.
- 같은 상품 행을 기다리는 lock waiter가 여러 개 생긴다.
- 요청 latency가 약 3초 단위의 계단처럼 길어질 수 있다.
- 환경과 timing에 따라 connection timeout이 발생할 수도 있고 발생하지 않을 수도 있다.

### 결과를 통해 알아야 할 것

- Connection pool은 무한하지 않다.
- 잠금 대기 중인 transaction도 DB connection을 사용한다.
- 한 상품의 느린 처리 때문에 pool이 가득 차면 다른 DB 작업까지 connection을 얻지 못할 수 있다.
- 한 번의 실행에서 timeout이 없었다면 timeout이 발생했다고 주장하면 안 된다.
- 성능 문제처럼 보이지만 transaction boundary가 자원 점유 시간을 늘린 구조적 문제다.

### 실습 가이드

3초 지연을 사용하는 같은 상품 주문 6개를 동시에 보낸다.

```powershell
.\scripts\reproduce\phase1-slow-pg-lock.ps1 -GatewayMode DELAY_3S -ConcurrentRequests 6
```

Step 9와 같은 방법으로 최신 CSV의 최댓값을 계산한다. 다음 표에 자신의 결과를 적는다.

| 관찰 항목 | 실습 결과 |
|---|---:|
| 성공 요청 수 |  |
| 실패 요청 수 |  |
| 최대 request latency |  |
| 최대 active connection |  |
| 최대 pending connection |  |
| 최대 lock waiter |  |
| lock waiter가 존재한 sample 수 |  |

기존 Phase 1 실행에서는 active connection 5개, pending connection 1개, lock waiter 최대 4개,
최대 request latency 16.809초가 관찰됐다. Connection timeout은 발생하지 않았으므로 발생했다고
기록하지 않았다.

---

## 실습 완료 점검표

다음 질문에 자신의 말로 답할 수 있으면 Phase 1 실습을 완료한 것이다.

- [ ] DB transaction이 무엇인지 설명할 수 있다.
- [ ] `FOR UPDATE`가 왜 필요한지와 어떤 대기 문제를 만드는지 설명할 수 있다.
- [ ] DB constraint와 application validation의 차이를 설명할 수 있다.
- [ ] PG timeout을 `FAILED`로 단정하면 안 되는 이유를 설명할 수 있다.
- [ ] Request latency와 transaction duration의 차이를 설명할 수 있다.
- [ ] Active connection, pending connection, lock waiter의 의미를 설명할 수 있다.
- [ ] 두 개의 10초 PG 요청 중 하나가 약 20초 걸리는 이유를 설명할 수 있다.
- [ ] 테스트 통과가 운영 가능한 시스템을 뜻하지 않는 이유를 설명할 수 있다.
- [ ] Phase 1에서 보장하는 것과 아직 보장하지 않는 것을 구분할 수 있다.

## Phase 1에서 보장하는 것

- 재고는 0 미만이 되지 않는다.
- 주문 하나에는 `SUCCESS` 결제가 최대 하나만 존재한다.
- PG timeout만으로 결제를 `FAILED`라고 확정하지 않는다.
- 결과를 확정할 수 없으면 `UNKNOWN`을 사용한다.

## Phase 1에서 아직 보장하지 않는 것

- Application 재시작이나 instance 장애 이후 `PENDING`/`UNKNOWN` 상태의 자동 수렴
- Local 상태와 PG authoritative state의 장기 불일치 해결
- 주문, webhook, event, 환불의 멱등 처리
- 오래된 webhook에 의한 최종 상태 역행 방지
- PG 승인 후 DB commit 실패 상황의 복구
- 복수 application instance의 correctness
- Connection pool 고갈 상황의 가용성

## 실습 종료

Container만 종료하고 DB data는 보존하려면 다음 명령을 실행한다.

```powershell
docker compose --env-file .env.example down
```

DB volume까지 삭제하는 다음 명령은 모든 실습 데이터를 초기화하려는 경우에만 실행한다.

```powershell
docker compose --env-file .env.example down --volumes
```

Phase 1에서는 여기서 멈춘다. Acceptance criteria를 정하지 않은 상태에서 다음 Phase의
transaction boundary 개선, outbox, retry, reconciliation을 미리 구현하지 않는다.
