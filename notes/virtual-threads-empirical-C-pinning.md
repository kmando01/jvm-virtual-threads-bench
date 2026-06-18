# 실증 노트 C: synchronized Pinning

> **실험일**: 2026-06-18  
> **결론**: JDK 21에서 synchronized pinning 4.33x 처리량 손실 확인. JDK 25(JEP 491)에서 완전 해결.

---

## 실험 조건

| 항목 | 값 |
|------|----|
| JDK | 21.0.6 (Amazon Corretto) vs 25.0.3 (Amazon Corretto) |
| concurrency | 50 가상 스레드 |
| sleep | 50ms (인공 blocking) |
| total ops | 200 |
| CPU cores | 12 (= 캐리어 스레드 기본 풀 크기) |

**설계 핵심**: 각 가상 스레드가 **자신만의 lock 객체**를 보유 → lock 경합 없음, pinning 효과만 격리.

---

## 이론 예측

| 시나리오 | 예측 소요시간 | 근거 |
|----------|------------|------|
| pinning 없음 | ~200ms | ops=200, concurrency=50 → ceil(200/50)×50ms=4라운드 |
| pinning 있음 | ~850ms | ops=200, 실질 병렬도=12(cores) → ceil(200/12)×50ms=17라운드 |

---

## 측정 결과

### JDK 21 (JEP 491 없음)

```
[synchronized]  913ms  throughput=219.1 ops/s   ← 이론 최악값(850ms)에 근접
[ReentrantLock] 211ms  throughput=947.9 ops/s   ← 이론 최소값(200ms)에 근접

RatioSync/Lock = 4.33x → PINNING CONFIRMED
```

### JDK 25 (JEP 491 포함)

```
[synchronized]  221ms  throughput=905.0 ops/s   ← 이론 최소값에 근접!
[ReentrantLock] 219ms  throughput=913.2 ops/s

RatioSync/Lock = 1.01x → NO PINNING (JEP 491 효과)
```

---

## 가설 검증

### C-1: JDK 21에서 synchronized가 carrier를 pin한다

**✅ 확인**

JDK 21에서 synchronized 블록 안에서 `Thread.sleep()`을 호출하면:
- JVM이 가상 스레드를 carrier에서 unmount하지 못함
- 동시 실행 가능한 가상 스레드 수 = carrier 스레드 수 = 12개로 제한
- 50개가 경쟁해도 실질 병렬도 12 → 처리량 4.3x 손실

### C-2: JDK 25(JEP 491)에서 synchronized pinning이 사라진다

**✅ 확인**

JDK 25에서 synchronized = ReentrantLock (차이 1%) → 완전히 동일한 처리량.

---

## 핵심 인사이트

### pinning 메커니즘

```
JDK 21:
가상 스레드 → synchronized(obj) 진입 → carrier에 mount
→ Thread.sleep() 호출 (blocking I/O 발생)
→ JVM: "monitor를 쥔 채 unmount하면 다른 스레드가 monitor 획득 불가"
→ 판정: carrier 유지 (pinned)
→ 12개 core 기준 carrier 포화 → 나머지 38개 가상 스레드 대기

JDK 25 (JEP 491):
가상 스레드 → synchronized(obj) 진입
→ Thread.sleep() 호출
→ JVM: "monitor는 가상 스레드에 연결, carrier와 무관"
→ carrier 반환 → 다른 가상 스레드가 carrier 사용
→ 동시 50개 모두 진행 가능
```

### 실무 시사점 (JDK 21 기준)

| 상황 | 권고 |
|------|------|
| 직접 작성 코드 | `synchronized` → `ReentrantLock` 마이그레이션 |
| 라이브러리 내부 `synchronized` | 라이브러리 업그레이드 또는 carrier 스레드 수 증가 (`jdk.virtualThreadScheduler.parallelism`) |
| JDK 25 이상 | `synchronized` 사용 가능, 성능 차이 없음 |

### JDK 21에서 pinning 진단 방법

```bash
# JFR로 VirtualThreadPinned 이벤트 캡처
java -XX:StartFlightRecording=filename=pin.jfr,settings=default \
     -Djdk.tracePinnedThreads=full MyApp

# 또는 실시간 출력 (-Djdk.tracePinnedThreads)
# → 스택 트레이스에서 synchronized 위치 확인
```

---

## 흥미로운 발견

JDK 21에서 실측값(913ms)이 이론 최악(850ms)보다 약 7% 초과했다. 원인:
- 캐리어 스레드 스케줄링 오버헤드
- JVM 내부 스레드(GC, JIT 등) 12코어 일부 경쟁

JDK 25에서 221ms는 이론 최소 200ms 대비 10% 초과 — 이는 virtual thread 스케줄링 자체의 고정 오버헤드다.

---

## 다음 실험(D)에 대한 시사점

JEP 491이 `synchronized`를 완전히 fix했지만, native code(JNI/FFI)는 다른 이야기다. 실험 D에서 같은 조건으로 JNI sleep을 측정하면 JDK 25에서도 pinning이 남아있음을 확인한다.
