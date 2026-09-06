# Phase 0: 2년차 개발자 완료 기준 상세 답안

이 문서는 `Phase0-02-year-developer.md`의 완료 기준 질문에 현재 저장소 코드와
2026-09-06 실행 결과를 근거로 답한다.

실행 결과의 원문 요약은
[Phase 0 infrastructure verification evidence](../../evidence/phase0-2026-09-06-infrastructure-verification.md)에
기록한다.

## 초등학생도 이해하는 먼저 읽기

이 프로젝트를 여러 개의 방이 있는 건물이라고 생각해 보자.

```text
Docker라는 건물
├── application 방: 주문 프로그램 실행
├── postgres 방: 데이터베이스 실행
└── test 방: 프로그램 검사
```

각 방은 서로 분리돼 있지만 Docker Compose가 방 사이에 통로를 만들고 이름표를
붙여 준다. 이 그림을 기억하면 아래 질문을 쉽게 이해할 수 있다.

### 1. 왜 `localhost`가 아니라 `postgres`로 찾을까?

`localhost`는 “내 방”이라는 뜻이다.

Application 방에서 `localhost`를 찾으면 application 자신을 찾는다. PostgreSQL은
옆에 있는 별도의 `postgres` 방에 있으므로 `postgres`라는 이름표로 찾아야 한다.

```text
application 방에서 localhost 찾기
→ application 방 자신을 찾음
→ PostgreSQL 없음

application 방에서 postgres 찾기
→ postgres 방을 찾음
→ PostgreSQL 있음
```

그래서 application은 다음 주소를 사용한다.

```text
jdbc:postgresql://postgres:5432/payment_orchestration
```

- `postgres`: PostgreSQL이 있는 방 이름
- `5432`: PostgreSQL 방의 문 번호
- `payment_orchestration`: 사용할 데이터베이스 이름

`postgres` 방의 실제 IP 주소는 container를 다시 만들면 바뀔 수 있다. 변할 수 있는
IP를 직접 적지 않고 Compose가 관리하는 service 이름을 사용하는 이유다.

### 2. Container와 volume은 무엇이 다를까?

학교 매점에 비유하면 container는 매점이고 volume은 재료를 보관하는 창고다.

```text
PostgreSQL container = 운영 중인 매점
PostgreSQL volume    = 데이터를 보관하는 창고
```

매점을 철거하고 새로 만들어도 창고가 남아 있으면 보관하던 재료를 다시 사용할 수
있다. 마찬가지로 PostgreSQL container가 교체돼도 같은 volume을 연결하면 DB 데이터가
남는다.

```text
PostgreSQL container
  → /var/lib/postgresql/data
  → postgres-data volume
```

일반적인 `docker compose down`은 매점을 정리하지만 창고는 남긴다.
`docker compose down --volumes`는 창고까지 삭제한다. 따라서 `--volumes`는 DB 데이터를
정말 초기화할 때만 사용해야 한다.

Volume도 완전한 backup은 아니다. 창고 자체를 실수로 지우거나 망가뜨릴 수 있으므로
중요한 데이터는 별도 backup이 필요하다.

### 3. 이미 실행한 Flyway 파일은 왜 수정하면 안 될까?

Flyway migration은 학교 공사 기록과 같다.

```text
V1: 운동장을 만들었다.
V2: 운동장에 미끄럼틀을 설치했다.
V3: 미끄럼틀 옆에 그네를 설치했다.
```

이미 V1대로 학교를 만든 뒤 V1 기록을 “운동장과 수영장을 만들었다”로 몰래 바꾸면
기존 학교와 새 학교의 모습이 달라진다. 둘 다 V1이라고 적혀 있어도 실제 구조는
같지 않다.

Flyway는 migration 파일마다 지문과 같은 checksum을 기록한다. 현재 V1의 checksum은
`621857564`다. 이미 적용한 V1을 수정하면 지문이 달라져 Flyway가 과거 기록과 현재
파일이 다르다고 판단한다.

따라서 V1을 고치는 대신 새로운 V2를 추가한다.

```text
V1__create_application_schema.sql
V2__add_required_change.sql
```

과거 공사 기록을 바꾸지 않고 새로운 공사 기록을 남기는 것이다.

### 4. Health가 `UP`이면 결제도 성공할까?

아니다. Health의 `UP`은 “매점 불이 켜져 있고 직원이 대답한다” 정도의 의미다.

```text
매점 불이 켜짐
직원이 있음
냉장고를 열 수 있음
→ Health UP
```

그래도 카드 결제기가 고장 났거나 상품이 없을 수 있다. 현재 health는 Spring Boot가
실행되고 HTTP 요청에 응답하며 PostgreSQL에 연결 가능한지를 확인한다.

다음은 확인하지 않는다.

- 외부 PG에서 결제가 성공하는가?
- 중복 결제를 막는가?
- Webhook을 안전하게 처리하는가?
- `UNKNOWN` 결제가 최종 상태로 바뀌는가?

실제 실행에서 health는 `UP`이었지만 현재 프로젝트에는 결제 기능 자체가 없다.
따라서 `UP`만 보고 결제가 성공한다고 말할 수 없다.

### 5. 단위 테스트와 integration test는 무엇이 다를까?

장난감 자동차에 비유하면 단위 테스트는 부품 하나를 검사하는 것이다.

```text
바퀴가 잘 돌아가는가?
버튼을 누르면 불이 켜지는가?
배터리 계산이 정확한가?
```

프로그램에서는 가격 계산, 주문 수량 검사, 허용된 상태 변경처럼 작은 규칙을 빠르게
검사한다.

Integration test는 장난감 자동차를 모두 조립한 뒤 실제로 움직이는지 검사한다.

```text
Spring Boot
  → JDBC
  → PostgreSQL
  → Flyway
  → HTTP health endpoint
```

현재 실제 실행에서는 integration test 2개가 실행됐고 실패와 오류가 없었다. 하지만
재고, 결제와 환불을 검사하는 테스트는 아직 없다.

### 6. Application validation과 DB constraint는 무엇이 다를까?

Application validation은 매점 입구에서 학생의 주문을 확인하는 선생님과 같다.

```text
수량이 0개인가?
존재하지 않는 상품인가?
입력 형식이 잘못됐는가?
```

잘못된 요청이면 “수량은 1개 이상이어야 합니다”처럼 이해하기 쉬운 이유를 알려 줄
수 있다. 하지만 다른 입구로 들어오거나 검사 코드가 빠지면 잘못된 값이 통과할 수
있다.

DB constraint는 창고 문에 설치된 단단한 자물쇠와 같다.

```sql
CHECK (quantity >= 0)
UNIQUE (idempotency_key)
```

어떤 길로 DB에 들어와도 마지막에 규칙을 검사한다. 그래서 둘 중 하나를 고르는 것이
아니라 함께 사용한다.

```text
Application validation → 빠르고 이해하기 쉬운 오류
DB constraint          → 잘못된 데이터 저장을 최종 차단
```

현재 프로젝트에는 아직 재고나 결제 table이 없으므로 이런 constraint도 구현하지
않았다.

### 7. Timeout을 결제 실패라고 생각하면 어떤 문제가 생길까?

치킨집에 전화로 주문했다고 생각해 보자.

```text
1. 내가 치킨을 주문한다.
2. 치킨집은 주문을 받고 치킨을 만들기 시작한다.
3. 치킨집이 “주문 완료”라고 말하려는 순간 전화가 끊긴다.
4. 나는 완료 응답을 듣지 못한다.
```

응답을 듣지 못했어도 치킨집은 이미 치킨을 만들고 있다. 이때 주문이 실패했다고
생각하고 다시 주문하면 치킨이 두 마리 올 수 있다.

결제도 같다.

```text
Application이 결제 A 요청
→ PG에서 결제 A 성공
→ 성공 응답 유실
→ Application에서 timeout 발생
→ 결제 A를 FAILED로 잘못 처리
→ 결제 B 재시도
→ 결제 B도 성공
→ 실제 결제가 두 번 발생
```

Timeout이 알려 주는 것은 “정해진 시간 안에 응답을 받지 못했다”는 사실뿐이다.
PG가 결제를 처리하지 않았다는 증거가 아니다.

결과를 모르면 `FAILED`가 아니라 `UNKNOWN`으로 기록하고 PG 조회나 Webhook으로 실제
결과를 확인해야 한다.

```text
UNKNOWN
  → PG 상태 다시 확인
  → SUCCEEDED 또는 FAILED로 최종 결정
```

### 8. 실행 결과는 어떻게 해야 다른 사람도 다시 확인할 수 있을까?

과학 실험에서 “성공했어요”라고만 말하면 다른 사람이 같은 실험을 할 수 없다.

```text
어떤 준비물을 사용했는가?
어떤 순서로 실행했는가?
언제 실행했는가?
어떤 결과가 나왔는가?
실제로 실험했는가?
확인하지 않은 부분은 무엇인가?
```

프로그램 테스트도 다음 정보를 함께 기록한다.

- Source code revision
- 실행 날짜와 timezone
- Java, Docker와 PostgreSQL 버전
- 사용한 환경 파일
- 정확한 실행 명령
- 테스트 수와 실패 수
- 기존 DB인지 빈 DB인지
- 아직 검사하지 않은 기능

이번에 처음 실행한 기본 명령은 `BUILD SUCCESSFUL`이었지만 다음 표시가 있었다.

```text
:test UP-TO-DATE
```

이것은 Gradle이 예전 결과를 보고 테스트를 다시 실행하지 않았다는 뜻이다.
`BUILD SUCCESSFUL`만 보고 이번에 테스트했다고 말하면 안 된다.

그래서 `--rerun-tasks`를 사용해 실제로 다시 실행했다.

```powershell
docker compose --env-file .env.example --profile test run --build --rm test `
  gradle test --no-daemon --rerun-tasks
```

그 결과는 다음과 같았다.

```text
4 actionable tasks: 4 executed
테스트 2개
실패 0개
오류 0개
```

정확한 보고는 “PostgreSQL 17.11을 사용하는 integration test 2개를 실제로 다시
실행했고 실패와 오류 없이 통과했다”다. 결제 기능은 존재하지도, 테스트하지도
않았으므로 “모든 결제 기능이 정상이다”라고 말하면 안 된다.

### 쉬운 설명의 최종 정리

이번 실행으로 직접 확인한 것은 다음이다.

```text
PostgreSQL 연결 가능
Flyway V1 기록 정상
payment_orchestration schema 존재
Health endpoint UP
Integration test 2개 통과
```

아직 확인하지 않은 것은 다음이다.

```text
재고가 음수가 되지 않는가?
중복 결제가 발생하지 않는가?
Timeout을 UNKNOWN으로 처리하는가?
중복 Webhook을 한 번만 처리하는가?
재시작 후 결제 상태가 정상으로 수렴하는가?
```

> 프로그램이 켜졌다는 것, DB에 연결됐다는 것, 결제가 안전하다는 것은 서로 다른
> 주장이다. 실제로 확인한 만큼만 정확하게 말해야 한다.

## 1. Application 컨테이너는 PostgreSQL을 왜 `localhost`가 아닌 `postgres`로 찾는가?

### 짧은 답

Container마다 자기만의 network namespace가 있기 때문이다. Application 컨테이너
안에서 `localhost`는 application 컨테이너 자신을 가리킨다. PostgreSQL은 별도의
container에서 실행되므로 Compose network가 제공하는 service 이름 `postgres`로
접속해야 한다.

### 코드 근거

`compose.yaml`의 application datasource URL은 다음과 같다.

```yaml
SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/${POSTGRES_DB:?POSTGRES_DB is required}
```

여기서 `postgres`는 `services.postgres`의 service 이름이다. Compose는 같은 project의
service를 기본 network에 연결하고 service 이름을 DNS 이름으로 제공한다.

반면 `application.yml`의 기본값은 다음과 같다.

```yaml
url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/payment_orchestration}
```

이 기본값은 application을 host에서 직접 실행하고 host의 5432 port로 PostgreSQL에
접속할 때 사용할 수 있다. Compose 안에서는 `SPRING_DATASOURCE_URL` 환경 변수가
기본값을 덮어쓴다.

`ports`의 다음 설정도 구분해야 한다.

```yaml
ports:
  - "${POSTGRES_PORT:-5432}:5432"
```

이것은 host에서 PostgreSQL container로 접근하는 port mapping이다. Application과
PostgreSQL container 사이의 통신은 같은 Compose network에서 `postgres:5432`를
사용한다.

### 실행 근거

Test container에서 이름 해석을 실행한 결과는 다음과 같았다.

```text
::1 localhost localhost.localdomain
127.0.0.1 localhost localhost.localdomain
172.18.0.2 postgres
```

`localhost`는 test container 자신의 loopback 주소이고 `postgres`는 PostgreSQL
container의 Compose network 주소로 해석됐다. Test와 application은 동일하게
`postgres` service 이름을 datasource hostname으로 사용한다.

### 흔한 실수

- Container 안에서 host DB를 찾으면서 무조건 `localhost`를 사용한다.
- Host port와 container port를 혼동한다.
- PostgreSQL container IP를 설정 파일에 직접 저장한다. Container IP는 다시 만들면
  바뀔 수 있으므로 service 이름을 사용해야 한다.

## 2. DB 데이터를 유지하는 container와 volume의 차이는 무엇인가?

### 짧은 답

Container는 PostgreSQL process를 실행하는 교체 가능한 실행 단위다. Volume은
PostgreSQL 데이터 파일을 container lifecycle과 분리해 보존하는 저장소다.

### 코드 근거

`compose.yaml`은 named volume을 PostgreSQL 데이터 경로에 연결한다.

```yaml
services:
  postgres:
    volumes:
      - postgres-data:/var/lib/postgresql/data

volumes:
  postgres-data:
```

PostgreSQL은 `/var/lib/postgresql/data`에 table, index, transaction log와 catalog 등
데이터베이스 파일을 기록한다. 이 경로가 named volume과 연결돼 있으므로 container가
교체돼도 같은 volume을 다시 연결하면 데이터가 남는다.

### 실행 근거

실행 중인 PostgreSQL container를 inspect한 결과는 다음과 같았다.

```text
ContainerId=12972daf31c49a7c55ee550901795f7ac015110ec48098c1460722aab75a4d32
Mounts=volume:payment-orchestration-lab_postgres-data->/var/lib/postgresql/data
```

Volume을 별도로 inspect한 결과는 다음과 같았다.

```text
Volume=payment-orchestration-lab_postgres-data
Mountpoint=/var/lib/docker/volumes/payment-orchestration-lab_postgres-data/_data
```

Container ID와 volume 이름이 별도로 존재하며 volume이 container 내부 데이터 경로에
mount된 것을 확인할 수 있다.

### 명령별 lifecycle 차이

```powershell
docker compose --env-file .env.example down
```

Container와 network는 정리하지만 named volume은 기본적으로 유지한다.

```powershell
docker compose --env-file .env.example down --volumes
```

Container뿐 아니라 Compose project의 named volume도 삭제한다. 현재 로컬 DB 데이터를
초기화하려는 경우에만 사용해야 한다.

Volume은 backup이 아니다. 실수로 `down --volumes`를 실행하거나 storage가 손상되면
데이터를 잃을 수 있다. 운영 환경에서는 별도의 backup과 restore 검증이 필요하다.

## 3. Flyway가 이미 적용한 파일을 왜 수정하면 안 되는가?

### 짧은 답

Versioned migration은 여러 환경이 같은 변경 이력을 순서대로 적용했다는 계약이다.
이미 적용한 파일을 수정하면 과거에 실행된 SQL과 현재 저장소의 SQL이 달라지고,
환경마다 schema 생성 과정이 달라진다.

### 동작 배경

Flyway는 versioned migration을 실행할 때 `flyway_schema_history`에 version, description,
script, checksum, 성공 여부 등을 기록한다. 다음 실행에서는 현재 파일의 checksum과
기록된 checksum을 비교해 변경 여부를 검증한다.

현재 적용 기록을 조회한 결과는 다음과 같았다.

```text
version=1
description=create application schema
type=SQL
checksum=621857564
success=true
```

V1 파일을 수정하면 새 파일에서 계산된 checksum이 기존 `621857564`와 달라질 수
있다. Flyway validation은 이를 migration 변경으로 감지하고 일반적으로 application
시작을 실패시킨다.

### 수정하면 생기는 문제

예를 들어 개발 DB에는 원래 V1이 적용됐고 새 개발자는 수정된 V1로 빈 DB를 만든다고
가정한다.

```text
기존 DB: 원래 V1로 만들어진 schema
새 DB:   수정된 V1로 만들어진 schema
```

두 DB는 모두 version 1처럼 보이지만 실제 구조가 다를 수 있다. 장애 재현, 운영 배포,
rollback 판단과 감사가 어려워진다.

### 올바른 방법

이미 공유 환경에 적용된 V1을 고치지 않고 새로운 migration을 추가한다.

```text
V1__create_application_schema.sql
V2__add_required_change.sql
```

아직 어디에도 적용되지 않은 migration을 개발 중 수정하는 것과 이미 공유 DB에
적용된 migration을 수정하는 것은 구분한다. Flyway `repair`로 checksum만 맞추는 것도
정당한 schema 변경 방법이 아니다. 실제 DB 상태와 변경 이유를 확인한 뒤 제한적으로
사용해야 한다.

### 현재 검증 범위

2026-09-06 실행에서는 Flyway가 PostgreSQL 17.11에 연결해 migration 1개를 검증했고
현재 `public` schema version이 1이며 추가 migration이 필요 없다고 보고했다. 이번
실행은 기존 volume을 사용했으므로 빈 DB에 V1을 최초 적용하는 과정까지 다시
검증하지는 않았다.

## 4. Health가 `UP`이어도 결제 성공을 보장하지 않는 이유는 무엇인가?

### 짧은 답

Health endpoint는 정의된 health indicator가 현재 응답 가능한지만 검사한다. 현재
프로젝트에는 결제 기능과 외부 PG 연동 자체가 없으므로 `UP`은 결제 성공 여부를
검사할 수 없다.

### 코드 근거

`application.yml`은 health probe를 활성화하고 외부 노출 endpoint를 health와 info로
제한한다.

```yaml
management:
  endpoint:
    health:
      probes:
        enabled: true
      show-details: never
  endpoints:
    web:
      exposure:
        include: health,info
```

JDBC datasource가 있으므로 현재 health에는 DB 연결 가능성이 반영된다. 그러나 다음은
구현돼 있지 않거나 health가 직접 검증하지 않는다.

- 외부 PG 승인 API
- Payment 상태 머신
- Idempotency 처리
- Webhook 처리
- `UNKNOWN` reconciliation
- 주문당 성공 결제 최대 하나라는 DB constraint

### 실행 근거

2026-09-06 12:18:54 KST에 다음 요청은 `UP`을 반환했다.

```powershell
(Invoke-RestMethod -Uri 'http://localhost:8080/actuator/health').status
```

```text
UP
```

Integration test도 `/actuator/health` 응답 Map에 `status=UP`이 있는지만 확인한다.
결제 API를 호출하거나 PG 상태를 검사하지 않는다.

### 올바른 해석

- Liveness: application process를 재시작해야 하는가?
- Readiness: 지금 요청을 받을 준비가 됐는가?
- Business health: 결제와 reconciliation이 업무적으로 정상인가?

이 세 가지는 서로 다르다. PostgreSQL과 HTTP server가 정상이어도 PG가 거절하거나,
PG timeout이 발생하거나, `UNKNOWN` backlog가 장기간 쌓일 수 있다.

## 5. 단위 테스트와 integration test는 무엇을 각각 검증하는가?

### 단위 테스트

단위 테스트는 외부 인프라 없이 하나의 작은 규칙이나 객체를 빠르고 결정적으로
검증한다.

예시는 다음과 같다.

- 주문 금액 계산
- 허용된 Payment 상태 전이
- 잘못된 수량 거부
- Retry 횟수 계산

장점은 빠르고 실패 원인을 좁히기 쉽다는 것이다. 단점은 mock이나 fake가 실제
PostgreSQL, Spring configuration, HTTP 직렬화와 다르게 동작할 수 있다는 것이다.

### Integration test

Integration test는 여러 실제 구성 요소가 함께 올바르게 연결되는지 검증한다.

현재 `InfrastructureIntegrationTest`에는 다음 특징이 있다.

- `@SpringBootTest(webEnvironment = RANDOM_PORT)`로 Spring application을 실행한다.
- `JdbcTemplate`로 실제 PostgreSQL에 SQL을 실행한다.
- `TestRestTemplate`로 실제 HTTP health endpoint를 호출한다.
- PostgreSQL 연결, application schema, Flyway V1 성공 기록을 검증한다.

강제 재실행 결과 XML은 다음을 기록했다.

```text
tests=2
skipped=0
failures=0
errors=0
time=7.942 seconds
```

실행된 테스트는 다음 두 개다.

```text
postgresIsReachableAndInitialMigrationWasApplied()
applicationHealthEndpointIsUp()
```

현재 저장소에는 별도의 비즈니스 단위 테스트가 없다. 따라서 단위 테스트의 역할은
개념과 향후 기준이며 현재 통과 결과로 주장할 수 없다.

## 6. Application validation과 DB constraint는 어떻게 다른가?

### Application validation

Application code가 요청을 검사하고 비즈니스 로직을 실행하기 전에 거부하는 것이다.

```text
quantity가 1 미만이면 사용자에게 400 응답
지원하지 않는 상태 전이면 명확한 오류 반환
```

장점은 빠르고 사용자에게 구체적인 오류를 제공하기 쉽다는 것이다. 하지만 다음
한계가 있다.

- 다른 application code path가 검증을 빼먹을 수 있다.
- 직접 SQL이나 관리 도구가 검증을 우회할 수 있다.
- 두 요청이 동시에 같은 값을 읽으면 둘 다 검증을 통과할 수 있다.

### DB constraint

Database가 저장되는 최종 데이터 형태를 강제로 제한하는 것이다.

```sql
CHECK (quantity >= 0)
UNIQUE (idempotency_key)
FOREIGN KEY (order_id) REFERENCES orders(id)
```

DB로 들어오는 모든 쓰기 경로에 적용되고 동시 transaction에도 최종 방어선이 된다.
대신 사용자 친화적인 오류로 변환하려면 application의 예외 처리가 필요하다.

### 함께 사용하는 이유

```text
Application validation → 빠르고 이해하기 쉬운 실패
DB constraint          → 우회와 경쟁 조건에도 최종 무결성 보호
```

재고가 0 미만이 되지 않는 불변식은 application의 수량 검증만으로 충분하지 않다.
Conditional update와 `CHECK (quantity >= 0)` 같은 DB 방어를 함께 검토해야 한다.

현재 V1은 schema만 생성하고 business table이나 constraint를 만들지 않는다. 따라서
이 답은 이후 설계 원칙이며 현재 프로젝트가 재고나 결제 불변식을 보장한다는 뜻이
아니다.

## 7. Timeout을 결제 실패로 단정하면 어떤 중복 결제가 발생할 수 있는가?

### 대표 시나리오

```text
1. Application이 PG에 결제 A를 요청한다.
2. PG는 카드 승인을 성공시키고 결제를 완료한다.
3. 성공 응답이 network에서 유실된다.
4. Application은 timeout만 관측한다.
5. Application이 결제 A를 FAILED로 잘못 저장한다.
6. 사용자 또는 application이 새 결제 B를 재시도한다.
7. PG에서 결제 B도 성공한다.
8. 하나의 주문에 실제 승인 두 건이 존재한다.
```

Timeout이 알려 주는 사실은 정해진 시간 안에 응답을 받지 못했다는 것뿐이다. PG가
요청을 처리하지 않았다는 증거가 아니다.

### 안전한 방향

- 결과를 확정할 수 없으면 local payment를 `UNKNOWN`으로 기록한다.
- 동일한 PG idempotency key를 안전하게 재사용할 수 있는지 PG 계약을 확인한다.
- PG 조회 API와 webhook으로 authoritative state를 확인한다.
- Reconciliation이 재시작 이후에도 `UNKNOWN`을 최종 상태로 수렴시킨다.
- 주문당 성공 결제 최대 하나를 local DB constraint와 상태 전이로도 보호한다.

PG idempotency key만으로 모든 문제가 해결된다고 가정하면 안 된다. Key의 scope,
보존 기간, 동일 key와 다른 payload 처리 등 PG 계약을 확인해야 한다.

현재 프로젝트에는 PG 연동이나 Payment 모델이 없으므로 이 실패 시나리오는 아직
재현하거나 방지하지 않는다.

## 8. 실행한 명령과 실제 테스트 결과를 재현 가능하게 제시할 수 있는가?

### 필요한 정보

재현 가능한 보고에는 최소한 다음이 있어야 한다.

- Source revision
- 실행 시각과 timezone
- Docker, Java, Spring Boot, PostgreSQL 버전
- 사용한 환경 파일
- 정확한 명령
- 종료 코드와 테스트 수
- Cache로 실제 테스트가 생략되지 않았는지 여부
- 기존 DB인지 빈 DB인지
- 아직 검증하지 않은 범위

### 이번 실행 정보

```text
Source revision: 9e9496120cba20cb2c35e59cf89e7b8ed3149a36
실행일: 2026-09-06
Timezone: Asia/Seoul
Docker client/server: 29.7.2
Docker Compose: v5.5.0
Java: 21.0.12
Spring Boot: 3.5.6
PostgreSQL: 17.11
환경 파일: .env.example
```

### 문서화된 기본 명령 결과

```powershell
docker compose --env-file .env.example --profile test run --build --rm test
```

이 명령은 종료 코드 0과 `BUILD SUCCESSFUL`을 반환했지만 Gradle 출력은 다음과
같았다.

```text
> Task :test UP-TO-DATE
4 actionable tasks: 4 up-to-date
```

즉 이전 build output 때문에 이번 명령에서는 테스트 메서드가 다시 실행되지 않았다.
`BUILD SUCCESSFUL`만 보고 “이번에 테스트 2개를 실행했다”고 보고하면 안 된다.

### 실제 강제 재실행 명령과 결과

```powershell
docker compose --env-file .env.example --profile test run --build --rm test `
  gradle test --no-daemon --rerun-tasks
```

결과는 다음과 같았다.

```text
> Task :test
BUILD SUCCESSFUL in 1m 9s
4 actionable tasks: 4 executed
```

생성된 XML 결과로 테스트 2개, 실패 0개, 오류 0개를 확인했다.

### 재현 범위의 한계

- 기존 `postgres-data` named volume을 사용했다.
- Flyway V1은 새로 적용되지 않고 checksum validation 후 up-to-date로 판단됐다.
- 빈 DB 최초 migration은 이번 실행에서 검증하지 않았다.
- 결제, 재고, 환불 business test는 존재하지 않는다.
- 동시성, failure, performance test는 실행하지 않았다.
- Source revision 뒤에 존재하는 문서 변경은 아직 해당 revision에 포함되지 않는다.

이처럼 성공 결과뿐 아니라 캐시 여부, 기존 데이터와 비보장 범위를 함께 제시해야
다른 개발자가 같은 의미의 검증을 반복할 수 있다.

## 최종 정리

현재 실행 결과가 직접 보장한 것은 다음 네 가지다.

- 실제 PostgreSQL 17.11에 연결했다.
- Flyway V1 적용 기록과 checksum이 유효했다.
- `payment_orchestration` schema가 존재했다.
- Application health endpoint가 `UP`을 반환했다.

다음은 아직 보장하지 않는다.

- 빈 DB에서 모든 migration의 최초 적용
- 재고가 음수가 되지 않는 불변식
- 주문당 성공 결제 최대 하나
- Timeout 시 `UNKNOWN` 처리와 최종 수렴
- 중복 webhook과 중복 환불 방지
- 복수 application instance의 correctness
