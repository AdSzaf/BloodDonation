package Hospital;

import java.util.*;

/**
 * Logika biznesowa szpitala / banku krwi szpitalnego.
 *
 * Zmiany v3:
 *  - Sledzi otwarte zamowienia (requestId -> czas wyslania) w pendingRequests
 *  - checkTimeouts(currentTime): zamowienie bez odpowiedzi po TIMEOUT_DAYS
 *    jest liczone jako niedobor -> realistyczny wskaznik niedoboru po stronie szpitala
 *  - registerDelivery(requestId): oznacza zamowienie jako zrealizowane
 */
public class Hospital {

    public static final String[] BLOOD_TYPES = {
            "A_PLUS", "A_MINUS", "B_PLUS", "B_MINUS",
            "AB_PLUS", "AB_MINUS", "0_PLUS", "0_MINUS"
    };

    public static final float UNIT_VOLUME = 0.45f;

    private static final double URGENT_PROBABILITY   = 0.25;
    private static final int    MIN_SEPARATION_TIME  = 2;
    private static final int    MAX_SEPARATION_TIME  = 8;
    private static final int    MIN_REQUEST_INTERVAL = 3;
    private static final int    MAX_REQUEST_INTERVAL = 15;

    //Stałe terminów ważności składników
    private static final double EXPIRY_RED_CELLS  = 42.0;  // krwinki czerwone
    private static final double EXPIRY_PLASMA     = 365.0; // osocze (mrożone)
    private static final double EXPIRY_PLATELETS  = 5.0;   // płytki krwi

    // Proporcje potrzebnego skladnika: osocze 50%, krwinki 35%, plytki 15%
    private static final double PROB_PLASMA    = 0.50;
    private static final double PROB_RED_CELLS = 0.85; // 0.50+0.35

    // Zamowienie bez odpowiedzi po tym czasie uznawane za niezrealizowane [j.s.]
    // Nagle: 5 j.s., planowe: 20 j.s.
    private static final double TIMEOUT_URGENT  = 5.0;
    private static final double TIMEOUT_PLANNED = 20.0;

    // -----------------------------------------------------------------------
    private final int hospitalId;
    private final Random random;
    private int nextRequestId = 1;
    private int expiredComponentCount = 0;


    // Otwarte zamowienia: requestId -> [czasWyslania, isUrgent(0/1)]
    private final Map<Integer, double[]> pendingRequests = new HashMap<>();

    // Statystyki
    private int    totalRequests       = 0;
    private int    unmetRequests       = 0;
    private int    deliveredUnitsCount = 0;
    private double totalBloodAgeDays   = 0.0;

    // Skladniki po separacji
    private int plasmaUnits   = 0;
    private int plateletUnits = 0;
    private int redCellUnits  = 0;
    private int usedPlasmaUnits   = 0;
    private int usedPlateletUnits = 0;
    private int usedRedCellUnits  = 0;

    // -----------------------------------------------------------------------

    public Hospital(int hospitalId) {
        this.hospitalId = hospitalId;
        this.random = new Random();
        System.out.println("Hospital #" + hospitalId + ": Zainicjalizowano szpital.");
    }

    // -----------------------------------------------------------------------
    // Generowanie zapotrzebowania
    // -----------------------------------------------------------------------

    public int getTimeToNextRequest() {
        return random.nextInt(MAX_REQUEST_INTERVAL - MIN_REQUEST_INTERVAL + 1)
                + MIN_REQUEST_INTERVAL;
    }

    public boolean isUrgentRequest() {
        return random.nextDouble() < URGENT_PROBABILITY;
    }

    public String randomBloodType() {
        return BLOOD_TYPES[random.nextInt(BLOOD_TYPES.length)];
    }

    public float randomRequestedAmount() {
        int units = random.nextInt(3) + 1;
        return units * UNIT_VOLUME;
    }

    /**
     * Rejestruje nowe zamowienie i zwraca jego ID.
     * Zapisuje czas wyslania do mapy otwartych zamowien.
     */
    public int registerRequest(boolean isUrgent, double currentTime) {
        int id = nextRequestId++;
        totalRequests++;
        pendingRequests.put(id, new double[]{ currentTime, isUrgent ? 1.0 : 0.0 });
        return id;
    }

    /**
     * Oznacza zamowienie jako zrealizowane (dostawa otrzymana).
     * Wywolywane z ambassadora po odebraniu BloodTransported.
     */
    public void registerDelivery(int requestId) {
        if (pendingRequests.containsKey(requestId)) {
            pendingRequests.remove(requestId);
            System.out.println("Hospital #" + hospitalId
                    + " [DOSTAWA]: Zrealizowano zamowienie #" + requestId);
        } else {
            // To jest kluczowy moment - krew dotarła, ale zamówienie już wygasło (timeout)
            System.out.println("Hospital #" + hospitalId
                    + " [SPÓŹNIONA DOSTAWA]: Odebrano krew dla zamowienia #" + requestId
                    + ", ktore juz wygasło!");
            // Opcjonalnie: można tu zmniejszyć licznik unmetRequests,
            // jeśli chcemy uznać spóźnioną dostawę za "lepiej późno niż wcale"
        }
    }

    /**
     * Sprawdza czy jakies otwarte zamowienia przekroczyly timeout.
     * Przekroczone -> liczone jako niedobor i usuwane z mapy.
     * Wywolywane co krok symulacyjny z HospitalFederate.
     */
    public void checkTimeouts(double currentTime) {
        Iterator<Map.Entry<Integer, double[]>> it = pendingRequests.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, double[]> entry = it.next();
            int     reqId     = entry.getKey();
            double  sentTime  = entry.getValue()[0];
            boolean urgent    = entry.getValue()[1] == 1.0;
            double  timeout   = urgent ? TIMEOUT_URGENT : TIMEOUT_PLANNED;

            if (currentTime - sentTime > timeout) {
                unmetRequests++;
                it.remove();
                System.out.println("Hospital #" + hospitalId
                        + " [TIMEOUT]: Zamowienie #" + reqId
                        + " niezrealizowane po " + (currentTime - sentTime) + " j.s."
                        + " | Wskaznik niedoboru: "
                        + String.format("%.1f", getShortageRate()) + "%");
            }
        }
    }

    // -----------------------------------------------------------------------
    // Separacja krwi na skladniki
    // -----------------------------------------------------------------------

    public int getSeparationTime() {
        return random.nextInt(MAX_SEPARATION_TIME - MIN_SEPARATION_TIME + 1)
                + MIN_SEPARATION_TIME;
    }

    public boolean separateBlood(float bloodAmount, double currentTime, double donationTime) {
        int units = Math.max(1, Math.round(bloodAmount / UNIT_VOLUME));
        double ageInDays = currentTime - donationTime;
        totalBloodAgeDays   += ageInDays;
        deliveredUnitsCount += units;

        // Losuj jaki skladnik byl potrzebny
        double roll = random.nextDouble();
        String neededComponent;
        double expiryOfNeeded;
        if (roll < PROB_PLASMA) {
            neededComponent  = "osocze";
            expiryOfNeeded   = EXPIRY_PLASMA;
        } else if (roll < PROB_RED_CELLS) {
            neededComponent  = "krwinki";
            expiryOfNeeded   = EXPIRY_RED_CELLS;
        } else {
            neededComponent  = "plytki";
            expiryOfNeeded   = EXPIRY_PLATELETS;
        }

        boolean componentOk = ageInDays <= expiryOfNeeded;

        // Zawsze produkujemy wszystkie 3 skladniki z kazdej jednostki
        plasmaUnits   += units;
        plateletUnits += units;
        redCellUnits  += units;

        if (!componentOk) {
            expiredComponentCount += units;
            System.out.println("Hospital #" + hospitalId
                    + " [PRZETERMINOWANY]: potrzebny skladnik='" + neededComponent
                    + "' wiek=" + String.format("%.1f", ageInDays) + " dni"
                    + " (limit=" + expiryOfNeeded + ") -> zamowienie NIEZREALIZOWANE");
        } else {
            // Tylko potrzebny i zdatny skladnik trafia do "uzytych"
            if (neededComponent.equals("osocze")) {
                usedPlasmaUnits += units;
            } else if (neededComponent.equals("krwinki")) {
                usedRedCellUnits += units;
            } else {
                usedPlateletUnits += units;
            }
            System.out.println("Hospital #" + hospitalId + " [SEPARACJA]: "
                    + units + " jednostek | skladnik=" + neededComponent
                    + " | wiek=" + String.format("%.1f", ageInDays) + " dni"
                    + " | uzyto: osocze=" + usedPlasmaUnits
                    + " plytki=" + usedPlateletUnits
                    + " krwinki=" + usedRedCellUnits);
        }

        return componentOk;
    }

    // -----------------------------------------------------------------------
    // Statystyki
    // -----------------------------------------------------------------------

    public double getShortageRate() {
        if (totalRequests == 0) return 0.0;
        return (double) unmetRequests / totalRequests * 100.0;
    }

    public double getAverageBloodAgeDays() {
        if (deliveredUnitsCount == 0) return 0.0;
        return totalBloodAgeDays / deliveredUnitsCount;
    }

    public String getStatsReport() {
        return "========================================\n"
                + "STATYSTYKI Szpital #" + hospitalId + "\n"
                + "  Laczne zamowienia:      " + totalRequests + "\n"
                + "  Niezrealizowane:        " + unmetRequests + "\n"
                + "  Wskaznik niedoboru:     " + String.format("%.1f", getShortageRate()) + "%\n"
                + "  Dostarczone jednostki:  " + deliveredUnitsCount + "\n"
                + "  Sredni wiek krwi:       " + String.format("%.2f", getAverageBloodAgeDays()) + " dni\n"
                + "  Wyprodukowane skladniki:    osocze=" + plasmaUnits
                + "  plytki=" + plateletUnits
                + "  krwinki=" + redCellUnits + "\n"
                + "  Faktycznie uzyte skladniki: osocze=" + usedPlasmaUnits
                + "  plytki=" + usedPlateletUnits
                + "  krwinki=" + usedRedCellUnits + "\n"
                + "  Przeterminowane skladniki:  " + expiredComponentCount + "\n"
                + "========================================";
    }

    public int getHospitalId()      { return hospitalId; }
    public int getTotalRequests()   { return totalRequests; }
    public int getUnmetRequests()   { return unmetRequests; }
    public int getDeliveredUnits()  { return deliveredUnitsCount; }
    public int getPendingCount()    { return pendingRequests.size(); }
    public int getPlasmaUnits()     { return plasmaUnits; }
    public int getPlateletUnits()   { return plateletUnits; }
    public int getRedCellUnits()    { return redCellUnits; }
    public int getExpiredComponentCount() { return expiredComponentCount; }
    public int getUsedPlasmaUnits()   { return usedPlasmaUnits; }
    public int getUsedPlateletUnits() { return usedPlateletUnits; }
    public int getUsedRedCellUnits()  { return usedRedCellUnits; }
}
