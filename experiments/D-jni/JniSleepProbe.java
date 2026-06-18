import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 실험 D: JNI pinning 잔존 확인
 *
 * JEP 491은 synchronized pinning을 제거했지만
 * native frame 안에서의 blocking은 여전히 carrier를 pin한다.
 *
 * 비교:
 *   (1) Java Thread.sleep()  → always no pinning (JDK 21/25 공통)
 *   (2) JNI nativeSleep()    → always pinning    (JDK 21/25 공통)
 *
 * 판정: nativeSleep가 JavaSleep보다 현저히 느리면 → JNI pinning 잔존 확인.
 */
public class JniSleepProbe {

    static { System.loadLibrary("jni_sleep"); }

    /** JNI native: C의 usleep으로 millis ms 동안 블로킹 */
    static native void nativeSleep(long millis);

    static final int SLEEP_MS    = 50;
    static final int TOTAL_OPS   = 200;
    static final int CONCURRENCY = 50;

    public static void main(String[] args) throws Exception {
        int cores = Runtime.getRuntime().availableProcessors();
        System.out.printf("JDK: %s%n", System.getProperty("java.version"));
        System.out.printf("cores=%d  concurrency=%d  ops=%d  sleep=%dms%n%n",
            cores, CONCURRENCY, TOTAL_OPS, SLEEP_MS);
        System.out.printf("이론 최소(no pin): %dms | 이론 최악(pin): %dms%n%n",
            (long) Math.ceil((double) TOTAL_OPS / CONCURRENCY) * SLEEP_MS,
            (long) Math.ceil((double) TOTAL_OPS / cores)       * SLEEP_MS);

        long tJava = runTest(false);
        long tJni  = runTest(true);

        double ratio = (double) tJni / tJava;
        System.out.printf("%-20s %6d ms  throughput=%5.1f ops/s%n",
            "[Java sleep]",   tJava, TOTAL_OPS * 1000.0 / tJava);
        System.out.printf("%-20s %6d ms  throughput=%5.1f ops/s%n",
            "[JNI  sleep]",   tJni,  TOTAL_OPS * 1000.0 / tJni);
        System.out.printf("%nRatioJNI/Java = %.2fx  → %s%n",
            ratio,
            ratio >= 1.5 ? "JNI PINNING CONFIRMED (carrier 점유됨)" : "NO PINNING");
    }

    static long runTest(boolean useJni) throws Exception {
        var counter = new AtomicInteger(0);
        var done    = new CountDownLatch(TOTAL_OPS);

        long t0 = System.currentTimeMillis();
        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int w = 0; w < CONCURRENCY; w++) {
                exec.submit(() -> {
                    while (counter.getAndIncrement() < TOTAL_OPS) {
                        if (useJni) {
                            nativeSleep(SLEEP_MS);          // JNI: native frame → pinning
                        } else {
                            try { Thread.sleep(SLEEP_MS); } // Java: 정상 unmount
                            catch (InterruptedException e) {}
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
