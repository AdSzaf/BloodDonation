package core;

import BloodPoints.BloodPointsFederate;
import Hospital.HospitalFederate;
import Transport.TransportFederate;
import hla.rti1516e.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class Main {

    private static final int STARTUP_DELAY_MS = 3000;
    private static final int NUM_BLOOD_POINTS = 3;
    private static final int NUM_HOSPITALS = 2;

    // Latwa do zmiany dlugosc symulacji w jednostkach czasu HLA.
    public static final double SIMULATION_END_TIME = 200.0;

    // Flaga sterujaca startem wszystkich federatow.
    public static volatile boolean startSimulation = false;

    public static void main(String[] args) throws InterruptedException {

        // Spróbuj posprzątać po ewentualnej poprzedniej sesji
        try {
            RTIambassador cleanup = RtiFactoryFactory.getRtiFactory().getRtiAmbassador();
            cleanup.connect(new NullFederateAmbassador(){}, CallbackModel.HLA_EVOKED);
            cleanup.destroyFederationExecution("BloodSupplyFederation");
            cleanup.disconnect();
            System.out.println("[core.Main] Wyczyszczono pozostalosci poprzedniej federacji.");
        } catch (Exception e) {
            // Normalnie - federacja nie istnieje lub nie da się wyczyścić
        }

        System.out.println("=====================================================");
        System.out.println("  BloodDonation Simulation - Multi-Federate Start");
        System.out.println("  BloodPoints: " + NUM_BLOOD_POINTS + " | Hospitals: " + NUM_HOSPITALS);
        System.out.println("  Simulation time limit: t=" + SIMULATION_END_TIME);
        System.out.println("=====================================================");

        List<Thread> threads = new ArrayList<>();

        Thread transportThread = new Thread(() -> {
            try {
                new TransportFederate().runFederate("TransportCenter");
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        threads.add(transportThread);
        transportThread.start();
        Thread.sleep(STARTUP_DELAY_MS);

        for (int i = 1; i <= NUM_BLOOD_POINTS; i++) {
            final int id = i;
            Thread bpThread = new Thread(() -> {
                try {
                    new BloodPointsFederate().runFederate("BloodPoint_" + id);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
            threads.add(bpThread);
            bpThread.start();
        }

        for (int i = 1; i <= NUM_HOSPITALS; i++) {
            final int id = i;
            Thread hThread = new Thread(() -> {
                try {
                    new HospitalFederate(id).runFederate("Hospital_" + id);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
            threads.add(hThread);
            hThread.start();
        }

        System.out.println("\n[core.Main] Wszystkie watki uruchomione. Czekam na polaczenie z RTI...");
        Thread.sleep(2000);
        System.out.println("[core.Main] Nacisnij ENTER, aby rozpoczac symulacje dla wszystkich...");
        new Scanner(System.in).nextLine();

        startSimulation = true;

        for (Thread t : threads) {
            t.join();
        }

        SimulationStats.printHospitalReports();
        SimulationStats.writeReport(SIMULATION_END_TIME);
        System.out.println("[core.Main] Symulacja zakonczona.");
        System.out.println("[core.Main] Raport HTML: logs/simulation-report.html");
    }
}
