# ADR-0002: Container and execution environment concepts

- Status: Accepted
- Date: 2026-09-06
- Related: [ADR-0001: Bootstrap stack](./0001-bootstrap-stack.md)

## Context

현재 프로젝트는 Docker image와 Docker Compose를 사용해 PostgreSQL, application,
integration test를 실행한다. 이후 배포와 운영을 논의하려면 Docker, container,
Compose, orchestrator가 각각 어떤 문제를 해결하는지 구분할 필요가 있다.

또한 container를 사용하지 않는 환경도 실행 파일, 설정, process lifecycle, network,
storage를 관리해야 한다. Container 사용 여부는 운영 문제가 사라지는지의 차이가
아니라, 그 문제를 어떤 단위와 도구로 관리하는지의 차이다.

이 ADR은 현재 Phase에 새로운 배포 기술을 도입하지 않는다. 용어, 실행 모델,
trade-off와 현재 프로젝트의 선택 범위를 정리한다.

## Decision

현재 bootstrap과 로컬 실험 환경은 ADR-0001에서 결정한 Docker Compose 방식을
유지한다.

- Application은 Dockerfile로 OCI image를 만든다.
- PostgreSQL, application, test의 로컬 실행 관계는 Compose로 정의한다.
- Compose를 production orchestrator로 간주하지 않는다.
- Kubernetes 등의 orchestrator는 현재 도입하지 않는다.
- Container를 사용하지 않는 실행 방식은 개념과 대안으로 문서화하되 현재 Phase의
  추가 실행 경로로 구현하지 않는다.
- 실제 운영 요구, 복수 host, 자동 복구, rolling deployment처럼 관찰된 문제가
  생겼을 때 orchestrator 또는 다른 배포 방식을 별도 ADR에서 검토한다.

## Core mental model

전체 관계는 다음과 같이 이해한다.

```text
Application source
  → build
Application artifact (app.jar)
  ├── Host JVM에서 직접 실행
  └── Container image에 포함
        → Container runtime이 container 실행
              ├── Docker Compose가 여러 container를 로컬에서 구성
              └── Orchestrator가 여러 host의 container를 지속적으로 관리
```

`app.jar`가 실제 application artifact다. Container는 이 artifact와 실행 환경을
함께 포장하고 격리하는 방법이다. Compose와 orchestrator는 application 코드를
대체하지 않고 여러 process의 실행과 관계를 관리한다.

## Beginner-friendly summary

세 가지 방식은 식당을 운영하는 방법에 비유할 수 있다.

| 방식 | 식당 비유 | 잘 맞는 상황 |
|---|---|---|
| Host/VM 직접 실행 | 한 주방에 도구를 직접 설치하고 요리 | 프로그램과 서버가 적을 때 |
| Docker Compose | 요리별 조리 상자를 한 주방에서 함께 사용 | 개발·테스트 환경을 똑같이 만들 때 |
| Orchestrator | 여러 지점을 관리하는 본사 | 서버와 프로그램이 많고 자동 복구가 필요할 때 |

### Host/VM에서 직접 실행

컴퓨터에 Java와 PostgreSQL을 직접 설치하고 application을 실행하는 방식이다.

```text
컴퓨터 또는 VM
├── Java 21
├── PostgreSQL
└── app.jar
```

식당에 비유하면 한 주방에 냉장고, 오븐과 조리 도구를 직접 설치하는 것이다.

장점은 구조가 단순하고 container 설정이 필요 없다는 것이다. 프로그램과 서버가
몇 개 없다면 관리하기 쉽다.

단점은 컴퓨터마다 Java와 PostgreSQL 버전이 달라질 수 있다는 것이다. 새 서버를
만들 때 필요한 프로그램과 설정을 다시 설치해야 하며, 자동 시작, 재시작, 로그와
배포도 별도로 관리해야 한다.

다음 상황에서 우선 검토한다.

- 프로그램과 서버가 매우 적다.
- 실행 환경이 자주 바뀌지 않는다.
- 회사가 이미 VM provisioning과 운영 자동화를 갖추고 있다.
- 보안이나 운영 정책 때문에 Docker를 사용할 수 없다.

### Docker Compose

프로그램마다 필요한 실행 환경을 container라는 상자에 담고 여러 상자를 한꺼번에
실행하는 방식이다.

```text
한 대의 컴퓨터
├── PostgreSQL container
├── Application container
└── Test container
```

식당에 비유하면 각 요리의 재료와 도구를 별도 조리 상자에 담고, 설명서 한 장으로
모든 상자를 준비하는 것이다. 이 프로젝트에서는 `compose.yaml`이 설명서 역할을
한다.

장점은 한 명령으로 여러 프로그램을 실행하고 팀원이 비슷한 개발·테스트 환경을
반복해서 만들 수 있다는 것이다. Java와 PostgreSQL을 host에 각각 설치하지 않아도
된다.

단점은 Docker와 image, network, volume 개념을 배워야 한다는 것이다. Volume을
잘못 삭제하면 DB 데이터가 사라질 수 있고 여러 host에 container를 자동 배치하는
기능은 기본 범위가 아니다.

다음 상황에서 우선 검토한다.

- 여러 개발자가 같은 환경을 사용해야 한다.
- Application, DB와 test를 함께 실행해야 한다.
- 한 대의 개발 장비나 단순한 단일 host에서 실행한다.
- Orchestrator 수준의 운영 기능은 아직 필요하지 않다.

### Orchestrator

Orchestrator는 여러 서버의 많은 container를 자동으로 관리하는 시스템이다.
대표적인 예로 Kubernetes가 있다.

```text
Orchestrator
├── 서버 A: Application container
├── 서버 B: Application container
├── 서버 C: Worker container
└── 문제가 생기면 원하는 상태에 맞게 다시 배치
```

식당에 비유하면 여러 지점의 직원 수, 주문량과 고장 난 주방을 계속 확인하고
조정하는 본사다.

장점은 여러 host에 workload를 배치하고, 필요한 instance 수를 유지하고, 고장 난
instance를 교체하고, 새 버전을 순차적으로 배포할 수 있다는 것이다.

단점은 구조와 운영이 복잡하고 지속적으로 관리할 사람과 비용이 필요하다는 것이다.
Orchestrator 자체에도 장애가 생길 수 있으며 잘못된 application code나 transaction을
자동으로 올바르게 고쳐 주지는 않는다. 여러 instance가 동시에 실행되면 오히려
경쟁 조건이 더 쉽게 나타날 수 있다.

다음 상황에서 우선 검토한다.

- 여러 host에 많은 workload를 배치해야 한다.
- 장애 난 instance의 빠른 자동 교체가 필요하다.
- 여러 application instance와 rolling deployment를 관리해야 한다.
- 팀에 platform을 지속적으로 운영할 역량이 있다.

### 쉬운 선택 순서

```text
프로그램과 서버가 매우 적은가?
├── 예 → Host/VM 직접 실행 검토
└── 아니오
      ↓
한 대에서 개발·테스트 환경을 쉽게 만들려는가?
├── 예 → Docker Compose 검토
└── 아니오
      ↓
여러 서버의 많은 container를 자동 관리해야 하는가?
├── 예 → Orchestrator 검토
└── 아니오 → Compose 또는 VM 운영 자동화 검토
```

선택할 때는 다음 질문에 먼저 답한다.

1. 프로그램과 서버가 몇 개인가?
2. 서버가 고장 나면 사람이 복구해도 되는가?
3. 여러 application instance를 동시에 실행해야 하는가?
4. 새 버전을 중단 없이 순차적으로 배포해야 하는가?
5. 팀이 선택한 환경을 실제로 운영할 수 있는가?

### 가장 중요한 trade-off

```text
단순함 ←────────────────────────→ 자동화와 확장성

Host/VM 직접 실행    Docker Compose    Orchestrator
가장 단순            적당한 자동화      가장 많은 자동화
수동 관리 비중 큼     한 대 중심 관리    여러 서버 자동 관리
```

자동화가 많아질수록 편리한 기능이 늘어나지만 배워야 할 내용, 운영 비용과 새로운
장애 가능성도 함께 늘어난다. 어느 방식이 항상 가장 좋은 것은 아니다.

현재 Payment Orchestration Lab에는 Docker Compose가 적절하다. Application과
PostgreSQL을 함께 반복 실행해야 하지만 여러 운영 서버의 자동 배치와 복구 문제는
아직 발생하지 않았기 때문이다.

> 현재 필요한 만큼 Docker Compose를 사용하고, 여러 서버의 자동 복구와 배포 문제가
> 실제로 발생했을 때 Orchestrator를 검토한다.

## Concepts

### Container

Container는 application process와 필요한 filesystem, runtime, 설정 일부를 일정한
실행 단위로 묶는 방식이다.

Container는 일반적으로 다음 특성을 가진다.

- Host kernel을 공유하는 격리된 process다.
- Image를 기반으로 생성된다.
- Container filesystem은 교체될 수 있는 임시 영역으로 취급한다.
- 영속 데이터는 volume이나 외부 storage에 둔다.
- CPU, memory와 같은 resource limit을 적용할 수 있다.
- Image가 같으면 runtime과 기본 filesystem 구성을 반복하기 쉽다.

Container는 virtual machine과 다르다.

| 구분 | Container | Virtual machine |
|---|---|---|
| 격리 단위 | Process와 namespace | Guest operating system |
| Kernel | Host kernel 공유 | VM별 guest kernel |
| 시작 속도 | 일반적으로 빠름 | 일반적으로 더 느림 |
| Image 크기 | 비교적 작음 | OS를 포함하므로 비교적 큼 |
| 격리 강도 | Process 격리 | OS·hypervisor 경계 |

Windows나 macOS에서 Linux container를 실행할 때 Docker Desktop 내부에서 Linux VM이
사용될 수 있다. 개발자가 다루는 추상화는 container지만 실제 기반에는 VM이 있을
수 있다.

### Image

Image는 container를 만들기 위한 불변 template다. Application binary, runtime,
library, 기본 filesystem과 시작 명령을 포함할 수 있다.

현재 Dockerfile은 다음 두 단계로 application image를 만든다.

```text
Gradle + JDK image
  → bootJar build
JRE image
  → app.jar 복사
  → non-root app 사용자로 실행
```

이를 multi-stage build라고 한다. Build 도구를 최종 image에서 제외해 image 크기와
공격 표면을 줄일 수 있다.

Image 사용 시 확인할 항목은 다음과 같다.

- Base image의 Java와 OS 계열
- Tag 고정 수준과 digest 고정 필요성
- Application artifact가 빌드된 source revision
- Root가 아닌 사용자로 실행되는지
- Image에 secret이나 개발용 파일이 포함되지 않는지
- 취약점, SBOM, 배포 및 폐기 절차

Image가 동일해도 database, environment variable, network, 외부 API 상태까지 동일한
것은 아니다. “Image가 같다”와 “전체 실행 환경이 같다”를 구분한다.

### Docker

Docker는 image를 build하고 container를 생성·실행·중지하며 network와 volume을
관리하는 도구와 runtime 생태계다.

현재 프로젝트에서 Docker가 담당하는 일은 다음과 같다.

- Dockerfile로 application image build
- PostgreSQL image 실행
- Application과 test container 실행
- Port mapping, network, volume 제공
- Health check 실행

Docker가 자동으로 해결하지 않는 것은 다음과 같다.

- 애플리케이션 비즈니스 로직의 정확성
- DB transaction과 데이터 무결성
- Secret rotation
- Backup과 point-in-time recovery
- 여러 host 사이의 scheduling과 failover
- 무중단 schema migration
- PG 장애 후 결제 상태 수렴

### Docker Compose

Docker Compose는 여러 container, network, volume, environment variable과 시작 관계를
하나의 선언 파일로 구성하는 도구다.

현재 `compose.yaml`은 다음 관계를 정의한다.

```text
postgres가 health check 통과
  ├── application 시작
  └── test profile 실행 시 test 시작
```

Compose의 주요 목적은 다음과 같다.

- 로컬 개발 환경을 한 명령으로 실행
- Application과 DB의 network 연결 정의
- 팀원이 비슷한 환경을 반복 실행
- Integration test에 실제 PostgreSQL 제공

`depends_on`과 health condition은 시작 순서를 돕지만 다음을 보장하지 않는다.

- Application migration과 모든 초기화가 끝났는가
- Business 요청을 처리할 준비가 됐는가
- Runtime 장애 후 원하는 수만큼 instance가 복구되는가
- 여러 host에 workload가 분산되는가
- Rolling deployment와 automatic rollback이 가능한가

Compose는 단일 개발 장비나 단순한 단일 host 환경에 적합하다. Production 사용
가능 여부는 규모와 운영 요구에 따라 판단해야 하지만, 이 프로젝트에서는 Compose를
로컬 실행과 테스트 도구로 한정한다.

### Orchestrator

Orchestrator는 여러 workload의 desired state를 지속적으로 유지하는 제어 시스템이다.
대표적인 container orchestrator로 Kubernetes가 있지만 orchestrator라는 개념이
Kubernetes 하나만을 의미하지는 않는다.

일반적으로 orchestrator는 다음을 담당한다.

- 여러 host 중 workload를 실행할 위치 결정
- 원하는 instance 수 유지
- 비정상 instance 재시작 또는 교체
- Service discovery와 traffic routing
- Rolling deployment와 rollback 지원
- Config와 secret 전달
- Resource request와 limit 기반 scheduling
- Persistent storage 연결
- Health probe를 이용한 traffic 제어

Orchestrator가 있어도 application이 다음을 직접 해결해야 한다.

- 중복 요청과 중복 worker 실행
- Graceful shutdown 중 작업 처리
- DB transaction과 lock correctness
- Schema version 호환성
- 외부 PG timeout과 `UNKNOWN` 상태
- Webhook idempotency
- 재시작 이후 상태 수렴

Orchestrator가 container를 재시작하면 availability에는 도움을 줄 수 있지만 잘못된
transaction을 올바르게 만들지는 않는다. 오히려 작업 중간 재시작과 복수 instance로
새로운 경쟁 조건이 더 잘 드러날 수 있다.

### Container runtime과 orchestrator의 차이

| 질문 | Container runtime | Compose | Orchestrator |
|---|---|---|---|
| Container 하나를 실행하는가 | 예 | Runtime에 요청 | Runtime에 요청 |
| 여러 service 관계를 선언하는가 | 제한적 | 예 | 예 |
| 여러 host에 배치하는가 | 아니오 | 일반적으로 아니오 | 예 |
| Desired replica 수를 계속 유지하는가 | 아니오 | 제한적 | 예 |
| Rolling deployment를 관리하는가 | 아니오 | 제한적 | 예 |
| Local development에 적합한가 | 가능 | 매우 적합 | 일반적으로 복잡함 |

## Environment without containers

Container를 사용하지 않는다고 application이 운영체제 없이 실행되는 것은 아니다.
JVM process가 host OS 또는 VM에서 직접 실행된다.

```text
Physical server 또는 VM
  → Operating system
      → JRE 21
          → java -jar app.jar
```

PostgreSQL도 같은 host 또는 별도 host/VM에 package나 installer로 설치할 수 있다.

### Direct host process

가장 단순한 방식은 host에 Java를 설치하고 JAR를 직접 실행하는 것이다.

```powershell
java -jar app.jar
```

이 방법에서도 다음을 별도로 관리해야 한다.

- 올바른 Java 21 설치와 patch version
- Application 사용자와 filesystem 권한
- 환경 변수와 secret 전달
- Process 자동 시작과 재시작
- Log 수집과 rotation
- Port 충돌과 firewall
- CPU와 memory 제한
- Artifact 배포, 이전 버전 보존과 rollback

개발자가 터미널을 닫아도 계속 실행해야 한다면 process manager가 필요하다.
Linux에서는 systemd, Windows에서는 Windows Service 또는 별도 service wrapper를
사용할 수 있다.

### Virtual machine deployment

Application별 또는 역할별 VM을 만들고 그 안에서 JAR와 PostgreSQL을 직접 실행할
수 있다.

장점은 다음과 같다.

- OS 단위 격리가 명확하다.
- 기존 VM 운영·보안 체계를 활용할 수 있다.
- Container platform 없이도 배포할 수 있다.

비용은 다음과 같다.

- VM별 OS patch와 package 관리
- 환경 drift 가능성
- Provisioning과 배포 자동화 필요
- 상대적으로 큰 resource overhead
- Scale-out과 교체 속도가 느릴 수 있음

Ansible, image template, cloud-init 같은 도구를 이용해 host 설정을 코드화하지 않으면
서버마다 설치 상태가 달라질 수 있다.

### Managed platform

PaaS나 managed application platform에 JAR를 배포하고 process lifecycle, routing,
certificate 일부를 플랫폼에 맡길 수 있다. 사용자는 container를 직접 다루지 않아도
플랫폼 내부에서는 container가 사용될 수 있다.

따라서 “Container를 사용하지 않는다”는 표현은 다음 두 의미를 구분해야 한다.

- 실제 runtime이 host process 또는 VM process인 경우
- 사용자는 container를 관리하지 않지만 platform 내부 구현은 container인 경우

Managed platform은 운영 부담을 줄일 수 있지만 지원 runtime, network, filesystem,
deployment 방식과 vendor 제약을 확인해야 한다.

## Comparison

| 항목 | Host/VM에서 직접 실행 | Docker Compose | Orchestrator |
|---|---|---|---|
| 실행 단위 | JAR process와 OS package | Container와 service | 선언된 workload와 service |
| 환경 일관성 | Provisioning 품질에 의존 | Image로 비교적 높음 | Image와 platform 정책으로 높음 |
| 로컬 재현 | 개발 도구 직접 설치 필요 | 한 명령으로 구성 가능 | 로컬 사용에는 상대적으로 무거움 |
| Process 복구 | Service manager 필요 | Restart policy 수준 | Desired state controller |
| 다중 host | 별도 자동화 필요 | 기본 범위 아님 | 핵심 기능 |
| 배포 전략 | Script/배포 도구 필요 | 직접 관리 | Rolling 등 지원 가능 |
| 운영 복잡성 | Host 수에 따라 증가 | 낮음~중간 | 초기·상시 복잡성 높음 |
| 데이터 영속성 | Host disk/외부 DB | Volume/외부 DB | Persistent volume/외부 DB |

어느 방식이 항상 우월한 것은 아니다. 팀의 운영 역량, instance 수, 장애 복구 목표,
배포 빈도, 규제와 비용에 따라 선택한다.

## Cross-cutting responsibilities

실행 모델과 관계없이 다음 항목은 반드시 설계해야 한다.

### Configuration and secrets

- 환경별 설정을 artifact와 분리한다.
- Secret을 Git, image, 일반 로그에 넣지 않는다.
- 누가 읽고 변경할 수 있는지 통제한다.
- Rotation 후 application이 안전하게 새 값을 사용하는 방법을 정한다.

### Network

- 어떤 process가 어떤 hostname과 port로 통신하는지 정의한다.
- 내부와 외부 노출 범위를 분리한다.
- Timeout, retry와 connection pool을 설정한다.
- DNS나 연결 실패를 비즈니스 실패로 잘못 해석하지 않는다.

### Persistent data

- Application filesystem을 영속 storage로 가정하지 않는다.
- PostgreSQL backup, restore와 recovery 목표를 정한다.
- Container나 VM 교체 후에도 필요한 데이터가 남아 있어야 한다.
- Migration과 backup 복구 절차를 함께 검증한다.

### Process lifecycle

- 시작 순서와 readiness를 정의한다.
- 종료 신호를 받고 신규 요청을 중단한다.
- 진행 중 transaction과 background job의 종료 정책을 정한다.
- 재시작 후 `PENDING`과 `UNKNOWN` 작업을 다시 찾아 수렴시킨다.

### Observability

- 표준 출력 또는 파일 log의 수집 경로를 정한다.
- Health, metric, trace의 의미를 구분한다.
- Instance 교체 후에도 evidence와 audit log가 사라지지 않게 한다.
- Technical health와 payment business health를 구분한다.

## Selection criteria

실행 환경을 선택하거나 변경할 때 다음 질문에 답한다.

1. Application과 DB instance 수는 몇 개인가?
2. 단일 host 장애 시 허용 가능한 중단 시간은 얼마인가?
3. 배포 중 구버전과 신버전이 함께 실행되는가?
4. 하루 배포 횟수와 rollback 요구는 무엇인가?
5. 운영팀이 관리 가능한 기술은 무엇인가?
6. Host provisioning과 security patch는 누가 자동화하는가?
7. Secret, certificate와 audit 요구사항은 무엇인가?
8. Stateful workload는 직접 운영하는가, managed DB를 사용하는가?
9. 새 platform이 해결하는 관찰된 문제는 무엇인가?
10. 추가되는 장애 모드와 운영 비용을 감당할 수 있는가?

단순히 업계의 유행이거나 분산 시스템 기술을 학습한다는 이유만으로 orchestrator를
도입하지 않는다. 현재 문제와 대안을 먼저 분석하고 검증 가능한 성공 기준을 둔다.

## Consequences

### Positive

- Docker, Compose와 orchestrator의 책임을 혼동하지 않는다.
- Container 사용 여부와 관계없이 필요한 운영 책임을 확인할 수 있다.
- 현재 프로젝트가 Compose를 사용하는 이유와 보장 범위를 명확히 한다.
- 이후 production 배포 방식을 검토할 공통 용어와 선택 기준을 제공한다.

### Trade-offs

- 현재는 container가 없는 실행 경로를 실제로 제공하거나 테스트하지 않는다.
- Compose 환경과 미래 production 환경 사이에는 차이가 남는다.
- Orchestrator가 없으므로 복수 host scheduling, rolling deployment, desired state
  reconciliation은 현재 검증 대상이 아니다.
- 실행 환경 문서가 application correctness, DB constraint나 결제 reconciliation을
  대신하지 않는다.

## Validation

현재 Phase에서는 다음으로 결정의 범위를 검증한다.

- Compose로 PostgreSQL과 application을 실행할 수 있다.
- Test profile이 실제 PostgreSQL을 사용하는 integration test를 실행한다.
- Container 재생성 여부와 PostgreSQL volume 삭제 여부가 분리돼 있다.
- Application health endpoint는 process와 현재 datasource 상태를 확인한다.

Container를 사용하지 않는 실행, 복수 host, production orchestrator는 아직 실행하거나
검증하지 않는다. 해당 요구가 생기면 acceptance criteria와 재현 가능한 test를 먼저
정의하고 별도 ADR을 작성한다.
