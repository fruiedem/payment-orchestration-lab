# Phase 0 detailed answers documentation report

- Date: 2026-09-06
- Scope: 2년차 개발자 완료 기준 질문의 코드·실행 근거 정리

## 변경 파일

- `docs/lessons/README.md`
- `docs/lessons/Phase0-02-year-developer.md`
- `docs/lessons/Phase0-02-year-developer-detailed-answers.md`
- `docs/evidence/README.md`
- `docs/evidence/phase0-2026-09-06-infrastructure-verification.md`
- `docs/report/2026-09-06-phase0-detailed-answers.md`

Application source, migration과 Compose 설정은 변경하지 않았다.

## 설계 이유

- 학습 가이드는 개념과 상세 답안을 분리해 본문이 지나치게 길어지지 않게 했다.
- 상세 답안은 각 질문마다 짧은 답, 코드 근거, 실행 근거와 한계를 함께 제시했다.
- 실제 명령과 결과는 `docs/evidence`에 분리해 주장과 실행 증거를 구분했다.
- 기존 lessons 파일명이 `Phase0-*`로 변경된 상태였으므로 README의 깨진 링크를 현재
  파일명에 맞게 수정했다.
- 기본 Gradle 명령의 `UP-TO-DATE`와 강제 실행의 `executed`를 구분해 테스트를 실제로
  실행하지 않은 결과를 성공 증거로 오인하지 않게 했다.

## 실행한 명령

```powershell
docker version
docker compose version
docker info --format 'Server={{.ServerVersion}} OS={{.OperatingSystem}}'

docker compose --env-file .env.example --profile test run --build --rm test

docker compose --env-file .env.example --profile test run --build --rm test `
  gradle test --no-daemon --rerun-tasks

docker compose --env-file .env.example --profile test run --rm test `
  sh -c "getent hosts localhost; getent hosts postgres"

(Invoke-RestMethod -Uri 'http://localhost:8080/actuator/health').status

docker inspect payment-orchestration-lab-postgres-1 `
  --format 'ContainerId={{.Id}} Mounts={{range .Mounts}}{{.Type}}:{{.Name}}->{{.Destination}}{{end}}'

docker volume inspect payment-orchestration-lab_postgres-data `
  --format 'Volume={{.Name}} Mountpoint={{.Mountpoint}}'

docker compose --env-file .env.example exec -T postgres `
  psql -U payment_app -d payment_orchestration -At -F '|' `
  -c "SELECT version, description, type, checksum, success FROM flyway_schema_history ORDER BY installed_rank;"

git rev-parse --show-toplevel
git rev-parse HEAD
git diff --check
```

추가로 PowerShell의 `Test-Path`와 정규식을 사용해 변경 문서 존재 여부, 상대 링크와
Markdown code fence 균형을 검사했다.

## 테스트 결과

문서화된 기본 test 명령:

```text
BUILD SUCCESSFUL
:test UP-TO-DATE
4 actionable tasks: 4 up-to-date
```

이 결과는 이번 실행에서 테스트가 다시 수행됐다는 증거가 아니다.

`--rerun-tasks` 강제 실행 결과:

```text
BUILD SUCCESSFUL in 1m 9s
4 actionable tasks: 4 executed
Tests: 2
Skipped: 0
Failures: 0
Errors: 0
```

추가 확인 결과:

- PostgreSQL: 17.11
- Flyway V1 checksum: `621857564`
- Flyway V1 success: true
- Health endpoint: `UP`
- Compose DNS: `localhost`와 `postgres`가 서로 다른 주소로 해석됨
- PostgreSQL data path: named volume에 mount됨
- 변경 문서 존재 여부: 통과
- Markdown code fence 검사: 통과
- 상대 링크 검사: 통과
- `git diff --check`: 공백 오류 없음

## 아직 보장하지 않는 것

- 빈 DB에서 V1을 최초 적용하는 bootstrap 과정
- 재고가 음수가 되지 않는 불변식
- 주문당 성공 결제 최대 하나
- 외부 PG timeout의 `UNKNOWN` 처리
- Webhook과 refund idempotency
- 재시작 후 `PENDING`과 `UNKNOWN` 상태 수렴
- 복수 application instance correctness
- 동시성·장애·성능 acceptance criteria

## 다음 단계에서 해결할 문제

- Phase acceptance criteria가 정해지면 해당 비즈니스 모델과 constraint를 추가한다.
- 빈 PostgreSQL volume에서 전체 migration을 검증하는 격리된 실행 방법을 마련한다.
- 기본 test 명령이 항상 실제 테스트를 수행해야 하는지, cache를 허용하고 evidence에서
  구분할지 결정한다.
- 비즈니스 기능을 추가할 때 unit, integration, deterministic concurrency test의
  책임을 분리한다.
