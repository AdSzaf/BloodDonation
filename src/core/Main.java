package core;

import BloodPoints.BloodPointsFederate;
import Hospital.HospitalFederate;
import Transport.TransportFederate;
import java.util.Scanner;
import java.util.ArrayList;
import java.util.List;

public class Main {

    private static final int STARTUP_DELAY_MS = 1000;
    private static final int NUM_BLOOD_POINTS = 3;
    private static final int NUM_HOSPITALS = 2;

    // Flaga sterująca startem wszystkich federatów
    public static volatile boolean startSimulation = false;

    public static void main(String[] args) throws InterruptedException {

        System.out.println("=====================================================");
        System.out.println("  BloodDonation Simulation - Multi-Federate Start");
        System.out.println("  BloodPoints: " + NUM_BLOOD_POINTS + " | Hospitals: " + NUM_HOSPITALS);
        System.out.println("=====================================================");

        List<Thread> threads = new ArrayList<>();

        // 1. Transport (RCKiK) - zawsze jeden, tworzy federację
        Thread transportThread = new Thread(() -> {
            try {
                new TransportFederate().runFederate("TransportCenter");
            } catch (Exception e) { e.printStackTrace(); }
        });
        threads.add(transportThread);
        transportThread.start();
        Thread.sleep(STARTUP_DELAY_MS);

        // 2. BloodPoints - wiele instancji
        for (int i = 1; i <= NUM_BLOOD_POINTS; i++) {
            final int id = i;
            Thread bpThread = new Thread(() -> {
                try {
                    new BloodPointsFederate().runFederate("BloodPoint_" + id);
                } catch (Exception e) { e.printStackTrace(); }
            });
            threads.add(bpThread);
            bpThread.start();
        }

        // 3. Hospitals - wiele instancji
        for (int i = 1; i <= NUM_HOSPITALS; i++) {
            final int id = i;
            Thread hThread = new Thread(() -> {
                try {
                    // Przekazujemy ID szpitala do federatu
                    new HospitalFederate(id).runFederate("Hospital_" + id);
                } catch (Exception e) { e.printStackTrace(); }
            });
            threads.add(hThread);
            hThread.start();
        }

        // Centralne czekanie na Enter
        System.out.println("\n[core.Main] Wszystkie wątki uruchomione. Czekam na połączenie z RTI...");
        Thread.sleep(2000);
        System.out.println("[core.Main] NACIŚNIJ ENTER, ABY ROZPOCZĄĆ SYMULACJĘ DLA WSZYSTKICH...");
        new Scanner(System.in).nextLine();

        // Zwolnienie blokady
        startSimulation = true;

        for (Thread t : threads) {
            t.join();
        }
        System.out.println("[core.Main] Symulacja zakończona.");
    }
}
