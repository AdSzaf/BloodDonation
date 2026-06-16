package BloodPoints;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Logika punktu poboru krwi.
 * Symuluje zglaszanie sie dawcow, badania laboratoryjne i pobranie.
 * Obsluguje tryb stacjonarny (natychmiastowe wyslanie BloodCollected)
 * oraz mobilny (buforowanie i wysylanie partia po zakonczeniu zmiany).
 */
public class BloodPoints {

    // Grupy krwi zgodnie z FOM
    public static final String[] BLOOD_TYPES = {
            "A_PLUS", "A_MINUS", "B_PLUS", "B_MINUS",
            "AB_PLUS", "AB_MINUS", "0_PLUS", "0_MINUS"
    };

    // Standardowa objetosc jednej jednostki krwi pelnej [l]
    public static final float UNIT_VOLUME = 0.45f;

    // Prawdopodobienstwo ze punkt jest mobilny (krwiobus)
    private static final double MOBILE_PROBABILITY = 0.3;

    // Czas trwania jednej zmiany krwiobusu (w jednostkach symulacyjnych)
    private static final int MOBILE_SHIFT_DURATION = 30;

    private final boolean isMobile;
    private final Random random;

    // Bufor krwiobusu - trojki (bloodId, bloodType, donationTime)
    private final List<Object[]> mobileBuffer = new ArrayList<>();

    // Dla krwiobusu: licznik czasu do konca zmiany
    private int shiftTimeRemaining;
    // Globalny licznik ID donacji
    private int nextBloodId = 1;

    public BloodPoints() {
        this.random = new Random();
        this.isMobile = random.nextDouble() < MOBILE_PROBABILITY;
        this.shiftTimeRemaining = MOBILE_SHIFT_DURATION;
        System.out.println("BloodPoints: Zainicjalizowano punkt "
                + (isMobile ? "MOBILNY (krwiobus)" : "STACJONARNY"));
    }

    public boolean isMobile() {
        return isMobile;
    }

    /**
     * Losuje czas przyjscia kolejnego dawcy (1-8 jednostek symulacyjnych).
     */
    public int getTimeToNextDonor() {
        return random.nextInt(8) + 1;
    }

    /**
     * Losuje czas badan laboratoryjnych i pobrania (2-6 jednostek).
     */
    public int getLabTestDuration() {
        return random.nextInt(5) + 2;
    }

    /**
     * Sprawdza czy dawca przeszedl kwalifikacje (80% szansa sukcesu).
     */
    public boolean donorQualifies() {
        return random.nextInt(100) < 80;
    }

    /**
     * Losuje grupe krwi dawcy.
     */
    public String randomBloodType() {
        return BLOOD_TYPES[random.nextInt(BLOOD_TYPES.length)];
    }

    /**
     * Generuje kolejne unikalne ID donacji.
     */
    public int nextBloodId() {
        return nextBloodId++;
    }

    // -----------------------------------------------------------------------
    // Logika mobilna (krwiobus)
    // -----------------------------------------------------------------------

    /**
     * Dla trybu mobilnego: dodaje donacje do lokalnego bufora krwiobusu.
     * Przechowuje bloodId, bloodType ORAZ donationTime (czas rzeczywistego pobrania).
     *
     * @param bloodId      ID donacji
     * @param bloodType    grupa krwi
     * @param donationTime czas pobrania (symulacyjny)
     */
    public void addToMobileBuffer(int bloodId, String bloodType, double donationTime) {
        mobileBuffer.add(new Object[]{bloodId, bloodType, donationTime});
        System.out.println("BloodPoints [MOBILNY]: Dodano do bufora donacje #" + bloodId
                + " (" + bloodType + ") t=" + donationTime
                + ". W buforze: " + mobileBuffer.size() + " szt.");
    }

    /**
     * Sprawdza czy zmiana krwiobusu dobiegla konca.
     * Odejmuje uplyniety czas od pozostalego czasu zmiany.
     */
    public boolean isShiftOver(int elapsedTime) {
        shiftTimeRemaining -= elapsedTime;
        if (shiftTimeRemaining <= 0) {
            shiftTimeRemaining = MOBILE_SHIFT_DURATION; // reset na nastepna zmiane
            return true;
        }
        return false;
    }

    /**
     * Zwraca bufor krwiobusu i go czysci (wywolywane po zakonczeniu zmiany).
     * Kazdy element to Object[]{ bloodId (Integer), bloodType (String), donationTime (Double) }.
     */
    public List<Object[]> flushMobileBuffer() {
        List<Object[]> copy = new ArrayList<>(mobileBuffer);
        mobileBuffer.clear();
        System.out.println("BloodPoints [MOBILNY]: Wyslano partie " + copy.size() + " donacji.");
        return copy;
    }

    public int getMobileBufferSize() {
        return mobileBuffer.size();
    }
}