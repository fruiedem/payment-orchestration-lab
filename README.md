# Payment Orchestration Lab

외부 결제 장애, 재시도, 상태 수렴, 동시성 문제를 단계적으로 재현하기 위한 백엔드 실험 환경이다. 현재 Phase 1은 주문 생성, 재고 차감, Fake PG 승인을 하나의 DB transaction으로 묶은 의도적으로 잘못된 구조다. 해결책이 아니라 slow PG가 lock과 connection을 오래 점유하는 실패를 관찰한다.

## 필요 도구

- Docker Engine 또는 Docker Desktop
- Docker Compose v2

로컬 Java나 Gradle 설치는 필요하지 않다. Docker 이미지가 Java 21과 Gradle을 제공한다.

## DB + application 실행

저장소 루트에서 다음 한 명령을 실행한다.

```powershell
docker compose --env-file .env.example up --build
```

애플리케이션이 준비되면 `http://localhost:8080/actuator/health`가 `UP`을 반환한다. 종료는 `Ctrl+C`로 하고, 컨테이너 정리는 다음 명령으로 수행한다.

```powershell
docker compose --env-file .env.example down
```

DB 데이터까지 초기화하려는 경우에만 다음 명령을 사용한다. 이 명령은 로컬 PostgreSQL 볼륨을 삭제한다.

```powershell
docker compose --env-file .env.example down --volumes
```

## 테스트 실행

실제 PostgreSQL 컨테이너에 연결하는 integration test를 한 명령으로 실행한다.

```powershell
docker compose --env-file .env.example --profile test run --build --rm test
```

테스트는 PostgreSQL/Flyway/health와 Phase 1의 정상 승인, timeout `UNKNOWN`, HTTP 500 rollback, 동일 상품 동시 주문을 검증한다. 테스트 후 실행 중인 DB를 정리하려면 `docker compose --env-file .env.example down`을 실행한다.

## Phase 1 API와 재현

재고를 준비한 뒤 주문한다.

```http
PUT /api/lab/stock
{"sku":"sku-1","stock":2}

POST /api/orders
{"sku":"sku-1","quantity":1,"amount":1000,"gatewayMode":"DELAY_3S"}
```

`gatewayMode`는 `NORMAL`, `DELAY_3S`, `DELAY_10S`, `TIMEOUT`, `ERROR_500` 중 하나다.
동시 주문과 지표 샘플링은 다음 명령으로 재현한다.

```powershell
.\scripts\reproduce\phase1-slow-pg-lock.ps1
```

## 환경 변수와 secret

`.env.example`에는 로컬 개발 전용 예시값만 있다. 개인 설정이 필요하면 `.env.example`을 `.env`로 복사하고 값을 변경한다. `.env` 및 `.env.*`는 `.gitignore` 대상이며 `.env.example`만 커밋한다. 실제 운영 credential, API key, token은 저장소에 추가하지 않는다.

## 프로젝트 구조

```text
.
├── compose.yaml
├── Dockerfile
├── build.gradle
├── settings.gradle
├── src/
│   ├── main/
│   │   ├── java/lab/payment/orchestration/
│   │   └── resources/db/migration/
│   └── test/java/lab/payment/orchestration/
├── docs/
│   ├── adr/
│   ├── evidence/
│   └── runbook/
├── tests/
│   ├── concurrency/
│   ├── failure/
│   └── performance/
└── scripts/reproduce/
```

## 현재 단계의 보장 범위

- Compose가 PostgreSQL 준비 완료 후 application/test를 시작한다.
- 애플리케이션 시작 시 Flyway가 migration을 적용한다.
- Integration test가 mock DB가 아닌 PostgreSQL에 접속한다.
- DB `CHECK (stock >= 0)`와 상품 행 잠금으로 재고가 음수가 되지 않는다.
- partial unique index로 주문별 `SUCCESS` 결제는 최대 하나다.
- timeout은 `FAILED`가 아니라 `UNKNOWN`으로 기록한다.

## 현재 구현하지 않은 기능

- idempotency와 상태 전이 규칙
- outbox, reconciliation, admin/operations
- 실제 PG 연동, webhook, refund
- `PENDING`/`UNKNOWN` 자동 수렴
- 복수 애플리케이션 인스턴스 검증

위 항목은 해당 Phase의 acceptance criteria와 불변식을 먼저 정의한 뒤 단계별로 추가한다.
