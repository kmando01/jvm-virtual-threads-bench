import java.io.*;
import java.nio.file.*;
import java.util.concurrent.CountDownLatch;

/**
 * 자동 측정 버전: 스레드 N개 생성 → NMT baseline → holdSeconds 대기 →
 * jcmd로 self NMT 스냅샷 → 결과 파일 저장 → 종료.
 *
 * Usage: java -XX:NativeMemoryTracking=detail NMTProbe <virtual|platform> <count> <outFile>
 */
public class NMTProbe {

    public static void main(String[] args) throws Exception {
        String mode     = args[0];           // virtual | platform
        int    count    = Integer.parseInt(args[1]);
        String outFile  = args[2];
        boolean vt      = "virtual".equals(mode);

        long pid = ProcessHandle.current().pid();
        System.out.printf("[%s] PID=%d, count=%,d%n", mode, pid, count);

        // baseline (스레드 생성 전)
        String before = nmtSummary(pid);

        // 스레드 생성
        var latch = new CountDownLatch(1);
        Thread.Builder builder = vt
            ? Thread.ofVirtual().name("vt-", 0)
            : Thread.ofPlatform().name("pt-", 0);

        Thread[] threads = new Thread[count];
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < count; i++) {
            threads[i] = builder.start(() -> {
                try { latch.await(); } catch (InterruptedException e) {}
            });
        }
        long createMs = System.currentTimeMillis() - t0;
        System.out.printf("[%s] 생성 완료 %,d ms%n", mode, createMs);

        // JVM이 안정되기를 잠시 기다림
        Thread.sleep(2000);

        // OS 스레드 수
        int osThreads = osThreadCount(pid);

        // NMT 스냅샷 (스레드 유지 중)
        String after = nmtSummary(pid);

        // 결과 파일 저장
        try (PrintWriter pw = new PrintWriter(outFile)) {
            pw.printf("=== NMT Probe: mode=%s count=%,d createMs=%d osThreads=%d ===%n",
                      mode, count, createMs, osThreads);
            pw.println("--- BEFORE (baseline) ---");
            pw.println(before);
            pw.println("--- AFTER (threads held) ---");
            pw.println(after);
        }
        System.out.printf("[%s] 결과 저장: %s%n", mode, outFile);

        // 스레드 해제
        latch.countDown();
        for (Thread t : threads) t.join();
        System.out.printf("[%s] 완료%n", mode);
    }

    static String nmtSummary(long pid) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
            "jcmd", String.valueOf(pid), "VM.native_memory", "summary"
        );
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        return out;
    }

    static int osThreadCount(long pid) {
        try {
            ProcessBuilder pb = new ProcessBuilder("ps", "-M", String.valueOf(pid));
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor();
            // 헤더 라인 1줄 제외, 나머지 = 스레드 수
            long lines = out.lines().filter(l -> !l.trim().isEmpty()).count();
            return (int) Math.max(0, lines - 1);
        } catch (Exception e) {
            return -1;
        }
    }
}
