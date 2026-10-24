import stockexchange.exchange.UnorderedOrderMatcher;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.List;

public class DeadlockSimulation {
    private static final int MAX_ATTEMPTS = 5;

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== INCORRECT IMPLEMENTATION SIMULATION: DEADLOCK ===");

        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            MarketSimulation market = new MarketSimulation(
                    new UnorderedOrderMatcher(), List.of("TSLA"), 4, 4, 40, 100, 1);
            boolean completed = market.run(Duration.ofSeconds(5));
            System.out.println("Attempt " + attempt + ": simulation finished: " + completed);

            // The JVM records which thread owns and which thread awaits every lock, including
            // java.util.concurrent ones, so it can find threads waiting on each other in a cycle.
            // It returns null when there is no such cycle.
            long[] deadlockedThreadIds = threadBean.findDeadlockedThreads();
            if (deadlockedThreadIds != null) {
                printDeadlock(threadBean, deadlockedThreadIds);
                System.exit(0);
            }
        }

        System.out.println("The deadlock was not reproduced.");
        System.exit(1);
    }

    private static void printDeadlock(ThreadMXBean threadBean, long[] deadlockedThreadIds) {
        System.out.println("DEADLOCK DETECTED:");
        for (ThreadInfo thread : threadBean.getThreadInfo(deadlockedThreadIds)) {
            System.out.println("  " + thread.getThreadName() + " is waiting for a lock held by "
                    + thread.getLockOwnerName());
        }
        System.out.println("Cause: different threads lock the same two orders in different orders.");
    }
}
