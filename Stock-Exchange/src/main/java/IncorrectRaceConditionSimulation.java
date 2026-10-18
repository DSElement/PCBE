import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public class IncorrectRaceConditionSimulation {
    private static final class IncorrectInventory {
        private int remainingQuantity = 1;
        private final AtomicInteger recordedSales = new AtomicInteger();

        void sellOne(
                CountDownLatch bothThreadsRead,
                CountDownLatch allowWrites) throws InterruptedException {
            int observedQuantity = remainingQuantity;
            bothThreadsRead.countDown();
            bothThreadsRead.await();

            if (observedQuantity > 0) {
                allowWrites.await();
                remainingQuantity = observedQuantity - 1;
                recordedSales.incrementAndGet();
            }
        }
    }

    public static void main(String[] args) throws InterruptedException {
        IncorrectInventory inventory = new IncorrectInventory();
        CountDownLatch bothThreadsRead = new CountDownLatch(2);
        CountDownLatch allowWrites = new CountDownLatch(1);

        Thread buyerOne = new Thread(() -> sellOne(
                inventory, bothThreadsRead, allowWrites), "race-buyer-1");
        Thread buyerTwo = new Thread(() -> sellOne(
                inventory, bothThreadsRead, allowWrites), "race-buyer-2");

        buyerOne.start();
        buyerTwo.start();
        bothThreadsRead.await();
        allowWrites.countDown();
        buyerOne.join();
        buyerTwo.join();

        System.out.println("=== SIMULARE IMPLEMENTARE GRESITA: RACE CONDITION ===");
        System.out.println("Cantitatea initiala: 1");
        System.out.println("Cumparatori care au citit simultan cantitatea: 2");
        System.out.println("Vanzari inregistrate: "
                + inventory.recordedSales.get());
        System.out.println("Cantitate ramasa: " + inventory.remainingQuantity);

        if (inventory.recordedSales.get() != 2
                || inventory.remainingQuantity != 0) {
            throw new AssertionError(
                    "Scenariul nu a reprodus race condition-ul asteptat");
        }

        System.out.println(
                "RACE CONDITION DETECTAT: doua tranzactii au consumat aceeasi unitate.");
        System.out.println(
                "Cauza: verificarea si reducerea cantitatii nu au fost atomice.");
    }

    private static void sellOne(
            IncorrectInventory inventory,
            CountDownLatch bothThreadsRead,
            CountDownLatch allowWrites) {
        try {
            inventory.sellOne(bothThreadsRead, allowWrites);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
