# 무인 숙박시설 출입 권한 발급 실패 복구

## 프로젝트 소개

어반스테이와 르컬렉티브를 이용할 때 모바일로 객실 비밀번호를 확인할 수 있어 편리했습니다. 그러다 체크인은 끝났는데 외부 도어락 시스템이 응답하지 않으면 고객은 어떻게 입실할지 생각해 봤습니다.

이전 업무에서 출입 권한 승인, 외부 카드 시스템 REST API 연동, 장애 대응과 데이터 정합성 관리를 경험했습니다. 그 경험을 숙박 도메인에 적용해, 발급 요청을 남겨 두고 실패한 작업을 재처리하는 백엔드를 만들었습니다.

## 해결하려는 문제

- 체크인이 완료되면 발급 요청도 함께 남아야 합니다.
- 일시적인 실패는 재시도하되, 응답이 없었다는 이유로 같은 권한을 중복 발급하면 안 됩니다.
- 발급을 확인하지 못했다면 고객에게 입실 가능으로 안내하면 안 됩니다.

## 구현한 기능

- 사전 등록된 예약의 체크인과 발급 상태 조회 API
- 체크인 정보와 발급 작업의 트랜잭션 저장, 중복 요청 방지
- 저장된 작업을 최대 20건씩 순차 처리하는 스케줄러: 처리 완료 후 1초 간격으로 조회
- 최초 1회 + 최대 재시도 3회(**총 4회**), 재시도 간격 5초
- 성공, 일시 오류, 발급 후 응답 타임아웃을 재현하는 `FakeDoorlockClient`

발급 성공과 실패는 Fake 클라이언트로 재현합니다.

## 기술 스택

Java 17 · Spring Boot 3.5.7 · Spring Data JPA · H2 · Gradle 8.14.3(Wrapper) · JUnit 5

## 실행 방법

`JAVA_HOME`을 Java 17 JDK로 설정합니다. 최초 실행에는 Gradle과 의존성을 내려받을 인터넷 연결이 필요합니다. Windows에서는 `gradlew.bat`을 사용합니다.

```bash
java -version
./gradlew bootRun
```

JAR로 실행하려면 다음 명령을 사용합니다.

```bash
./gradlew bootJar
java -jar build/libs/stay-access-recovery-0.0.1-SNAPSHOT.jar
```

기본 포트는 `8080`입니다. 시작할 때 예약 `1001`(101호), `1002`(102호), `1003`(103호)를 등록하며 기존 데이터는 덮어쓰지 않습니다.

DB 파일은 실행 디렉터리의 `data/access-recovery.mv.db`입니다. 같은 디렉터리에서 재실행하면 같은 DB를 사용합니다. 초기화하려면 프로세스를 종료한 뒤 `data/`를 삭제합니다. 체크인과 발급 이력도 함께 삭제됩니다.

## API 사용 예시

### Swagger UI로 확인

서버를 실행한 뒤 [Swagger UI](http://localhost:8080/swagger-ui/index.html)에서 두 API를 호출할 수 있습니다. 문서 원본은 [OpenAPI JSON](http://localhost:8080/v3/api-docs)으로 제공합니다. 테스트용 예약 ID는 `1001`, `1002`, `1003`입니다.

1. `POST /api/reservations/{reservationId}/check-in`에서 **Try it out**을 누르고 예약 ID를 입력한 뒤 **Execute**를 누릅니다. 본문은 필요 없으며 최초 요청은 `202`, 중복 요청은 `200`을 반환합니다.
2. `GET /api/reservations/{reservationId}/access-grant`에 같은 예약 ID를 넣어 상태를 조회합니다. 기본 성공 시나리오에서는 스케줄러 처리 후 `ISSUED`가 됩니다.

다른 프로젝트가 8080을 사용해 서버를 8085로 실행했다면 [8085 포트의 Swagger UI](http://localhost:8085/swagger-ui/index.html)로 접속하면 됩니다. OpenAPI 주소도 실행한 서버의 포트로 바꿉니다.

### 체크인

```bash
curl -i -X POST http://localhost:8080/api/reservations/1001/check-in
```

요청 본문은 없습니다. 최초 요청은 발급 작업을 저장한 뒤 `202 Accepted`를 반환하며, 외부 호출은 스케줄러가 처리합니다.

```json
{
  "reservationId": 1001,
  "requestId": "7851f1ee-a46c-4aa5-a479-d77ca51d0804",
  "status": "PENDING",
  "canEnter": false,
  "attempts": 0,
  "maxAttempts": 4,
  "nextAttemptAt": "2026-10-30T00:00:00Z"
}
```

같은 예약을 다시 요청하면 `200 OK`와 기존 작업의 현재 상태를 반환합니다. 새 작업이나 요청 ID를 만들지 않으며, `FAILED` 작업도 초기화하지 않습니다.

### 발급 상태 조회

```bash
curl -i http://localhost:8080/api/reservations/1001/access-grant
```

발급을 확인한 경우의 `200 OK` 응답입니다.

```json
{
  "reservationId": 1001,
  "requestId": "7851f1ee-a46c-4aa5-a479-d77ca51d0804",
  "status": "ISSUED",
  "canEnter": true,
  "attempts": 1,
  "maxAttempts": 4,
  "issuedAt": "2026-10-30T00:00:01Z",
  "grantId": "cb59ece7-91d6-4f72-bbec-819bb32de207"
}
```

계속된 타임아웃으로 허용 횟수를 소진한 경우에는 다음과 같이 반환합니다.

```json
{
  "reservationId": 1002,
  "requestId": "cbe5d888-f576-48fd-b922-81b594419ef7",
  "status": "FAILED",
  "canEnter": false,
  "attempts": 4,
  "maxAttempts": 4,
  "lastError": "TIMEOUT"
}
```

| 상태 | 의미 | `canEnter` |
| --- | --- | --- |
| `PENDING` | 최초 처리 또는 재시도 대기. 발급 여부가 불명확한 타임아웃 포함 | `false` |
| `ISSUED` | 발급 성공 응답을 받고 DB에 확인 결과를 저장함 | `true` |
| `FAILED` | 자동 재시도 종료. 실제 외부 미발급을 의미하지는 않음 | `false` |

조회 API는 DB에 저장된 확인 상태만 반환합니다. `attempts`는 커밋된 시도 수이고, `null` 필드는 생략합니다. `grantId`는 가상 발급 식별자로 객실 비밀번호가 아닙니다.

없는 예약은 `404`, 체크인 전 상태 조회는 `409`, 숫자가 아닌 예약 ID는 `400`을 반환합니다. 오류 응답 형식은 `application/problem+json`입니다.

## 이렇게 구현한 이유

### 체크인과 발급 작업을 함께 저장

체크인 시각과 `AccessGrantJob`을 같은 DB 트랜잭션에 저장해 둘 중 하나만 남지 않도록 했습니다. 작업 테이블에 상태를 보관하고, 스케줄러가 여기서 처리할 작업을 찾습니다.

| 데이터 | 주요 내용 |
| --- | --- |
| `Reservation` | 예약 ID, 객실 번호, 체크인 시각 |
| `AccessGrantJob` | 예약별 고유 작업, 고유·불변 `requestId`, 상태, 시도 수, 생성·재시도·발급 시각, 발급 결과와 마지막 오류 |

처음 들어오는 체크인 요청끼리도 겹칠 수 있어 예약 행을 먼저 잠그고 기존 작업을 확인합니다. 예약별 작업 고유 제약도 둬 중복 생성을 막습니다.

워커는 작업마다 트랜잭션을 열고 작업 행을 잠근 뒤 상태와 처리 기한을 다시 확인합니다. 스케줄러와 워커를 별도 빈으로 나눈 이유도 작업별 트랜잭션을 적용하기 위해서입니다. 조회 조건에는 `(status, next_attempt_at)` 인덱스를 뒀습니다.

발급 호출 중에도 DB 잠금을 유지합니다. 코드는 단순해지지만 호출이 길어지면 DB 자원을 오래 점유합니다. 실제 연동에서는 연결·응답 타임아웃을 짧게 두고, 필요한 처리량에 맞는 방식인지 살펴봐야 합니다.

### 응답이 없어도 발급됐을 수 있음

발급은 끝났는데 응답만 유실될 수 있어 재시도할 때도 같은 `requestId`를 보냅니다. **외부 시스템이 같은 ID에 기존 발급 결과를 돌려주는 멱등성을 보장한다는 가정**입니다. 발급 성공 후 DB 저장이 롤백돼도 같은 ID로 다시 요청합니다.

성공하거나 4회를 소진한 작업은 자동 재처리하지 않습니다. 다만 외부 호출 후 DB가 롤백되면 시도 수도 롤백되므로, 실제 호출 수는 커밋된 횟수인 4회를 넘을 수 있습니다.

### H2 데이터가 남는지도 확인

초기 테스트가 통과한 뒤에도 Codex의 추가 리뷰에서 데이터 유실이 발견됐습니다. H2 기본 `WRITE_DELAY=500ms`에서는 체크인 성공 응답 직후 프로세스를 강제 종료하면 체크인과 작업이 함께 사라졌습니다. `WRITE_DELAY=0`으로 바꾸고 같은 DB로 재시작하는 테스트를 3회 다시 실행해 데이터가 남는 것을 확인했습니다.

## 테스트와 확인한 내용

2026-10-31까지 확인한 결과입니다. 자동 실행과 개발자가 Swagger로 직접 확인한 내용을 구분했습니다. 모두 Java 17과 Fake 클라이언트를 사용한 로컬 실행입니다.

| 확인한 내용 | 결과 | 실행 주체 |
| --- | --- | --- |
| `clean test bootJar` | 성공, JUnit 15개 통과. 실패·오류·건너뜀 0건 | Codex |
| 체크인 응답 직후 SIGKILL 후 동일 DB 재시작 | 3/3 통과 | Codex |
| Swagger UI · OpenAPI | 문서 생성·접속, 브라우저에서 체크인 202와 상태 조회 200/ISSUED 확인 | Codex |
| Swagger 시나리오 확인 | 정상 발급, 체크인 전 409, 일시 실패 후 재시도 성공(attempts=2) | 개발자 |

JUnit 테스트는 정상 발급, 일시 실패 후 성공, 재시도 소진, 중복 체크인, 발급 후 타임아웃의 멱등 재시도를 포함합니다. 동시 체크인 6건과 동일 작업의 동시 처리 요청 4건, 트랜잭션 롤백 후 복구, DB 고유 제약, HTTP 오류 응답도 확인합니다. [테스트 코드](src/test/java/com/handies/accessrecovery/AccessRecoveryIntegrationTest.java)는 별도의 메모리 H2를 사용합니다.

```bash
./gradlew clean test bootJar
python3 scripts/verify_h2_durability.py
```

[강제 종료 검증 스크립트](scripts/verify_h2_durability.py)는 Java 17, Python 3.9 이상, macOS/Linux에서 실행합니다. 임시 DB와 포트를 사용하고 스케줄러를 꺼 `PENDING`을 유지합니다. JAR의 JDBC 설정으로 체크인 `202` 응답 직후 Java 프로세스를 `SIGKILL`하고, 같은 DB로 앱을 재시작해 다음을 확인합니다.

- `checked_in_at` 보존
- 발급 작업 1건
- 최초 응답과 같은 `requestId`
- `PENDING`, `attempts=0`, `canEnter=false`

체크인 시각은 같은 트랜잭션의 최초 `nextAttemptAt`과 H2의 마이크로초 정밀도로 비교합니다. 스크립트는 임시 DB와 검증 프로세스를 정리하며 실행용 `data/`는 사용하지 않습니다.

JUnit 보고서는 `build/reports/tests/test/index.html`과 `build/test-results/test/`, 강제 종료 결과는 `build/h2-durability-result.json`에 생성됩니다.

장애를 직접 재현하려면 실행 시 Fake 시나리오를 선택할 수 있습니다. 기본값은 `SUCCESS`입니다.

```bash
DOORLOCK_FAKE_SCENARIO=TIMEOUT_AFTER_ISSUE ./gradlew bootRun
```

| 시나리오 | 동작과 최종 결과 |
| --- | --- |
| `SUCCESS` | 즉시 성공, `ISSUED` / 1회 |
| `TRANSIENT_THEN_SUCCESS` | 첫 호출 일시 오류 후 성공, `ISSUED` / 2회 |
| `ALWAYS_FAIL` | 계속 일시 오류, `FAILED` / 4회 |
| `TIMEOUT_AFTER_ISSUE` | 발급 후 첫 응답 유실, 다음 호출에 기존 발급 반환. `ISSUED` / 2회, 발급 1건 |
| `ALWAYS_TIMEOUT_AFTER_ISSUE` | 발급 1건이 있어도 모든 응답 유실. `FAILED` / 4회 |

일시 실패 후 재시도를 확인하려면 다음 설정으로 서버를 실행합니다.

```bash
DOORLOCK_FAKE_SCENARIO=TRANSIENT_THEN_SUCCESS ./gradlew bootRun
```

아직 체크인하지 않은 예약 ID로 POST 체크인을 요청한 뒤, 같은 ID로 GET 조회를 반복합니다. 첫 발급 호출은 일시적으로 실패하고 두 번째 호출에서 성공하므로 최종 응답은 `status=ISSUED`, `canEnter=true`, `attempts=2`가 됩니다. 재시도 간격은 5초이므로 바로 조회하면 아직 `PENDING`일 수 있습니다.

시나리오는 프로세스 전체에 적용되고 첫 호출 여부는 요청 ID별로 판단합니다. 이미 `ISSUED`나 `FAILED`로 끝난 작업은 서버를 재시작해도 다시 실행되지 않습니다. 다른 미체크인 예약을 쓰거나, 서버를 종료한 뒤 테스트 DB의 `data/`를 삭제해 초기화합니다. 초기화하면 기존 체크인과 발급 이력도 사라집니다.

## 구현하지 않은 부분과 한계

- 별도 프런트엔드, 인증/인가, 결제, 실제 외부 API, 예약 생성·변경·취소, 권한 만료·회수, 외부 발급 상태 재조회, 수동 복구 API와 운영 알림은 만들지 않았습니다. 추가 인프라 없이 단일 Spring Boot 프로젝트로 구성했습니다.
- Fake의 멱등 기록은 메모리에만 있어 재시작하면 사라집니다. 외부 발급 직후 재시작까지 포함한 중복 발급 방지를 보장하지 않습니다. 실제 연동에서는 외부 멱등 기록의 영속성과 보관 기간을 확인해야 합니다.
- `FAILED` 이후에는 실제 외부 발급 여부를 별도로 확인할 운영 절차가 필요합니다. `canEnter`도 발급 확인 여부만 뜻하며 실제 문 개방이나 권한 만료를 확인한 값은 아닙니다.
- SIGKILL 테스트는 외부 발급 전, 체크인 응답 직후 단일 Java 프로세스를 종료한 경우입니다. 전원·디스크 장애와 외부 발급 도중 강제 종료는 시험하지 않았습니다.
- 고정 간격의 순차 처리와 H2 자동 스키마 변경을 사용합니다. 지속적인 DB 오류나 예상 밖의 예외에는 별도 감시가 필요합니다. 다중 인스턴스, 운영용 DB, 부하 테스트와 보안 감사는 수행하지 않았습니다.

## AI 활용 기록

Codex에 맡긴 구현과 리뷰, 문제를 수정하고 다시 확인한 과정, 개발자가 Swagger로 직접 확인한 내용은 [AI_REVIEW.md](docs/AI_REVIEW.md)에 정리했습니다.
