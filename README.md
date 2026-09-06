# Payment Orchestration Lab

외부 결제 장애, 재시도, 상태 수렴, 동시성 문제를 단계적으로 재현하기 위한 백엔드 실험 환경이다. 현재 bootstrap 단계에는 PostgreSQL 연결, Flyway migration, health endpoint, integration test만 포함한다. 주문 및 결제 비즈니스 기능은 의도적으로 구현하지 않았다.

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

테스트는 PostgreSQL 연결, Flyway V1 성공, `payment_orchestration` 스키마 생성, application health endpoint를 검증한다. 테스트 후 실행 중인 DB를 정리하려면 `docker compose --env-file .env.example down`을 실행한다.

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
- 비즈니스 테이블을 만들지 않으므로 재고 및 결제 관련 불변식은 아직 적용 대상이 아니다.

## 현재 구현하지 않은 기능

- Order, Inventory, Payment, Refund 도메인과 API
- PG 연동, webhook, timeout/UNKNOWN 처리
- idempotency와 상태 전이 규칙
- outbox, reconciliation, admin/operations
- 동시성·장애·성능 시나리오 및 재현 스크립트
- 복수 애플리케이션 인스턴스 검증

위 항목은 해당 Phase의 acceptance criteria와 불변식을 먼저 정의한 뒤 단계별로 추가한다.
