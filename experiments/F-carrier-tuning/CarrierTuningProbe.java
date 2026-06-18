import java.util.concurrent.*;

/**
 * 실험 F: jdk.virtualThreadScheduler.parallelism 튜닝 효과 (재설계)
 *
 * 문제점 수정:
 *   이전 버전: while 루프 + JNI pinning → ForkJoinPool 보상 스레드와 경쟁 조건 발생
 *   수정: 각 virtual thread가 정확히 1 op만 수행 → 명확한 인과관계
 *
 * 원리:
 *   parallelism=P → JNI 동시 실행 가능 수 = P
 *   총 TOTAL_OPS 건 완료 시간 = ceil(TOTAL_OPS / P) × SLEEP_MS
 *   parallelism가 2배 → 시간 절반 → 처리량 2배
 *
 * 실행:
 *   for P in 12 25 50 100 200; do
 *     java -Djdk.virtualThreadScheduler.parallelism=$P -Djava.library.path=. CarrierTuningProbe
 *   done
 */
public class CarrierTuningProbe {

    static final int SLEEP_MS  = 50;
    static final int TOTAL_OPS = 400;

    static { System.loadLibrary("jni_sleep"); }
    static native void nativeSleep(long millis);

    public static void main(String[] args) throws Exception {
        int parallelism = Integer.parseInt(
            System.getProperty("jdk.virtualThreadScheduler.parallelism",
                               String.valueOf(Runtime.getRuntime().availableProcessors())));

        // 이론값
        long theoreticalMs = (long) Math.ceil((double) TOTAL_OPS / parallelism) * SLEEP_MS;

        long tJni  = run(true);
        long tJava = run(false);

        double opsJni  = TOTAL_OPS * 1000.0 / tJni;
        double opsJava = TOTAL_OPS * 1000.0 / tJava;
        double recovery = opsJni / opsJava * 100;

        System.out.printf("parallelism=%-4d  JNI=%,5dms(%5.0f ops/s)  " +
                          "Java=%,5dms(%5.0f ops/s)  " +
                          "recovery=%5.1f%%  theory=%,dms%n",
            parallelism, tJni, opsJni, tJava, opsJava, recovery, theoreticalMs);
    }

    /** TOTAL_OPS개의 virtual thread를 생성, 각 1번의 sleep 수행 후 완료 */
    static long run(boolean useJni) throws Exception {
        var done = new CountDownLatch(TOTAL_OPS);

        long t0 = System.currentTimeMillis();
        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < TOTAL_OPS; i++) {
                exec.submit(() -> {
                    if (useJni) nativeSleep(SLEEP_MS);
                    else {
                        try { Thread.sleep(SLEEP_MS); }
                        catch (InterruptedException ignored) {}
                    }
                    done.countDown();
                });
            }
            done.await(60, TimeUnit.SECONDS);
        }
        return System.currentTimeMillis() - t0;
    }
}
