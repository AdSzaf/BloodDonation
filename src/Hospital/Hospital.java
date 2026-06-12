package Hospital;

import java.util.Random;

/**
 * Logika biznesowa szpitala / banku krwi szpitalnego.
 *
 * Odpowiada za:
 *  - generowanie zapotrzebowania (planowe / nagle)
 *  - separacje dostarczonej krwi pelnej na skladniki
 *  - zbieranie statystyk: wskaznik niedoboru i sredni wiek krwi
 */
public class Hospital {

    // Grupy krwi (te same co w BloodPoints)
    public static final String[] BLOOD_TYPES = {
            "A_PLUS", "A_MINUS", "B_PLUS", "B_MINUS",
            "AB_PLUS", "AB_MINUS", "0_PLUS", "0_MINUS"
    };

    // Standardowa objetosc jednostki [l]
    public static final float UNIT_VOLUME = 0.45f;

    // Prawd. zapotrzebowania naglego vs planowego
    private static final double URGENT_PROBABILITY = 0.25;

    // Czas separacji krwi na skladniki: 2-8 jednostek symulacyjnych
    private static final int MIN_SEPARATION_TIME = 2;
    private static final int MAX_SEPARATION_TIME = 8;

    // Czas miedzy kolejnymi zapotrzebowaniami
    private static final int MIN_REQUEST_INTERVAL = 3;
    private static final int MAX_REQUEST_INTERVAL = 15;

    // -----------------------------------------------------------------------
    // Identyfikator szpitala (ustawiany z zewnatrz przez federat)
    private final int hospitalId;
    private final Random random;

    // Licznik ID zamowien
    private int nextRequestId = 1;

    // -----------------------------------------------------------------------
    // Statystyki szpitala
    // -----------------------------------------------------------------------

    // Laczna liczba wyslanych zamowien
    private int totalRequests = 0;

    // Liczba zamowien, na ktore nie dostarczono krwi (niedor po stronie szpitala)
    // Tu liczymy tez zamowienia oczekujace - uproszczenie: kazde unanswered po X czasie
    private int unmetRequests = 0;

    // Do liczenia sredniej: suma wiekow krwi [dni] i liczba dostarczonych jednostek
    private double totalBloodAgeDays = 0.0;
    private int    deliveredUnitsCount = 0;

    // Skladniki krwi po separacji (uproszczone liczniki)
    private int plasmaUnits     = 0;
    private int plateletUnits   = 0;
    private int redCellUnits    = 0;

    // -----------------------------------------------------------------------

    public Hospital(int hospitalId) {
        this.hospitalId = hospitalId;
        this.random = new Random();
        System.out.println("Hospital #" + hospitalId + ": Zainicjalizowano szpital.");
    }

    // -----------------------------------------------------------------------
    // Generowanie zapotrzebowania
    // -----------------------------------------------------------------------

    /** Losuje czas do kolejnego zapotrzebowania. */
    public int getTimeToNextRequest() {
        return random.nextInt(MAX_REQUEST_INTERVAL - MIN_REQUEST_INTERVAL + 1)
                + MIN_REQUEST_INTERVAL;
    }

    /** Czy biezace zapotrzebowanie jest nagle? */
    public boolean isUrgentRequest() {
        return random.nextDouble() < URGENT_PROBABILITY;
    }

    /** Losuje grupe krwi dla zapotrzebowania. */
    public String randomBloodType() {
        return BLOOD_TYPES[random.nextInt(BLOOD_TYPES.length)];
    }

    /**
     * Losuje zapotrzebowana ilosc: 1-3 jednostki.
     */
    public float randomRequestedAmount() {
        int units = random.nextInt(3) + 1;
        return units * UNIT_VOLUME;
    }

    /** Zwraca i inkrementuje ID kolejnego zamowienia. */
    public int nextRequestId() {
        totalRequests++;
        return nextRequestId++;
    }

    public int getHospitalId() { return hospitalId; }

    // -----------------------------------------------------------------------
    // Separacja krwi na skladniki
    // -----------------------------------------------------------------------

    /**
     * Losuje czas separacji krwi na skladniki.
     */
    public int getSeparationTime() {
        return random.nextInt(MAX_SEPARATION_TIME - MIN_SEPARATION_TIME + 1)
                + MIN_SEPARATION_TIME;
    }

    /**
     * Wykonuje separacje dostarczonej krwi pelnej na skladniki.
     * Kazda jednostka 0.45l daje:
     *   - 1 jednostke osocza (plasma)
     *   - 1 jednostke plytek krwi (platelets)
     *   - 1 jednostke krwinek czerwonych (red cells)
     *
     * @param bloodAmount  objetosc dostarczonej krwi [l]
     * @param currentTime  aktualny czas symulacyjny
     * @param donationTime czas pobrania krwi od dawcy
     */
    public void separateBlood(float bloodAmount, double currentTime, double donationTime) {
        int units = Math.round(bloodAmount / UNIT_VOLUME);
        plasmaUnits   += units;
        plateletUnits += units;
        redCellUnits  += units;

        // Aktualizuj statystyki wieku krwi
        double ageInDays = currentTime - donationTime;
        totalBloodAgeDays    += ageInDays;
        deliveredUnitsCount  += units;

        System.out.println("Hospital #" + hospitalId + " [SEPARACJA]: "
                + units + " jednostek | wiek krwi=" + String.format("%.1f", ageInDays) + " dni"
                + " | Skladniki -> osocze=" + plasmaUnits
                + " platki=" + plateletUnits
                + " krwinki=" + redCellUnits);
    }

    // -----------------------------------------------------------------------
    // Statystyki
    // -----------------------------------------------------------------------

    /** Rejestruje niezrealizowane zamowienie (niedobor po stronie szpitala). */
    public void registerUnmetRequest() {
        unmetRequests++;
    }

    /**
     * Wskaznik niedoboru = (niezrealizowane / wszystkie zamowienia) * 100%
     */
    public double getShortageRate() {
        if (totalRequests == 0) return 0.0;
        return (double) unmetRequests / totalRequests * 100.0;
    }

    /**
     * Srednia liczba dni od pobrania krwi do jej uzycia w szpitalu.
     */
    public double getAverageBloodAgeDays() {
        if (deliveredUnitsCount == 0) return 0.0;
        return totalBloodAgeDays / deliveredUnitsCount;
    }

    /** Drukuje podsumowanie statystyk szpitala. */
    public void printStats() {
        System.out.println("========================================");
        System.out.println("STATYSTYKI Szpital #" + hospitalId);
        System.out.println("  Laczne zamowienia:      " + totalRequests);
        System.out.println("  Niezrealizowane:        " + unmetRequests);
        System.out.println("  Wskaznik niedoboru:     "
                + String.format("%.1f", getShortageRate()) + "%");
        System.out.println("  Dostarczone jednostki:  " + deliveredUnitsCount);
        System.out.println("  Sredni wiek krwi:       "
                + String.format("%.2f", getAverageBloodAgeDays()) + " dni");
        System.out.println("  Skladniki - osocze:"    + plasmaUnits
                + "  platki:" + plateletUnits
                + "  krwinki:" + redCellUnits);
        System.out.println("========================================");
    }

    // Gettery dla federatu
    public int getTotalRequests()     { return totalRequests; }
    public int getUnmetRequests()     { return unmetRequests; }
    public int getDeliveredUnits()    { return deliveredUnitsCount; }
}