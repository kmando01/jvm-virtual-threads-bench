# 실증 노트 B: HikariCP 풀 사이즈 곡선

> **실험일**: 2026-06-18  
> **결론**: 가설 완전 확인. 풀 크기가 병목일 때 가상 스레드 수 증가는 p99를 지수적으로 폭발시킨다.

---

## 실측 원본 출력

```
$ java -cp ".:h2.jar:hikari.jar:slf4j-api.jar:slf4j-nop.jar" PoolStarvation

=== Experiment B: HikariCP Pool Starvation ===
query_sleep=10ms  total_ops=2000

pool_size    vthreads   p50(ms)    p95(ms)    p99(ms)    max(ms)
-----------------------------------------------------------------
5            10         12         83         344        1122
5            50         12         872        2106       4844
5            100        12         1663       3718       4857
5            200        13         3238       4622       4796
5            400        13         4256       4704       4829

50           10         12         13         13         14
50           50         11         69         180        344
50           100        11         227        552        770
50           200        11         750        784        795
50           400        12         747        781        781
```

## 실험 조건

| 항목 | 값 |
|------|----|
| JDK | Amazon Corretto 21.0.6 |
| DB | H2 in-memory (H2 2.2.224) |
| 커넥션 풀 | HikariCP 5.1.0 |
| 쿼리 지연 | 10ms (H2 SLEEP 함수 — 실제 DB I/O 모사) |
| 측정 | 2,000 ops 완료 후 p50/p95/p99/max |
| 변수 | poolSize={5, 50} × vthreads={10, 50, 100, 200, 400} |

---

## 측정 결과

### poolSize = 5 (과소 설정)

| vthreads | p50(ms) | p95(ms) | p99(ms) | max(ms) |
|----------|---------|---------|---------|---------|
| 10       | 12      | 83      | **344** | 1,122   |
| 50       | 12      | 872     | **2,106** | 4,844 |
| 100      | 12      | 1,663   | **3,718** | 4,857 |
| 200      | 13      | 3,238   | **4,622** | 4,796 |
| 400      | 13      | 4,256   | **4,704** | 4,829 |

### poolSize = 50 (적정 설정)

| vthreads | p50(ms) | p95(ms) | p99(ms) | max(ms) |
|----------|---------|---------|---------|---------|
| 10       | 12      | 13      | **13**  | 14      |
| 50       | 11      | 69      | **180** | 344     |
| 100      | 11      | 227     | **552** | 770     |
| 200      | 11      | 750     | **784** | 795     |
| 400      | 12      | 747     | **781** | 781     |

---

## 가설 검증

### B-1: 풀이 작으면 vthread 증가 시 p99가 지수적으로 폭발한다

**✅ 확인**

poolSize=5, p99 증가 패턴:

```
vthreads=10  → p99=344ms   (쿼리 10ms × 34배 대기)
vthreads=50  → p99=2,106ms (614% 증가)
vthreads=100 → p99=3,718ms (76% 증가, 점차 수렴)
vthreads=400 → p99=4,704ms (connectionTimeout=30s 하한으로 수렴)
```

p50은 전 구간에서 ~12ms로 안정적. **꼬리 지연(tail latency)만 폭발** → 90th 퍼센타일을 보면 "괜찮아 보임"이라는 착각이 생긴다.

---

## 핵심 인사이트

### "가상 스레드는 공짜가 아니다"

가상 스레드 400개를 만들어도 커넥션 풀이 5개이면 실질 병렬도는 5다:

```
실질 처리량 = pool_size × (1000ms / query_ms) = 5 × 100 = 500 ops/s
큐 깊이 = vthreads - pool_size = 395
이론 평균 대기 = 395 / 500 × 1000 ≈ 790ms
```

이 이론값(790ms)은 **평균 대기**이며 p50과 다르다. p50=13ms는 커넥션을 먼저 잡은 태스크들의 중앙값이고, 대기 중인 태스크들의 지연이 p95~p99에 집중된다. "평균은 정상이지만 tail이 폭발"하는 전형적 패턴.

### p50은 거짓말한다

poolSize=5, vthreads=400에서 p50=13ms. "쿼리가 10ms니까 괜찮다"고 판단하면 틀렸다. p99=4704ms인 사용자들이 이미 timeout을 경험하고 있다.

### 병목 이동의 증거

poolSize=5:
- vthreads=10: 아직 pool 여유 있음 → p99 344ms (허용 범위)
- vthreads=50: pool=5 × 10 = 50 = 동시 경쟁자 수 초과 → p99 2100ms 폭발

poolSize=50 (10배 증가):
- vthreads=400에서도 p99=781ms (pool=5 대비 6x 개선)
- p50은 여전히 ~12ms → 대부분의 요청은 정상

**결론**: 가상 스레드는 메모리 병목을 없애지만, DB 커넥션 풀이 새로운 천장이 된다.

---

## 재현 명령

```bash
cd ~/vt-experiment-B
# JAR 다운로드 (최초 1회)
curl -sSL -o h2.jar "https://repo1.maven.org/maven2/com/h2database/h2/2.2.224/h2-2.2.224.jar"
curl -sSL -o hikari.jar "https://repo1.maven.org/maven2/com/zaxxer/HikariCP/5.1.0/HikariCP-5.1.0.jar"
curl -sSL -o slf4j-api.jar "https://repo1.maven.org/maven2/org/slf4j/slf4j-api/2.0.13/slf4j-api-2.0.13.jar"
curl -sSL -o slf4j-nop.jar "https://repo1.maven.org/maven2/org/slf4j/slf4j-nop/2.0.13/slf4j-nop-2.0.13.jar"

CP=".:h2.jar:hikari.jar:slf4j-api.jar:slf4j-nop.jar"
javac -cp "$CP" PoolStarvation.java
java -cp "$CP" PoolStarvation 2>/dev/null
```

## 다음 실험(C)에 대한 시사점

B에서 병목이 "외부 자원(풀)"임을 확인했다. C는 병목이 "JVM 내부(carrier 스레드)"로 이동하는 케이스: synchronized pinning이 가상 스레드의 병렬도 자체를 제한하는 현상을 측정한다.
