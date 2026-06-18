import com.zaxxer.hikari.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 실험 B: HikariCP 풀 사이즈 vs 가상 스레드 수 → p99 곡선
 *
 * 가설: 풀이 작으면 가상 스레드를 늘려도 p99가 지수적으로 폭발한다.
 *
 * 설계:
 *   - H2 in-memory DB, SLEEP 함수로 10ms 인공 쿼리 지연 (실제 I/O 모사)
 *   - poolSize=5 vs poolSize=50
 *   - 동시 가상 스레드: 10 → 50 → 100 → 200 → 400 (Ladder)
 *   - 각 조건: 총 2000 ops 완료 후 p50/p95/p99/max 측정
 */
public class PoolStarvation {

    static final int QUERY_SLEEP_MS = 10;
    static final int TOTAL_OPS      = 2000;
    static final int WARMUP_OPS     = 100;

    public static void main(String[] args) throws Exception {
        System.out.println("=== Experiment B: HikariCP Pool Starvation ===");
        System.out.printf("query_sleep=%dms  total_ops=%d%n%n", QUERY_SLEEP_MS, TOTAL_OPS);
        System.out.printf("%-12s %-10s %-10s %-10s %-10s %-10s%n",
            "pool_size", "vthreads", "p50(ms)", "p95(ms)", "p99(ms)", "max(ms)");
        System.out.println("-".repeat(65));

        int[] pools   = {5, 50};
        int[] threads = {10, 50, 100, 200, 400};

        for (int pool : pools) {
            for (int vt : threads) {
                long[] s = runBatch(pool, vt);
                System.out.printf("%-12d %-10d %-10d %-10d %-10d %-10d%n",
                    pool, vt, s[0], s[1], s[2], s[3]);
                System.out.flush();
            }
            System.out.println();
        }
    }

    static long[] runBatch(int poolSize, int concurrency) throws Exception {
        HikariConfig cfg = new HikariConfig();
        // DB 이름을 파라미터로 분리해 실험 간 격리
        cfg.setJdbcUrl("jdbc:h2:mem:db_p" + poolSize + "_c" + concurrency
                       + ";DB_CLOSE_DELAY=-1");
        cfg.setDriverClassName("org.h2.Driver");
        cfg.setMaximumPoolSize(poolSize);
        cfg.setMinimumIdle(poolSize);
        cfg.setConnectionTimeout(30_000);   // 30s (큐 대기 허용)
        cfg.setInitializationFailTimeout(0);

        try (HikariDataSource ds = new HikariDataSource(cfg)) {
            // H2 SLEEP 함수 등록 (long millis → void)
            try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
                st.execute("CREATE ALIAS SLEEP FOR \"java.lang.Thread.sleep(long)\"");
            }

            // 워밍업
            measure(ds, Math.min(concurrency, 20), WARMUP_OPS);
            Thread.sleep(300);

            // 실측
            long[] latencies = measure(ds, concurrency, TOTAL_OPS);
            Arrays.sort(latencies);
            int n = latencies.length;
            return new long[]{
                latencies[(int)(n * 0.50)],
                latencies[(int)(n * 0.95)],
                latencies[(int)(n * 0.99)],
                latencies[n - 1]
            };
        }
    }

    /**
     * virtualThread concurrency 개를 동시에 유지하면서 totalOps 건 처리.
     * 각 op: 커넥션 획득 → SLEEP(QUERY_SLEEP_MS) → 커넥션 반환.
     * 반환값: 각 op의 전체 소요시간 배열 (커넥션 대기 포함).
     */
    static long[] measure(HikariDataSource ds, int concurrency, int totalOps)
            throws Exception {

        long[] times = new long[totalOps];
        AtomicInteger issued = new AtomicInteger(0);
        CountDownLatch done  = new CountDownLatch(totalOps);

        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            // concurrency 개의 워커를 유지; 각 워커는 자기 할당량을 소진할 때까지 루프
            for (int w = 0; w < concurrency; w++) {
                exec.submit(() -> {
                    while (true) {
                        int slot = issued.getAndIncrement();
                        if (slot >= totalOps) break;

                        long t0 = System.currentTimeMillis();
                        try (Connection conn = ds.getConnection();
                             Statement   st   = conn.createStatement()) {
                            st.execute("CALL SLEEP(" + QUERY_SLEEP_MS + ")");
                        } catch (SQLException ignored) {}
                        times[slot] = System.currentTimeMillis() - t0;
                        done.countDown();
                    }
                });
            }
            done.await(120, TimeUnit.SECONDS);
        }
        return times;
    }
}
