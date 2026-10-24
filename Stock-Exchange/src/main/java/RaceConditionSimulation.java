import stockexchange.exchange.UnlockedOrderMatcher;

import java.time.Duration;
import java.util.List;

public class RaceConditionSimulation {
    public static void main(String[] args) throws InterruptedException {
        MarketSimulation market = new MarketSimulation(
                new UnlockedOrderMatcher(), List.of("TSLA"), 4, 4, 40, 100, 1);

        System.out.println("=== INCORRECT IMPLEMENTATION SIMULATION: RACE CONDITION ===");
        if (!market.run(Duration.ofSeconds(60))) {
            System.out.println("The simulation did not finish in the allotted time.");
            System.exit(1);
        }

        List<String> violations = market.findViolations();
        if (violations.isEmpty()) {
            System.out.println("The race condition was not reproduced.");
            System.exit(1);
        }

        System.out.println("RACE CONDITION DETECTED: " + violations.size() + " inconsistencies:");
        violations.forEach(violation -> System.out.println("  " + violation));
        System.out.println("Cause: checking and updating the quantities is not atomic.");
        System.exit(0);
    }
}
