import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 실험 C: synchronized pinning 측정 (수정판)
 *
 * ── 설계 핵심 ──
 *   공유 lock 금지 → 직렬화 효과와 pinning 효과가 뒤섞임.
 *   각 가상 스레드가 자신만의 lock 객체를 보유하고 sleeping →
 *   lock 경합 0, pinning 효과만 격리.
 *
 * ── 원리 ──
 *   JDK 21: virtual thread가 synchronized 블록 안에서 Thread.sleep()을 호출하면
 *           JVM이 carrier 스레드를 함께 블로킹(pinning).
 *           CONCURRENCY=50이어도 실제 병렬도 = cores(≈8) → 처리량 급감.
 *
 *   JDK 25(JEP 491): synchronized 블록 안에서도 carrier를 반환 가능 →
 *           CONCURRENCY 만큼 진짜 병렬 실행.
 *
 * ── 판정 ──
 *   소요 시간 ≈ ceil(TOTAL/CONCURRENCY) * SLEEP_MS   → no pinning
 *   소요 시간 ≈ ceil(TOTAL/CORES)       * SLEEP_MS   → pinning
 */
public class PinningProbe {

    static final int SLEEP_MS    = 50;
    static final int TOTAL_OPS   = 200;
    static final int CONCURRENCY = 50;

    public static void main(String[] args) throws Exception {
        int cores = Runtime.getRuntime().availableProcessors();
        String jdk = System.getProperty("java.version");

        System.out.printf("JDK: %s%n", jdk);
        System.out.printf("cores=%d  concurrency=%d  ops=%d  sleep=%dms%n%n",
            cores, CONCURRENCY, TOTAL_OPS, SLEEP_MS);
        System.out.printf("이론 최소 시간 (no pinning): %dms%n",
            (long) Math.ceil((double) TOTAL_OPS / CONCURRENCY) * SLEEP_MS);
        System.out.printf("이론 최악 시간 (pinning):    %dms%n%n",
            (long) Math.ceil((double) TOTAL_OPS / cores) * SLEEP_MS);

        long tSync = run(false);
        long tLock = run(true);

        double ratio = (double) tSync / tLock;
        System.out.printf("%-20s %6d ms  throughput=%5.1f ops/s%n",
            "[synchronized]", tSync, TOTAL_OPS * 1000.0 / tSync);
        System.out.printf("%-20s %6d ms  throughput=%5.1f ops/s%n",
            "[ReentrantLock]", tLock, TOTAL_OPS * 1000.0 / tLock);
        System.out.printf("%nRatioSync/Lock = %.2fx  → %s%n",
            ratio,
            ratio >= 1.5 ? "PINNING CONFIRMED (carrier 점유됨)" : "NO PINNING (JEP 491 효과)");
    }

    static long run(boolean useLock) throws Exception {
        var counter = new java.util.concurrent.atomic.AtomicInteger(0);
        var done    = new CountDownLatch(TOTAL_OPS);

        long t0 = System.currentTimeMillis();
        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int w = 0; w < CONCURRENCY; w++) {
                exec.submit(() -> {
                    // 각 워커가 자신만의 lock 객체 보유 → 경합 없음, pinning만 격리
                    var myLock = new ReentrantLock();
                    var myObj  = new Object();

                    while (counter.getAndIncrement() < TOTAL_OPS) {
                        if (useLock) {
                            myLock.lock();
                            try { Thread.sleep(SLEEP_MS); }
                            catch (InterruptedException ignored) {}
                            finally { myLock.unlock(); }
                        } else {
                            synchronized (myObj) {
                                try { Thread.sleep(SLEEP_MS); }
                                catch (InterruptedException ignored) {}
                            }
                        }
                        done.countDown();
                    }
                });
            }
            done.await(120, TimeUnit.SECONDS);
        }
        return System.currentTimeMillis() - t0;
    }
}
