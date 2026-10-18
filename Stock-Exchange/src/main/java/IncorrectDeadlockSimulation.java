import stockexchange.models.Company;
import stockexchange.models.Order;
import stockexchange.models.OrderSide;

import java.util.concurrent.CountDownLatch;

public class IncorrectDeadlockSimulation {
    public static void main(String[] args) throws InterruptedException {
        Order firstOrder = new Order(
                java.util.UUID.randomUUID(),
                new Company("TSLA"),
                OrderSide.BUY,
                100,
                1);
        Order secondOrder = new Order(
                java.util.UUID.randomUUID(),
                new Company("TSLA"),
                OrderSide.SELL,
                100,
                1);

        CountDownLatch firstLocksAcquired = new CountDownLatch(2);
        Thread threadOne = new Thread(() -> lockInWrongOrder(
                firstOrder, secondOrder, firstLocksAcquired), "deadlock-thread-1");
        Thread threadTwo = new Thread(() -> lockInWrongOrder(
                secondOrder, firstOrder, firstLocksAcquired), "deadlock-thread-2");

        threadOne.setDaemon(true);
        threadTwo.setDaemon(true);
        threadOne.start();
        threadTwo.start();

        firstLocksAcquired.await();
        Thread.sleep(200);

        boolean threadOneBlocked = threadOne.isAlive();
        boolean threadTwoBlocked = threadTwo.isAlive();

        System.out.println("=== SIMULARE IMPLEMENTARE GRESITA: DEADLOCK ===");
        System.out.println("Thread 1 blocheaza primul ordin, apoi il asteapta pe al doilea.");
        System.out.println("Thread 2 blocheaza al doilea ordin, apoi il asteapta pe primul.");
        System.out.println("Thread 1 este blocat: " + threadOneBlocked);
        System.out.println("Thread 2 este blocat: " + threadTwoBlocked);

        if (!threadOneBlocked || !threadTwoBlocked) {
            throw new AssertionError("Deadlock-ul nu a fost reprodus");
        }

        System.out.println(
                "DEADLOCK DETECTAT: fiecare thread detine un lock si il asteapta pe celalalt.");
        System.out.println(
                "Cauza: lock-urile sunt obtinute in ordine inversa.");
    }

    private static void lockInWrongOrder(
            Order first,
            Order second,
            CountDownLatch firstLocksAcquired) {
        first.lock();
        firstLocksAcquired.countDown();
        try {
            try {
                firstLocksAcquired.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
            second.lock();
            try {
                System.out.println(Thread.currentThread().getName()
                        + " a obtinut ambele lock-uri.");
            } finally {
                second.unlock();
            }
        } finally {
            first.unlock();
        }
    }
}
