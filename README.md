# 크레딧 (Credit)

비밀정원(마인팜 네트워크) 자체 **크레딧(캐시성 포인트)** 플러그인. Paper 26.2 / Java 25.

- **잔액의 단일 진실원은 MariaDB.** 모든 쓰기는 트랜잭션, 잔액은 절대 음수/중복지급 불가.
- **전 연산 비동기.** DB/HTTP/네트워크 I/O 는 전부 비동기 스레드에서 → TPS 영향 0. 비동기 스레드에서 Bukkit API 를 호출하지 않는다.
- **실패 시 안전(fail-safe).** MariaDB 연결 실패 시 크레딧 연산을 하지 않고 플러그인을 스스로 비활성화한다.

크레딧의 저장·조회·지급·차감은 전부 자체 구현 + MariaDB 다. [PlayerPoints](https://github.com/Rosewood-Development/PlayerPoints) 는 **참고용 소스**로만 사용했고 런타임 의존성이 아니다. (녹여낸 것: 저장소 추상화, 오프라인 UUID 기반 연산, 원자적 차감(잔액 부족 시 실패 반환), config 기반 메세지, 공개 API 설계. 우리 차이: MariaDB 단일, 금액 `long`/`BIGINT`, 공개 API 는 `CompletableFuture` 비동기, 홈페이지 결제 브릿지 통합.)

---

## 빌드

```bash
mvn clean package
```

- 산출물: `target/크레딧.jar` (버전/스피곳 접미사 없음 — `pom.xml` 의 `finalName` 이 결정).
- **paperweight / reobf / remap 미사용.** Paper 는 26.1부터 서버 JAR 난독화를 폐기했으므로 Mojang 매핑을 그대로 쓴다. `paper-api` provided 의존성 하나면 충분하다.
- 런타임 라이브러리(HikariCP, MariaDB JDBC)는 shade 하지 않고 `plugin.yml` 의 `libraries:` 로 서버가 런타임에 Maven Central 에서 받는다. → jar 가 작고 클래스 충돌이 없다.

### 필요 툴체인
| 항목 | 값 |
|---|---|
| JDK | **25** (Maven 도 JDK 25 로 실행) |
| Paper API | `[26.2,26.3)` — 26.2 라인 최신 빌드 자동 해석 |
| 빌드 | Maven |

> 참고: 이 저장소의 CI/개발 샌드박스는 JDK 21 이고 `repo.papermc.io` 접근이 네트워크 정책으로 차단되어 있어 `mvn package` 를 그대로 실행할 수 없었다. 실제 배포 환경(JDK 25 + Paper 저장소 접근 가능)에서 빌드한다. Paper 좌표(`[26.2,26.3)`)는 빌드 시점의 26.2 최신 빌드에 맞춰 조정할 수 있다.

---

## 설정

`config.yml`

- `database.*` — MariaDB 접속 정보 + HikariCP 풀 크기.
- `homepage.base-url` / `homepage.plugin-key` / `homepage.poll-interval-seconds` — 홈페이지 결제 브릿지. `plugin-key` 는 절대 노출 금지(로그/채팅에 찍지 않는다).
- `display.suffix` / `display.thousands-separator` — 금액 표시(접미사, 3자리 콤마).
- `redis.*` — (선택) 교차서버 캐시 무효화. 끄면 캐시 없이 항상 DB 조회.

`messages.yml` — 전부 MiniMessage 템플릿. 플레이스홀더 `<amount> <suffix> <player> <uuid>`, 커스텀 태그 `<glyph:이름>` 지원.

---

## 명령어 / 권한

| 명령어 | 권한 | 동작 |
|---|---|---|
| `/크레딧` | `credit.use`(기본 true, 플레이어 전용) | 본인 잔액 |
| `/크레딧 확인 <닉네임>` | `credit.admin` | 대상 잔액 |
| `/크레딧 지급 <닉네임> <금액>` | `credit.admin` | 지급(UPSERT) |
| `/크레딧 차감 <닉네임> <금액>` | `credit.admin` | 차감(원자적, 부족 시 거부) |
| `/크레딧 인증 <uuid>` | `credit.admin` | UUID → 마크 닉네임 |

관리자 서브명령어는 권한이 없으면 **탭완성/사용법에 노출되지 않는다.** 닉네임↔UUID 변환은 온라인 → usercache → Mojang API(비동기) 순이며 오프라인 유저도 안전하다.

---

## 공개 API (캐시 상점 등이 사용)

Bukkit `ServicesManager` 에 `CreditAPI` 구현을 등록하고 정적 접근자 `CreditProvider.get()` 도 제공한다. 전부 비동기·원자적.

```java
CreditAPI credit = kr.scfarm.credit.api.CreditProvider.get();
if (credit != null) {
    credit.take(uuid, 1000, "SHOP_TAKE").thenAccept(ok -> {
        if (ok) { /* 결제 성공 */ } else { /* 잔액 부족 */ }
    });
}
```

메서드: `getBalance / give / take / set / pay` — 모두 `CompletableFuture`. `take` 는 잔액 부족 시 `false`(잔액 불변). 홈페이지 지급도 동일한 DAO 경로(잔액 UPSERT + 원장)를 타므로 지급 로직은 한 곳에만 존재한다.

---

## 데이터 (MariaDB)

- `credit_balance` — 잔액(단일 진실원).
- `credit_ledger` — append-only 원장(모든 증감 이력, 감사/복구용).
- `credit_processed_charge` — 홈페이지 결제 멱등성(charge_id PK 로 중복 지급 차단).

지급 = 원자적 UPSERT + 원장(같은 트랜잭션). 차감 = 조건부 `UPDATE ... WHERE balance >= ?`(영향 행 0 = 잔액 부족 → false). 시작 시 스키마 자동 생성.

---

## 홈페이지 결제 브릿지 (명세 8번)

- `poll-interval-seconds` 마다 비동기 폴링: `GET /api/plugin/charges/pending` (헤더 `x-plugin-key`).
- 각 건을 **단일 MariaDB 트랜잭션**으로 "정확히 한 번" 지급:
  `INSERT processed_charge`(PK 충돌 = 이미 처리 → 재지급 안 함) + `UPSERT balance += amount` + `INSERT ledger(HOMEPAGE_CHARGE)`.
- 지급/스킵 무관하게 `POST /api/plugin/charges/complete {"id":...}` 로 보고(멱등). 보고 실패 시 다음 폴링에 재보고되어 수렴.
- **오프라인/미접속 UUID 도 지급된다**(UUID 기반 UPSERT — `Bukkit.getPlayer` 를 요구하지 않음). 네트워크 오류 → 다음 폴링 재시도, 401 → 키 오류 로그, DB 실패 → 보고 보류.
- 명세 7번(제재 동기화)은 이번 범위 밖. HTTP/헤더 구조만 재사용 가능하게 두었다.

---

## 콘솔 로드 메세지 (전 플러그인 통일 포맷)

`(흰색)크레딧 (회색)- (성공=주황/실패=빨강)상태문구` — 예: `크레딧 - MariaDB 연결 성공`. Adventure `ComponentLogger` 로 실제 색을 찍는다. 색상 상수는 `util/ConsoleLog` 에 모아 두어 다른 플러그인과 통일하기 쉽게 했다.

---

## 명세와 다르게/추가로 결정한 사항

- **지급/차감에 `<금액>` 필수 인자 추가** — 원문에는 생략돼 있었으나 논리상 필요.
- **PlaceholderAPI 는 동기 API** 라 DB 를 블로킹할 수 없어, `%credit_*%` 는 캐시값을 반환하고 백그라운드로 최신화한다. 정확한 잔액은 항상 `/크레딧`(DB) 로 조회한다.
- **Redis 는 기본 off.** 무손실/정확성이 우선이므로 확신 없으면 캐시보다 매번 DB 조회를 택했다. 켜려면 `plugin.yml` 의 `libraries` 에 lettuce-core 를 추가한다.
- 비정상 금액(≤0)의 홈페이지 결제 건은 지급하지 않되 무한 재폴링을 막기 위해 `processed` 에만 기록해 수렴시킨다.
