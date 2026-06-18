import java.io.*;
import java.lang.management.*;
import java.util.concurrent.CountDownLatch;

public class HeapUsageProbe {
    public static void main(String[] args) throws Exception {
        String mode  = args[0];
        int    count = Integer.parseInt(args[1]);
        boolean vt   = "virtual".equals(mode);

        Runtime rt = Runtime.getRuntime();
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();

        // baseline
        System.gc(); Thread.sleep(500);
        long heapBefore = mem.getHeapMemoryUsage().getUsed();

        var latch = new CountDownLatch(1);
        Thread.Builder b = vt
            ? Thread.ofVirtual().name("vt-", 0)
            : Thread.ofPlatform().name("pt-", 0);

        Thread[] threads = new Thread[count];
        for (int i = 0; i < count; i++) {
            threads[i] = b.start(() -> {
                try { latch.await(); } catch (InterruptedException e) {}
            });
        }
        Thread.sleep(1000);

        long heapAfter = mem.getHeapMemoryUsage().getUsed();
        long delta = heapAfter - heapBefore;

        System.out.printf("mode=%-8s count=%,6d  heapBefore=%,d KB  heapAfter=%,d KB  delta=%,d KB  perThread=%.1f KB%n",
            mode, count, heapBefore/1024, heapAfter/1024, delta/1024, (double)delta/count/1024);

        latch.countDown();
        for (Thread t : threads) t.join();
    }
}
