# Phase 1 쉬운 구현 보고서

- 최초 작성일: 2026-09-06
- 쉬운 설명으로 수정한 날: 2026-09-09

이 문서는 Phase 1에서 무엇을 만들었고, 왜 일부러 불편한 구조로 만들었는지를 처음 보는
사람도 이해할 수 있도록 쉽게 설명한다.

## 1. 어떤 요청 사항에 대한 개발인가?

인터넷 쇼핑몰에서 물건 하나를 주문하는 상황을 생각해 보자. 손님이 주문 버튼을 누르면
쇼핑몰은 보통 다음 일을 해야 한다.

1. 주문하려는 물건이 창고에 남아 있는지 확인한다.
2. 다른 사람이 같은 물건을 동시에 가져가지 못하게 잠시 표시해 둔다.
3. 주문한 수량만큼 재고를 줄인다.
4. 주문 내용을 저장한다.
5. 카드 회사와 비슷한 결제 회사에 돈을 받아도 되는지 물어본다.
6. 결제가 성공하면 주문과 결제를 성공 상태로 저장한다.

이번 개발 요청은 이 과정을 가장 단순하게 만드는 것이었다. 실제 결제 회사 대신
`FakePaymentGateway`라는 가짜 결제 회사를 만들고 다음 다섯 가지 상황을 선택해서 실행할
수 있게 했다.

- `NORMAL`: 바로 정상 응답을 보낸다.
- `DELAY_3S`: 3초 뒤에 정상 응답을 보낸다.
- `DELAY_10S`: 10초 뒤에 정상 응답을 보낸다.
- `TIMEOUT`: 오래 기다렸지만 답을 받지 못한 상황을 만든다.
- `ERROR_500`: 결제 회사 서버에서 오류가 발생한 상황을 만든다.

또한 여러 손님이 같은 상품을 동시에 주문하도록 테스트하고, 그때 다음 정보를 실제로
측정할 수 있게 만드는 것도 요청 사항에 포함됐다.

- 손님이 응답을 받을 때까지 걸린 시간(request latency)
- DB 작업 묶음이 시작해서 끝날 때까지 걸린 시간(transaction duration)
- 사용 중이거나 기다리는 DB connection 수
- 다른 요청이 DB 잠금이 풀리기를 기다리는 현상(lock wait)

## 2. 개발의 핵심 목표

이번 Phase의 핵심 목표는 좋은 결제 시스템을 완성하는 것이 아니다. 오히려 외부 결제 회사의
응답을 기다리는 일을 DB transaction 안에 넣으면 어떤 문제가 생기는지 직접 보는 것이다.

DB transaction은 여러 DB 작업을 하나의 약속으로 묶는 방법이다. 예를 들어 재고를 줄이고
주문을 저장하는 두 작업 중 하나라도 실패하면 둘 다 없던 일로 되돌릴 수 있다. 이것을
공책에 여러 내용을 적은 뒤, 마지막에 한꺼번에 “확정” 도장을 찍는 것과 비슷하다고 생각하면
된다.

그런데 이번 구현은 공책과 펜을 빌린 상태에서 결제 회사에 전화를 걸고, 상대방이 전화를
받을 때까지 계속 기다린다. 결제 회사가 10초 동안 대답하지 않으면 그동안 다른 사람은
그 펜과 잠긴 재고를 사용할 수 없다.

따라서 이번 Phase가 확인하려는 핵심 문제는 다음과 같다.

- 결제 회사가 느리면 우리 DB transaction도 함께 길어진다.
- Transaction이 길어지는 동안 DB connection을 계속 차지한다.
- 같은 상품을 주문한 다른 손님은 재고 잠금이 풀릴 때까지 기다려야 한다.
- 기다리는 손님이 늘어나면 뒤에 있는 손님의 응답 시간이 계속 길어진다.
- 요청이 더 많아지면 사용할 수 있는 DB connection이 부족해질 수 있다.

이 문제를 다음 Phase보다 먼저 해결하지 않고, 테스트와 재현 script로 눈에 보이게 남기는
것이 이번 개발의 목표다.

## 3. 설계 목적 및 근거

### PG 호출을 왜 transaction 안에 넣었는가?

좋은 구조이기 때문이 아니라, 잘못된 transaction boundary의 문제를 관찰해야 하기 때문이다.
PG 호출을 transaction 밖으로 옮기면 이번 Phase에서 확인하려는 lock wait와 connection 점유
문제가 줄어들어 실험 목적을 달성할 수 없다.

### 같은 상품을 왜 잠그는가?

재고가 1개인데 두 손님이 동시에 주문했다고 생각해 보자. 두 손님이 모두 “재고가 1개 있네”
라고 확인한 뒤 각각 하나씩 가져가면 재고가 -1이 될 수 있다.

이를 막기 위해 상품을 `SELECT ... FOR UPDATE`로 조회한다. 먼저 상품을 확인한 transaction이
해당 행을 잠그고, 뒤에 온 transaction은 잠금이 풀릴 때까지 기다린다. DB에는
`CHECK (stock >= 0)`도 두어 실수로 재고가 음수가 되는 것을 한 번 더 막는다.

### 성공한 결제가 왜 하나만 존재하도록 했는가?

한 주문의 돈이 두 번 결제되면 안 된다. 그래서 결제 상태가 `SUCCESS`인 행은 주문 하나당
최대 하나만 만들 수 있도록 DB에 partial unique index를 두었다. 이 규칙은 application
코드가 실수해도 DB가 마지막 단계에서 막는다.

### Timeout을 왜 실패로 저장하지 않는가?

Timeout은 결제가 실패했다는 뜻이 아니다. 우리 서버가 답을 못 받았을 뿐, 결제 회사에서는
이미 결제가 성공했을 수도 있다. 따라서 함부로 `FAILED`라고 적지 않고 `UNKNOWN`이라고
저장한다. 주문은 아직 결론을 내리지 못한 `PENDING` 상태로 남긴다.

### HTTP 500에서는 왜 전체 작업을 되돌리는가?

이번 단순한 Phase에서는 Fake PG가 HTTP 500 오류를 반환하면 승인에 실패한 것으로 보고
transaction 전체를 rollback한다. 그러면 줄였던 재고와 만들었던 주문 및 결제가 모두
원래대로 돌아간다. 이것은 Phase 1 실험을 위한 최소 동작이며 완전한 실제 결제 처리 방식은
아니다.

## 4. 구현 상세 내용

### 주문 처리 순서

`POST /api/orders`로 주문 요청이 오면 다음 순서로 처리한다.

```text
DB transaction 시작
  → 상품 행 조회 및 잠금
  → 남은 재고 확인
  → 재고 차감
  → PENDING 주문 생성
  → PENDING 결제 생성
  → Fake PG 호출 및 응답 대기
  → 성공이면 결제 SUCCESS, 주문 PAID
  → timeout이면 결제 UNKNOWN, 주문 PENDING
DB transaction 종료
```

중요한 점은 Fake PG를 호출하고 기다리는 단계도 DB transaction 안에 있다는 것이다.

### DB에 추가한 보호 장치

- 상품 재고는 0 이상이어야 한다.
- 주문 수량은 0보다 커야 한다.
- 결제 금액은 0보다 커야 한다.
- 하나의 주문에는 `SUCCESS` 결제가 최대 하나만 존재할 수 있다.
- 재고를 확인할 때 상품 행을 잠가 동시 주문을 차례대로 처리한다.

### 관찰할 수 있도록 추가한 지표

- `payment.request.latency`: 주문 요청 전체에 걸린 시간
- `payment.transaction.duration`: DB transaction 전체에 걸린 시간
- `hikaricp.connections.active`: 현재 사용 중인 DB connection 수
- `hikaricp.connections.pending`: DB connection을 빌리려고 기다리는 요청 수
- PostgreSQL `pg_stat_activity`: DB lock을 기다리는 요청 수와 내용

Actuator의 다음 주소에서 지표를 확인할 수 있다.

- `/actuator/metrics/payment.request.latency`
- `/actuator/metrics/payment.transaction.duration`
- `/actuator/metrics/hikaricp.connections.active`
- `/actuator/metrics/hikaricp.connections.pending`
- `/actuator/prometheus`

### 동시 주문 테스트

Integration test는 실제 PostgreSQL에 연결한 application에 HTTP 요청을 보낸다.

- 재고가 2개인 같은 상품을 두 명이 동시에 주문한다.
- 두 요청 모두 Fake PG에서 3초씩 기다린다.
- 한 요청이 상품 잠금을 잡으면 다른 요청은 기다려야 한다.
- 첫 요청은 약 3초, 뒤 요청은 약 6초가 걸리는 나쁜 동작을 확인한다.
- 재고가 1개일 때는 한 요청만 성공하고 다른 요청은 재고 부족으로 끝나는지 확인한다.
- Timeout이 `FAILED`가 아니라 `UNKNOWN`으로 저장되는지 확인한다.
- 정상 응답과 HTTP 500 rollback도 확인한다.

최종 실행 결과는 전체 테스트 7개 통과, 실패 0개였다. 이때 “테스트 통과”는 시스템이
운영에 적합하다는 뜻이 아니라, 이번 Phase에서 의도한 정상 동작과 나쁜 동작이 예상대로
재현됐다는 뜻이다.

### 재현 script와 실제 측정 결과

다음 script는 같은 상품 주문을 동시에 보내면서 latency, DB connection과 lock wait를
CSV 파일에 기록한다.

```powershell
.\scripts\reproduce\phase1-slow-pg-lock.ps1
```

10초 지연 요청 두 개를 동시에 실행했을 때 실제 request latency는 다음과 같았다.

- 첫 요청: 10.203초
- 두 번째 요청: 19.954초
- 최대 transaction duration: 19.795초
- 최대 active DB connection: 2개
- 최대 lock waiter: 1개

두 번째 요청은 첫 번째 요청의 잠금이 풀릴 때까지 기다린 뒤 자신의 10초 PG 호출을 실행해서
거의 20초가 걸렸다.

Connection pool 크기인 5개보다 많은 요청을 보내는 실험도 실행했다.

```powershell
.\scripts\reproduce\phase1-slow-pg-lock.ps1 -GatewayMode DELAY_3S -ConcurrentRequests 6
```

그 결과 active connection은 5개, pending connection은 1개, lock waiter는 최대 4개까지
늘어났다. 가장 오래 걸린 요청은 16.809초였다. 외부 PG 지연이 DB connection과 잠금을
오래 차지하고, 뒤의 요청 시간을 계속 늘린다는 사실을 실제 수치로 확인했다.

## 5. 반드시 알아야 할 내용

### 이 구조는 일부러 잘못 만들었다

PG 호출을 DB transaction 안에서 수행하는 현재 구조를 실제 서비스의 권장 설계로 이해하면
안 된다. 이번 Phase는 문제를 숨기지 않고 관찰하기 위한 학습용 단계다. 해결책은 의도적으로
구현하지 않았다.

### 테스트가 통과해도 좋은 결제 시스템이 된 것은 아니다

테스트는 재고가 음수가 되지 않는지, timeout을 실패로 단정하지 않는지, 그리고 느린 PG가
어떤 문제를 만드는지를 확인한다. 아직 실제 서비스에 필요한 많은 기능이 없다.

### 현재 보장하는 것

- 재고는 0 미만이 되지 않는다.
- 주문 하나에는 성공한 결제가 최대 하나만 존재한다.
- PG timeout만으로 결제를 `FAILED`라고 확정하지 않는다.
- 결과를 알 수 없으면 `UNKNOWN`으로 저장한다.

### 아직 보장하지 않는 것

- `UNKNOWN`이나 `PENDING` 상태를 나중에 자동으로 확인하고 최종 상태로 바꾸는 기능
- 우리 DB 상태와 실제 PG 상태가 다를 때 이를 찾아서 고치는 reconciliation
- 같은 주문, webhook, event, 환불 요청이 반복돼도 한 번만 처리하는 멱등성
- 오래된 webhook이 최신 결제 상태를 과거 상태로 되돌리지 못하게 하는 규칙
- PG 승인은 성공했지만 DB commit이 실패했을 때의 복구
- Application 재시작이나 instance 장애 이후의 자동 복구
- 여러 application instance를 동시에 실행했을 때의 correctness
- Connection pool이 가득 찼을 때의 안정적인 처리

### 다음 Phase 기능을 미리 넣지 않았다

Outbox, message broker, 재시도, reconciliation worker, 분산 lock, Saga 같은 기능은 이번
Phase에 추가하지 않았다. 먼저 이번 실패를 증거로 남긴 뒤, 다음 Phase의 acceptance criteria를
정하고 필요한 해결책을 하나씩 검토해야 한다.

### 자주 나오는 용어

- **PG**: 카드 결제 요청을 받아 실제 결제 처리를 도와주는 외부 결제 회사 또는 시스템
- **DB transaction**: 여러 DB 작업을 모두 성공시키거나 모두 되돌리는 하나의 작업 묶음
- **DB connection**: Application이 DB와 대화할 때 사용하는 통로
- **Lock**: 같은 데이터를 여러 요청이 동시에 바꾸지 못하도록 잠시 잠그는 장치
- **Latency**: 요청을 보낸 뒤 응답을 받을 때까지 걸린 시간
- **Timeout**: 정해진 시간 안에 상대방의 답을 받지 못한 상태
- **Rollback**: transaction에서 처리한 내용을 모두 취소하고 이전 상태로 되돌리는 것
- **UNKNOWN**: 결제가 성공했는지 실패했는지 아직 확실히 알 수 없는 상태
- **PENDING**: 처리가 아직 끝나지 않아 기다리는 상태
- **불변식**: 어떤 상황에서도 반드시 지켜야 하는 규칙
