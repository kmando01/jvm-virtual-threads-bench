import java.lang.ScopedValue;
import java.lang.management.ManagementFactory;
import java.util.concurrent.CountDownLatch;

/**
 * 실험 H: ThreadLocal × 가상 스레드 heap 비용 측정
 *
 * 모드:
 *   none    - 페이로드 없음, 순수 vthread 기본 비용 (baseline)
 *   tl      - 각 vthread가 자신의 ThreadLocal에 new byte[payloadKB*1024] 저장
 *   itl     - 메인 스레드가 InheritableThreadLocal에 설정 → vthread가 상속
 *   scoped  - ScopedValue에 shared payload 바인딩 → vthread끼리 공유
 *
 * 핵심 대비:
 *   tl     : N개 vthread × payloadKB  → heap N배 증가 (각 스레드 독립 복사본)
 *   itl    : 상속된 값은 reference 공유 → heap = payload 1개 분량
 *   scoped : ScopedValue는 immutable + shared → heap = payload 1개 분량
 */
public class ThreadLocalCostProbe {

    // 스코프드 밸류 (모드=scoped 전용)
    static final ScopedValue<byte[]> SV = ScopedValue.newInstance();

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: ThreadLocalCostProbe <none|tl|itl|scoped> <payloadKB> <N>");
            System.exit(1);
        }
        String mode    = args[0];
        int payloadKB  = Integer.parseInt(args[1]);
        int n          = Integer.parseInt(args[2]);
        int payBytes   = payloadKB * 1024;

        // TL/ITL 인스턴스
        ThreadLocal<byte[]>            tl  = new ThreadLocal<>();
        InheritableThreadLocal<byte[]> itl = new InheritableThreadLocal<>();

        // ITL 모드: 메인(부모) 스레드에 미리 설정 → 자식이 상속
        if ("itl".equals(mode) && payBytes > 0) {
            itl.set(new byte[payBytes]);
        }

        // Scoped 모드: 공유 페이로드 1개 생성
        byte[] sharedPayload = ("scoped".equals(mode) && payBytes > 0) ? new byte[payBytes] : null;

        forceGc();
        long baselineUsed = used();

        var ready = new CountDownLatch(n);
        var hold  = new CountDownLatch(1);

        long t0 = System.currentTimeMillis();
        for (int i = 0; i < n; i++) {
            if ("scoped".equals(mode) && payBytes > 0) {
                // ScopedValue 바인딩 안에서 vthread 시작
                final byte[] payload = sharedPayload;
                Thread.ofVirtual().start(() ->
                    ScopedValue.where(SV, payload).run(() -> {
                        ready.countDown();
                        try { hold.await(); } catch (InterruptedException e) {}
                    })
                );
            } else {
                Thread.ofVirtual().start(() -> {
                    if ("tl".equals(mode) && payBytes > 0)  tl.set(new byte[payBytes]);
                    // itl: 이미 상속됨, 확인만
                    if ("itl".equals(mode) && payBytes > 0 && itl.get() == null) {
                        // 상속 안 된 경우 (가상 스레드가 ITL 무시) → 직접 설정
                        itl.set(new byte[payBytes]);
                    }
                    ready.countDown();
                    try { hold.await(); } catch (InterruptedException e) {}
                });
            }
        }

        ready.await();
        long createMs = System.currentTimeMillis() - t0;

        // GC 안정화
        Thread.sleep(1500);
        forceGc();

        long afterUsed = used();
        long delta     = afterUsed - baselineUsed;
        double perVtKB = (double) delta / n / 1024.0;

        System.out.printf("RESULT mode=%-7s payKB=%-5d N=%-7d createMs=%-5d " +
                          "deltaKB=%-9.0f perVtKB=%-8.2f%n",
            mode, payloadKB, n, createMs,
            delta / 1024.0, perVtKB);

        hold.countDown();
    }

    static long used() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    static void forceGc() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(150);
        }
    }
}
