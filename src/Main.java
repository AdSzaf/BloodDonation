import BloodPoints.BloodPointsFederate;
import Hospital.HospitalFederate;
import Transport.TransportFederate;

/**
 * Main - punkt startowy calej symulacji BloodSupplyFederation.
 *
 * Uruchamia trzy federaty w osobnych watkach:
 *   1. BloodPointsFederate  - punkty poboru krwi (producent: BloodCollected)
 *   2. TransportFederate    - centrum logistyczne RCKiK (posrednik)
 *   3. HospitalFederate     - szpital / bank krwi (konsument: Request)
 *
 * Kolejnosc uruchamiania:
 *   Transport startuje jako pierwszy, bo jako jedyny moze tworzyc federacje
 *   i musi byc gotowy na subskrypcje zanim pozostali zaczna publikowac.
 *   BloodPoints i Hospital startuja z krotkim opoznieniem.
 */
public class Main {

    private static final int STARTUP_DELAY_MS = 2000; // opoznienie miedzy startami federatow

    public static void main(String[] args) throws InterruptedException {

        System.out.println("=============================================================");
        System.out.println("  BloodDonation Simulation - start");
        System.out.println("  Federacja: BloodSupplyFederation");
        System.out.println("  Federaty: BloodPoints | Transport | Hospital");
        System.out.println("=============================================================");

        // --- Watek 1: Transport (RCKiK) ---
        // Startuje pierwszy - bedzie probowal utworzyc federacje
        Thread transportThread = new Thread(() -> {
            try {
                System.out.println("[Main] Uruchamiam TransportFederate...");
                new TransportFederate().runFederate("Transport");
            } catch (Exception e) {
                System.err.println("[Main] BLAD TransportFederate: " + e.getMessage());
                e.printStackTrace();
            }
        }, "Thread-Transport");

        // --- Watek 2: BloodPoints ---
        Thread bloodPointsThread = new Thread(() -> {
            try {
                System.out.println("[Main] Uruchamiam BloodPointsFederate...");
                new BloodPointsFederate().runFederate("BloodPoints");
            } catch (Exception e) {
                System.err.println("[Main] BLAD BloodPointsFederate: " + e.getMessage());
                e.printStackTrace();
            }
        }, "Thread-BloodPoints");

        // --- Watek 3: Hospital ---
        Thread hospitalThread = new Thread(() -> {
            try {
                System.out.println("[Main] Uruchamiam HospitalFederate...");
                new HospitalFederate().runFederate("Hospital");
            } catch (Exception e) {
                System.err.println("[Main] BLAD HospitalFederate: " + e.getMessage());
                e.printStackTrace();
            }
        }, "Thread-Hospital");

        // Uruchom Transport jako pierwszy
        transportThread.start();
        Thread.sleep(STARTUP_DELAY_MS);

        // Uruchom BloodPoints
        bloodPointsThread.start();
        Thread.sleep(STARTUP_DELAY_MS);

        // Uruchom Hospital
        hospitalThread.start();

        // Czekaj na zakonczenie wszystkich
        transportThread.join();
        bloodPointsThread.join();
        hospitalThread.join();

        System.out.println("=============================================================");
        System.out.println("  Symulacja zakonczona.");
        System.out.println("=============================================================");
    }
}