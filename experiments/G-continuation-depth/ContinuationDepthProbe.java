import java.lang.management.*;
import java.util.concurrent.CountDownLatch;

/**
 * 실험 G: Continuation 스택 깊이 vs Heap 사용량
 *
 * 실험 A-2에서 "blocking 상태 가상 스레드의 Continuation은 Heap에 있지만
 * 단순 blocking 시 크기가 작다"는 것을 확인했다.
 *
 * 이 실험은 콜스택 깊이(재귀 프레임 수)가 늘어날수록
 * Continuation 크기(Heap 사용량)가 얼마나 커지는지 측정한다.
 *
 * 설계:
 *   - 가상 스레드 THREAD_COUNT개, 각 콜스택 깊이 DEPTH
 *   - 깊이: 1, 10, 50, 100, 200, 500 프레임
 *   - 전체 가상 스레드를 blocking 상태로 유지하면서 Heap used 측정
 *   - per-thread Heap 비용 계산
 *
 * 핵심 질문:
 *   "10만 개 가상 스레드가 각각 100단계 스택을 가지면 Heap 압력은 얼마인가?"
 */
public class ContinuationDepthProbe {

    static final int THREAD_COUNT = 5_000;

    public static void main(String[] args) throws Exception {
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();

        System.out.println("=== Experiment G: Continuation Stack Depth vs Heap ===");
        System.out.printf("virtual threads per run: %,d%n%n", THREAD_COUNT);
        System.out.printf("%-8s %-18s %-18s %-18s%n",
            "depth", "heap_used_KB", "delta_KB", "per_thread_B");
        System.out.println("------------------------------------------------------------------");

        // baseline
        System.gc(); Thread.sleep(500);
        long baseline = mem.getHeapMemoryUsage().getUsed();

        int[] depths = {1, 10, 50, 100, 200, 500};
        for (int depth : depths) {
            System.gc(); Thread.sleep(300);
            long before = mem.getHeapMemoryUsage().getUsed();

            long[] result = holdThreads(depth);
            long used  = result[0];
            long delta = used - before;
            long perThread = delta / THREAD_COUNT;

            System.out.printf("%-8d %-18d %-18d %-18d%n",
                depth, used / 1024, delta / 1024, perThread);
        }

        System.out.println();
        System.out.println("per_thread_B: 가상 스레드 1개당 Continuation Heap 비용 (바이트)");
        System.out.printf("참고: 플랫폼 스레드는 깊이와 무관하게 native stack에 Xss 전체 예약%n");
    }

    /**
     * depth 깊이의 콜스택을 가진 가상 스레드 THREAD_COUNT개를 생성하고
     * 모두 blocking 상태로 유지하면서 heap used를 측정.
     * [0]: heap used (bytes), [1]: elapsed ms
     */
    static long[] holdThreads(int depth) throws Exception {
        var latch = new CountDownLatch(1);
        var ready = new CountDownLatch(THREAD_COUNT);
        var threads = new Thread[THREAD_COUNT];

        for (int i = 0; i < THREAD_COUNT; i++) {
            threads[i] = Thread.ofVirtual().start(() -> {
                try {
                    recurse(depth, latch, ready);
                } catch (Exception ignored) {}
            });
        }

        // 모든 스레드가 blocking 상태에 들어갈 때까지 대기
        ready.await();
        Thread.sleep(200);  // GC 안정화

        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        long used = mem.getHeapMemoryUsage().getUsed();

        latch.countDown();
        for (Thread t : threads) t.join();
        return new long[]{used};
    }

    /**
     * depth 프레임만큼 재귀 후 latch를 기다림.
     * 각 프레임은 지역 변수(long 2개)를 가져 스택을 채움.
     */
    static void recurse(int depth, CountDownLatch latch, CountDownLatch ready)
            throws Exception {
        long a = depth * 31L;   // 지역 변수 — Continuation에 실제로 저장됨
        long b = a ^ 0xDEADBEEFL;
        if (depth <= 1) {
            ready.countDown();
            latch.await();
            return;
        }
        recurse(depth - 1, latch, ready);
        // 컴파일러가 a, b를 제거하지 못하도록 사용
        if (a + b == Long.MIN_VALUE) System.out.println("impossible");
    }
}
