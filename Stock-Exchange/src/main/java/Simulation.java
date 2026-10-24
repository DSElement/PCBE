import stockexchange.exchange.OrderMatcher;

import java.time.Duration;
import java.util.List;

public class Simulation {
    public static void main(String[] args) throws InterruptedException {
        MarketSimulation market = new MarketSimulation(
                new OrderMatcher(), List.of("TSLA", "AAPL"), 3, 3, 30, 100, 2);

        System.out.println("=== CORRECT IMPLEMENTATION SIMULATION ===");
        if (!market.run(Duration.ofSeconds(30))) {
            System.out.println("The simulation did not finish in the allotted time.");
            System.exit(1);
        }

        market.printReport();
        List<String> violations = market.findViolations();
        if (!violations.isEmpty()) {
            violations.forEach(violation -> System.out.println("ERROR: " + violation));
            System.exit(1);
        }

        System.out.println("VERIFICATION PASSED: orders stayed consistent and there were no deadlocks.");
        System.exit(0);
    }
}
