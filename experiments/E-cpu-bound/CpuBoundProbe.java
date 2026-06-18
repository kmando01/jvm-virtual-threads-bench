import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 실험 E: CPU-bound 작업에서 가상 스레드 vs 플랫폼 스레드
 *
 * 가설:
 *   CPU-bound 작업은 carrier 스레드(= core 수)가 상한이므로
 *   가상 스레드를 늘려도 처리량이 늘지 않는다.
 *   오히려 스케줄링 오버헤드로 인해 코어 수 초과 시 성능이 저하될 수 있다.
 *
 * 설계:
 *   - task: 소수 계산 (isPrime up to PRIME_LIMIT) — 순수 CPU 연산, blocking 없음
 *   - 총 TOTAL_TASKS 건을 다양한 concurrency로 처리
 *   - 플랫폼 스레드 / 가상 스레드 비교
 *   - cores=12 기준: 6(절반), 12(코어), 24(2배), 48, 96(8배) 로 Ladder
 */
public class CpuBoundProbe {

    static final int TOTAL_TASKS = 600;
    static final int PRIME_LIMIT = 500_000;   // 약 5~10ms/task (JIT 후)

    public static void main(String[] args) throws Exception {
        int cores = Runtime.getRuntime().availableProcessors();
        System.out.printf("=== Experiment E: CPU-bound (cores=%d) ===%n", cores);
        System.out.printf("task=isPrime(1..%,d)  total=%d tasks%n%n", PRIME_LIMIT, TOTAL_TASKS);

        // JIT 워밍업 (결과에 포함 안 함)
        System.out.print("JIT 워밍업 중... ");
        runBatch(cores, TOTAL_TASKS / 6, true,  "warmup");
        runBatch(cores, TOTAL_TASKS / 6, false, "warmup");
        System.out.println("완료");
        System.out.println();

        int[] concurrencies = {cores / 2, cores, cores * 2, cores * 4, cores * 8};
        System.out.printf("%-10s %-18s %-18s %-10s%n",
            "threads", "Platform(ms)", "Virtual(ms)", "비율V/P");
        System.out.println("-".repeat(60));

        for (int c : concurrencies) {
            long tPlat = runBatch(c, TOTAL_TASKS, false, null);
            long tVirt = runBatch(c, TOTAL_TASKS, true,  null);
            System.out.printf("%-10d %-18d %-18d %-10.2f%n",
                c, tPlat, tVirt, (double) tVirt / tPlat);
        }

        System.out.println();
        System.out.println("비율 < 1.0 → 가상이 빠름 | 비율 > 1.0 → 플랫폼이 빠름");
        System.out.printf("이론: 코어 수(%d) 초과 시 처리량 증가 없음, 오버헤드만 증가%n", cores);
    }

    static long runBatch(int concurrency, int totalTasks,
                         boolean virtual, String label) throws Exception {
        AtomicInteger issued = new AtomicInteger(0);
        CountDownLatch done  = new CountDownLatch(totalTasks);

        long t0 = System.currentTimeMillis();
        try (var exec = virtual
                ? Executors.newVirtualThreadPerTaskExecutor()
                : Executors.newFixedThreadPool(concurrency)) {

            for (int w = 0; w < concurrency; w++) {
                exec.submit(() -> {
                    while (true) {
                        int slot = issued.getAndIncrement();
                        if (slot >= totalTasks) break;
                        countPrimes(PRIME_LIMIT);   // CPU-bound 작업
                        done.countDown();
                    }
                });
            }
            done.await(120, TimeUnit.SECONDS);
        }
        long elapsed = System.currentTimeMillis() - t0;
        if (label != null) System.out.printf("  [%s:%s] %dms%n", label, virtual?"vt":"pt", elapsed);
        return elapsed;
    }

    /** n 이하의 소수 개수 계산 (에라토스테네스 체 — 순수 CPU) */
    static int countPrimes(int n) {
        boolean[] sieve = new boolean[n + 1];
        for (int i = 2; (long) i * i <= n; i++) {
            if (!sieve[i]) {
                for (int j = i * i; j <= n; j += i) sieve[j] = true;
            }
        }
        int count = 0;
        for (int i = 2; i <= n; i++) if (!sieve[i]) count++;
        return count;
    }
}
