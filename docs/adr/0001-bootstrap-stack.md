# ADR-0001: Bootstrap stack

- Status: Accepted
- Date: 2026-09-02

## Context

향후 결제 장애 및 동시성 실험을 반복 가능하게 실행할 기반이 필요하다. 현재 단계는 비즈니스 모델을 포함하지 않는다.

## Decision

Java 21, Spring Boot, PostgreSQL, Flyway, Gradle, Docker Compose를 사용한다. 하나의 애플리케이션과 하나의 관계형 데이터베이스로 시작한다.

Integration test는 Compose의 실제 PostgreSQL에 연결하며 migration과 health endpoint를 검증한다.

## Consequences

- 로컬 Java/Gradle 설치 없이 동일한 컨테이너 환경에서 실행 및 테스트할 수 있다.
- PostgreSQL의 transaction, lock, constraint를 이후 실험에서 직접 다룰 수 있다.
- 현재 migration은 application schema만 만들며 비즈니스 테이블이나 불변식은 아직 정의하지 않는다.

