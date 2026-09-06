# 2년차 개발자 학습 가이드

## 1. 핵심 목표

2년차 개발자의 핵심 목표는 프로젝트를 혼자 실행하고 기본적인 변경을 수행하며,
문제가 생겼을 때 애플리케이션, 데이터베이스, Docker 중 어느 영역의 문제인지
좁혀 가는 것이다.

다음 작업을 도움 없이 수행할 수 있는 상태를 목표로 한다.

- Docker Compose로 DB와 애플리케이션을 실행하고 종료한다.
- HTTP 요청이 Controller, Service, Repository, PostgreSQL로 이어지는 흐름을 설명한다.
- 설정 파일과 환경 변수 중 실제로 어떤 값이 적용되는지 찾는다.
- Flyway migration을 추가하고 빈 DB에서 적용 여부를 확인한다.
- 단위 테스트와 integration test의 목적을 구분한다.
- health endpoint와 로그를 이용해 기본 장애를 진단한다.
- 실행하지 않은 테스트를 통과했다고 보고하지 않는다.

## 2. 왜 이것을 알아야 하는가

애플리케이션 코드는 혼자 실행되지 않는다. Java runtime, Spring 설정, DB schema,
네트워크, 컨테이너가 맞물려야 하나의 요청이 처리된다. 초반에는 비즈니스 코드가
틀렸다고 생각하기 쉽지만 실제 원인은 잘못된 DB 주소, 적용되지 않은 migration,
사용 중인 port, 남아 있는 volume인 경우가 많다.

따라서 기능 구현 능력과 함께 실행 환경을 관찰하고 실패 원인을 분리하는 능력이
필요하다.

## 3. 핵심 개념

### 3.1 Java 21

모든 Java 21 기능을 외우기보다 다음 기본기를 확실히 한다.

- class, object, interface, constructor
- 접근 제한자와 package
- 예외 처리와 stack trace 읽기
- `List`, `Set`, `Map`의 차이
- `Optional`의 용도와 잘못된 남용
- 불변 객체와 `record`
- lambda와 stream의 기본 동작
- thread와 shared mutable state의 기본 위험

요청 DTO는 다음처럼 불변 값으로 표현할 수 있다.

```java
public record CreateOrderRequest(long productId, int quantity) {
}
```

`record`를 사용해도 `quantity > 0`이 자동으로 보장되지는 않는다. 객체 형태와
비즈니스 검증은 별개의 책임이다.

### 3.2 Spring Boot 요청 처리 흐름

일반적인 요청 흐름은 다음과 같다.

```text
HTTP 요청
  → Controller
  → Service
  → Repository
  → PostgreSQL
  → HTTP 응답
```

- Controller는 HTTP 입력과 응답 형식을 처리한다.
- Service는 비즈니스 규칙과 작업 순서를 처리한다.
- Repository는 SQL과 데이터 접근을 담당한다.
- DB는 데이터를 저장하고 constraint로 무결성을 보호한다.

Controller에 SQL과 모든 비즈니스 로직을 넣으면 각 책임을 독립적으로 테스트하기
어렵다.

### 3.3 의존성 주입

Spring이 관리해야 하는 객체는 생성자 주입을 기본으로 사용한다.

```java
@Service
public class OrderService {
    private final OrderRepository orderRepository;

    public OrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }
}
```

생성자 주입은 객체가 반드시 필요로 하는 의존성을 명확히 보여 주며 테스트에서
대체 구현을 전달하기도 쉽다.

### 3.4 설정과 환경 변수

현재 기본 datasource 설정은 `src/main/resources/application.yml`에 있다. Compose는
다음 환경 변수를 application 컨테이너에 전달해 기본값을 덮어쓴다.

- `SPRING_DATASOURCE_URL`
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`

Docker 컨테이너 내부에서 `localhost`는 해당 컨테이너 자신이다. application이
PostgreSQL에 접근할 때는 Compose service 이름인 `postgres`를 hostname으로 사용한다.

```text
jdbc:postgresql://postgres:5432/payment_orchestration
```

비밀번호, API key, token을 Java 코드나 Git에 커밋하지 않는다. `.env.example`에는
로컬 예시값만 둔다.

### 3.5 PostgreSQL 기초

다음 SQL과 DB 객체의 역할을 이해한다.

- `SELECT`, `INSERT`, `UPDATE`, `DELETE`
- `WHERE`, `JOIN`, `GROUP BY`, `ORDER BY`
- primary key와 foreign key
- `NOT NULL`, unique constraint, check constraint
- index가 읽기와 쓰기에 미치는 영향
- transaction의 commit과 rollback

애플리케이션 검증과 DB constraint는 목적이 다르다.

```sql
quantity INTEGER NOT NULL CHECK (quantity >= 0)
```

애플리케이션은 사용자에게 친절한 오류를 제공하고, DB constraint는 다른 실행
경로나 실수가 있어도 잘못된 데이터가 저장되는 것을 마지막으로 막는다.

### 3.6 Transaction 기초

Transaction은 여러 DB 변경을 하나의 작업 단위로 묶는다.

```text
주문 저장
  → 재고 감소
  → 모두 성공하면 commit
  → 중간 실패 시 rollback
```

알아야 할 기본 원칙은 다음과 같다.

- commit된 데이터만 최종 결과가 된다.
- rollback되면 해당 transaction의 변경이 취소된다.
- transaction을 오래 유지하면 DB connection과 lock을 오래 점유한다.
- `@Transactional`을 붙였다고 모든 동시성 문제가 해결되지는 않는다.
- DB transaction은 외부 PG의 작업까지 rollback해 주지 못한다.

### 3.7 Flyway

Flyway는 DB schema 변경 이력을 순서대로 관리한다.

```text
V1__create_application_schema.sql
V2__create_order_table.sql
V3__add_order_status.sql
```

기본 원칙은 다음과 같다.

- 공유 DB에 한 번 적용된 migration을 수정하지 않는다.
- 변경은 새로운 버전 migration으로 추가한다.
- 파일이 존재하는 것과 DB에 성공적으로 적용된 것은 다르다.
- 현재 데이터가 남아 있는 DB와 빈 DB 모두 고려한다.
- migration 실패 로그에서 실제 실패한 SQL과 원인을 찾는다.

현재 V1은 `payment_orchestration` schema만 생성한다. 이후 테이블을 추가할 때는
`payment_orchestration.orders`처럼 대상 schema를 명확히 지정해야 한다.

### 3.8 Docker와 Compose

다음 용어를 구분한다.

- Image: 컨테이너 실행에 필요한 파일과 설정
- Container: image에서 실행된 프로세스
- Volume: 컨테이너와 별도로 유지되는 데이터
- Port mapping: 호스트 port와 컨테이너 port의 연결
- Environment variable: 실행 시 전달하는 설정
- Health check: 컨테이너가 기본 응답 가능한지 확인하는 검사

현재 service의 관계는 다음과 같다.

```text
postgres
  ├── application
  └── test
```

`depends_on`의 health 조건은 PostgreSQL이 기본 연결을 받을 준비가 된 뒤 application과
test를 시작하게 한다. 비즈니스 기능 전체가 정상이라는 뜻은 아니다.

### 3.9 Actuator health

`/actuator/health`의 `UP`은 현재 대략 다음을 의미한다.

- Spring Boot가 실행 중이다.
- HTTP endpoint가 응답한다.
- datasource health indicator가 PostgreSQL에 연결할 수 있다.

다음은 의미하지 않는다.

- 주문과 결제가 정상 처리된다.
- 외부 PG가 정상이다.
- connection pool에 충분한 여유가 있다.
- 모든 데이터가 비즈니스 규칙을 만족한다.

Health 결과의 범위를 과장하지 않는 습관이 중요하다.

### 3.10 단위 테스트와 integration test

단위 테스트는 작은 로직을 빠르게 검증한다.

```text
가격 1,000원 × 수량 3 = 3,000원
```

Integration test는 실제 구성 요소의 연결을 검증한다.

```text
Spring configuration
  → JDBC driver
  → PostgreSQL
  → Flyway
  → HTTP endpoint
```

현재 `InfrastructureIntegrationTest`는 실제 PostgreSQL 연결, schema 존재, Flyway V1
성공 기록, health endpoint를 검증한다. 재고, 결제, 환불 불변식은 검증하지 않는다.

### 3.11 ADR, evidence, runbook

- ADR은 중요한 결정과 그 이유를 기록한다.
- Evidence는 실제로 실행한 명령과 결과를 기록한다.
- Runbook은 장애 확인과 복구 절차를 기록한다.

문서가 존재하는 것만으로 목적을 달성한 것은 아니다. 처음 보는 사람이 문서를
따라 같은 결과를 재현할 수 있어야 한다.

## 4. 상세 실습 방법

### 4.1 전체 환경 실행

```powershell
docker compose --env-file .env.example up --build
```

확인할 내용은 다음과 같다.

1. PostgreSQL health check가 먼저 통과하는가.
2. application이 Flyway migration을 실행하는가.
3. application이 8080 port에서 시작되는가.
4. `/actuator/health`가 `UP`을 반환하는가.

### 4.2 상태와 로그 확인

```powershell
docker compose --env-file .env.example ps
docker compose --env-file .env.example logs application
docker compose --env-file .env.example logs postgres
```

로그를 볼 때 마지막 예외만 읽지 말고 첫 번째 `Caused by`와 그 위의 맥락을 함께
확인한다.

### 4.3 Integration test 실행

```powershell
docker compose --env-file .env.example --profile test run --build --rm test
```

보고할 때는 다음처럼 사실을 구분한다.

```text
나쁜 보고: 테스트는 정상일 것입니다.
좋은 보고: 위 명령을 실행했고, 실행 시각과 결과는 evidence에 기록했습니다.
```

### 4.4 안전한 종료와 초기화

컨테이너만 종료한다.

```powershell
docker compose --env-file .env.example down
```

로컬 DB 데이터까지 초기화할 때만 volume을 삭제한다.

```powershell
docker compose --env-file .env.example down --volumes
```

`down`과 `down --volumes`의 차이를 모르는 상태에서 후자를 습관적으로 실행하지
않는다.

### 4.5 기본 장애 진단 순서

1. `docker compose ps`로 각 container 상태를 확인한다.
2. application 로그에서 최초 예외를 확인한다.
3. PostgreSQL health와 로그를 확인한다.
4. datasource URL의 hostname, port, DB 이름을 확인한다.
5. username과 password가 양쪽에서 같은지 확인한다.
6. Flyway가 어느 SQL에서 실패했는지 확인한다.
7. 호스트 port가 이미 사용 중인지 확인한다.
8. 이전 volume 상태가 영향을 주는지 판단한다.

문제를 해결하기 전에 재현 명령과 원래 오류를 기록한다. 여러 설정을 한꺼번에
바꾸면 어느 변경이 문제를 해결했는지 알 수 없다.

## 5. 결제 도메인에서 미리 알아둘 개념

이 개념들은 현재 Phase에서 구현하지 않지만 이후 코드를 이해하기 위한 배경이다.

### Idempotency

같은 요청이 반복되어도 실제 비즈니스 효과가 한 번만 발생해야 한다.

```text
동일 결제 요청 3회 → 실제 성공 결제 최대 1개
```

### Timeout과 실패

PG 요청 timeout은 응답을 받지 못했다는 뜻이다. PG가 결제를 완료하고 응답만
유실했을 수 있으므로 곧바로 `FAILED`로 확정하면 안 된다.

### 중복과 순서가 바뀐 webhook

Webhook은 중복되거나 생성 순서와 다른 순서로 도착할 수 있다. 따라서 이벤트 ID,
허용된 상태 전이, 중복 처리 방지가 필요하다.

## 6. 완료 기준

다음 질문에 코드와 실행 결과를 근거로 답할 수 있어야 한다.

- application 컨테이너는 PostgreSQL을 왜 `localhost`가 아닌 `postgres`로 찾는가?
- DB 데이터를 유지하는 container와 volume의 차이는 무엇인가?
- Flyway가 이미 적용한 파일을 왜 수정하면 안 되는가?
- health가 `UP`이어도 결제 성공을 보장하지 않는 이유는 무엇인가?
- 단위 테스트와 integration test는 무엇을 각각 검증하는가?
- application validation과 DB constraint는 어떻게 다른가?
- timeout을 결제 실패로 단정하면 어떤 중복 결제가 발생할 수 있는가?
- 실행한 명령과 실제 테스트 결과를 재현 가능하게 제시할 수 있는가?

