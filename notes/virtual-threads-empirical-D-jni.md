# 실증 노트 D: JNI Pinning 잔존

> **실험일**: 2026-06-18  
> **결론**: JEP 491 이후에도 JNI native frame 안에서의 blocking은 carrier를 pin한다. JDK 21/25 동일.

---

## 실험 조건

| 항목 | 값 |
|------|----|
| JDK | 21.0.6 vs 25.0.3 (Amazon Corretto) |
| 네이티브 라이브러리 | `libjni_sleep.dylib` (C, `usleep()` 사용) |
| concurrency | 50 가상 스레드 |
| sleep | 50ms (JNI `usleep`, Java `Thread.sleep` 비교) |
| total ops | 200 |

---

## 측정 결과

### JDK 21

```
이론 최소(no pin): 200ms | 이론 최악(pin): 850ms

[Java sleep]   222ms  throughput=901 ops/s  ← 정상 (no pin)
[JNI  sleep]   911ms  throughput=220 ops/s  ← 이론 최악에 근접

RatioJNI/Java = 4.10x → JNI PINNING CONFIRMED
```

### JDK 25 (JEP 491 포함)

```
이론 최소(no pin): 200ms | 이론 최악(pin): 850ms

[Java sleep]   226ms  throughput=885 ops/s  ← 정상 (no pin)
[JNI  sleep]   914ms  throughput=219 ops/s  ← 이론 최악에 근접

RatioJNI/Java = 4.04x → JNI PINNING CONFIRMED
```

**JDK 21과 JDK 25의 JNI pinning 비율: 4.10x vs 4.04x — 사실상 동일.**

---

## 가설 검증

### D-1: JDK 25에서도 native frame 안의 blocking은 여전히 pinning을 일으킨다

**✅ 확인**

JDK 25에서:
- Java `Thread.sleep()` → 226ms (정상, carrier 반환)
- JNI `usleep()` → 914ms (4.04x 느림, carrier 점유)

JEP 491이 JDK 25에서 `synchronized`를 완전히 수정했음에도 불구하고 JNI는 변화 없음.

---

## 왜 JNI는 고칠 수 없는가

### JEP 491 fix의 범위

```
JEP 491이 수정한 것:
  synchronized(obj) { Thread.sleep(50); }
  → monitor를 가상 스레드에 연결 (carrier 독립적)
  → carrier unmount 가능

JEP 491이 수정하지 못한 것:
  System.loadLibrary("jni_sleep"); nativeSleep(50);
  → JVM이 native frame에서 실행 중인 스레드를 unmount하면
    C 코드의 스택 포인터, TLS(Thread-Local Storage), CPU 레지스터가
    OS 스레드에 묶여 있음
  → native frame이 완료될 때까지 carrier 해제 불가
```

### 물리적 한계

```
[가상 스레드]
    ↓ 호출
[JNI JniSleepProbe.nativeSleep]
    ↓ C stack frame 진입
[OS: usleep(50000μs)]
    → OS가 해당 pthread를 sleep
    → pthread = carrier thread = OS가 관리하는 실제 스레드
    → JVM이 "이 carrier는 현재 native에서 sleep 중입니다"를 알지만
       pthread를 깨워서 다른 일을 시킬 수 없음 (OS sleep이 끝날 때까지)
```

---

## 핵심 인사이트

### synchronized vs JNI: fix 가능성의 차이

| | synchronized | JNI |
|--|------------|-----|
| pinning 원인 | JVM monitor가 carrier에 연결 | native thread stack이 OS에 연결 |
| fix 방법 | monitor를 vthread에 연결 (JEP 491) | 불가 (OS 수준 제약) |
| JDK 21 상태 | pinning | pinning |
| JDK 25 상태 | **fix됨** | **여전히 pinning** |

### JNI pinning이 실무에 미치는 영향

| 사례 | 위험도 |
|------|-------|
| 짧은 native 계산 (암호화, 수학 함수) | 낮음 (10μs 미만) |
| JDBC native driver | **높음** — DB I/O 중 carrier 점유 |
| SSL/TLS 핸드셰이크 (native) | 중간 |
| `synchronized (native 내부)` + blocking | **높음** |

### 대응 전략

```
문제: JNI 라이브러리가 carrier를 pin함
해결책:
  1. 해당 작업을 별도 ExecutorService (platform thread 기반) 에 오프로드
     → virtual thread가 CompletableFuture로 결과만 기다림
  2. carrier 스레드 수를 늘림
     -Djdk.virtualThreadScheduler.parallelism=128
     (기본값=cores, 최대 256)
  3. JNI 라이브러리를 async I/O 방식으로 재작성 (가장 어렵지만 근본 해결)
```

---

## 흥미로운 발견

JDK 25에서 JNI pinning 비율(4.04x)이 JDK 21(4.10x)보다 약간 낮다. 이는 JNI 자체 fix가 아닌 JDK 25의 전반적인 virtual thread 스케줄러 개선에 의한 노이즈 수준 차이다. 실질적으로 동일하다고 봐야 한다.

---

## 4개 실험의 큰 그림

```
실험 A: "가상 스레드는 JVM heap으로 간다" ✅
         → 메모리 병목 제거 (OS 스레드 38x 절약)

실험 B: "그러면 DB 커넥션 풀이 새 천장" ✅
         → pool=5에서 p99가 344→4704ms로 폭발

실험 C: "carrier thread도 천장이었다 (synchronized)" ✅
         → JDK 25(JEP 491)로 완전 해결

실험 D: "native code(JNI)는 여전히 carrier를 pin한다" ✅
         → JDK 25에도 해결 안 됨, 오프로드 전략 필요
```
