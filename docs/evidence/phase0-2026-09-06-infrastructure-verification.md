# Phase 0 infrastructure verification evidence

- Date: 2026-09-06
- Timezone: Asia/Seoul
- Source revision before documentation changes:
  `9e9496120cba20cb2c35e59cf89e7b8ed3149a36`
- Environment file: `.env.example`

## Environment

```text
Docker client: 29.7.2
Docker server: 29.7.2
Docker server OS: Docker Desktop
Docker Compose: v5.5.0
Java in integration test: 21.0.12
Spring Boot: 3.5.6
PostgreSQL: 17.11
```

## Docker daemon

Docker client와 Compose는 설치돼 있었지만 최초 확인 시 daemon이 실행 중이지 않았다.
설치된 Docker Desktop을 시작한 뒤 다음 상태를 확인했다.

```text
Server=29.7.2 OS=Docker Desktop
```

## Documented test command

Command:

```powershell
docker compose --env-file .env.example --profile test run --build --rm test
```

Result:

```text
Container payment-orchestration-lab-postgres-1 Healthy
> Task :test UP-TO-DATE
BUILD SUCCESSFUL in 10s
4 actionable tasks: 4 up-to-date
Exit code: 0
```

Interpretation: 명령은 성공했지만 Gradle up-to-date check 때문에 이번 실행에서 테스트
메서드를 다시 실행하지 않았다. 따라서 실제 재실행 증거로 사용하지 않는다.

## Forced integration test execution

Command:

```powershell
docker compose --env-file .env.example --profile test run --build --rm test `
  gradle test --no-daemon --rerun-tasks
```

Gradle result:

```text
> Task :compileJava
> Task :processResources
> Task :classes
> Task :compileTestJava
> Task :testClasses
> Task :test
BUILD SUCCESSFUL in 1m 9s
4 actionable tasks: 4 executed
Exit code: 0
```

Test result XML:

```text
Suite: lab.payment.orchestration.InfrastructureIntegrationTest
Tests: 2
Skipped: 0
Failures: 0
Errors: 0
Test time: 7.942 seconds
```

Executed tests:

```text
postgresIsReachableAndInitialMigrationWasApplied()
applicationHealthEndpointIsUp()
```

Relevant runtime log:

```text
Database: jdbc:postgresql://postgres:5432/payment_orchestration (PostgreSQL 17.11)
Successfully validated 1 migration
Current version of schema "public": 1
Schema "public" is up to date. No migration necessary.
```

## Compose DNS verification

Command:

```powershell
docker compose --env-file .env.example --profile test run --rm test `
  sh -c "getent hosts localhost; getent hosts postgres"
```

Result:

```text
::1 localhost localhost.localdomain
127.0.0.1 localhost localhost.localdomain
172.18.0.2 postgres
```

Interpretation: `localhost`는 실행 중인 container 자신의 loopback이고 `postgres`는
Compose network의 PostgreSQL service 주소로 해석됐다. IP 자체는 container 재생성에
따라 달라질 수 있다.

## Health verification

Command executed at `2026-09-06T12:18:54+09:00`:

```powershell
(Invoke-RestMethod -Uri 'http://localhost:8080/actuator/health').status
```

Result:

```text
UP
```

Interpretation: 현재 application HTTP endpoint와 등록된 health indicator가 정상이다.
결제 기능 성공을 의미하지 않는다.

## Container and volume verification

Commands:

```powershell
docker inspect payment-orchestration-lab-postgres-1 `
  --format 'ContainerId={{.Id}} Mounts={{range .Mounts}}{{.Type}}:{{.Name}}->{{.Destination}}{{end}}'

docker volume inspect payment-orchestration-lab_postgres-data `
  --format 'Volume={{.Name}} Mountpoint={{.Mountpoint}}'
```

Result:

```text
ContainerId=12972daf31c49a7c55ee550901795f7ac015110ec48098c1460722aab75a4d32
Mounts=volume:payment-orchestration-lab_postgres-data->/var/lib/postgresql/data
Volume=payment-orchestration-lab_postgres-data
Mountpoint=/var/lib/docker/volumes/payment-orchestration-lab_postgres-data/_data
```

Interpretation: 실행 container와 named volume은 별도 객체이며 PostgreSQL 데이터
경로가 volume에 mount돼 있다.

## Flyway history verification

Command:

```powershell
docker compose --env-file .env.example exec -T postgres `
  psql -U payment_app -d payment_orchestration -At -F '|' `
  -c "SELECT version, description, type, checksum, success FROM flyway_schema_history ORDER BY installed_rank;"
```

Result:

```text
1|create application schema|SQL|621857564|t
```

Interpretation: V1 versioned SQL migration의 checksum과 성공 기록이 존재한다.

## Limitations

- 기존 `postgres-data` volume을 사용했다.
- V1은 최초 적용되지 않고 validation 후 up-to-date로 판단됐다.
- 빈 DB bootstrap migration은 이번 실행에서 검증하지 않았다.
- Order, Inventory, Payment, Refund 기능은 존재하지 않는다.
- 비즈니스 불변식, 동시성, 장애, 성능은 검증하지 않았다.
- Application과 PostgreSQL container는 검증 종료 시 실행 중인 상태로 유지했다.

