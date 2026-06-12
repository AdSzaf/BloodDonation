package Transport;

import java.util.*;

/**
 * Singleton reprezentujacy Centrum Logistyczno-Magazynowe RCKiK.
 *
 * Przechowuje krew w mapie: grupa_krwi -> kolejka FIFO (najstarsza z przodu).
 * Realizuje:
 *  - addBlood()        : przyjecie jednostki od punktu poboru
 *  - removeExpired()   : utylizacja przeterminowanych jednostek
 *  - fulfillRequest()  : wydanie najstarszej krwi danego typu (FIFO)
 */
public class Transport {

    // Termin waznosci krwi pelnej w jednostkach symulacyjnych
    // Przyjmujemy 1 j.s. = 1 dzien, krew wazna 42 dni
    public static final double EXPIRY_DAYS = 42.0;

    // Reprezentacja pojedynczej jednostki krwi w magazynie
    public static class BloodEntry {
        public final int    bloodId;
        public final float  bloodAmount;
        public final String bloodType;
        public final double donationTime;

        public BloodEntry(int bloodId, float bloodAmount,
                          String bloodType, double donationTime) {
            this.bloodId      = bloodId;
            this.bloodAmount  = bloodAmount;
            this.bloodType    = bloodType;
            this.donationTime = donationTime;
        }

        public boolean isExpired(double currentTime) {
            return (currentTime - donationTime) > EXPIRY_DAYS;
        }

        @Override
        public String toString() {
            return "BloodEntry{id=" + bloodId
                    + ", type=" + bloodType
                    + ", amount=" + bloodAmount
                    + ", donationTime=" + donationTime + "}";
        }
    }

    // -----------------------------------------------------------------------
    // Singleton
    // -----------------------------------------------------------------------
    private static Transport instance = null;

    private Transport() {}

    public static Transport getInstance() {
        if (instance == null) instance = new Transport();
        return instance;
    }

    // -----------------------------------------------------------------------
    // Stan magazynu
    // -----------------------------------------------------------------------

    // Mapa: grupa krwi -> kolejka FIFO (przod = najstarsza jednostka)
    private final Map<String, Deque<BloodEntry>> bloodStorage = new HashMap<>();

    // Laczna liczba zarejestrowanych niedoborow
    private int shortageCount = 0;

    // -----------------------------------------------------------------------
    // Operacje na magazynie
    // -----------------------------------------------------------------------

    /**
     * Przyjmuje jednostke krwi do magazynu.
     * Wywolywane po odebraniu interakcji BloodCollected.
     */
    public void addBlood(int bloodId, float bloodAmount,
                         String bloodType, double donationTime) {
        BloodEntry entry = new BloodEntry(bloodId, bloodAmount, bloodType, donationTime);
        bloodStorage
                .computeIfAbsent(bloodType, k -> new ArrayDeque<>())
                .addLast(entry);
        System.out.println("Transport [MAGAZYN]: Przyjeto " + entry
                + " | Stan " + bloodType + ": "
                + bloodStorage.get(bloodType).size() + " szt.");
    }

    /**
     * Usuwa przeterminowane jednostki ze wszystkich kolejek.
     * Wywolywane co krok symulacyjny.
     * @return liczba usuniętych jednostek
     */
    public int removeExpired(double currentTime) {
        int removed = 0;
        for (Map.Entry<String, Deque<BloodEntry>> entry : bloodStorage.entrySet()) {
            Deque<BloodEntry> queue = entry.getValue();
            // FIFO: najstarsza z przodu - usuwamy z przodu az znajdziemy swiezą
            while (!queue.isEmpty() && queue.peekFirst().isExpired(currentTime)) {
                BloodEntry expired = queue.pollFirst();
                System.out.println("Transport [UTYLIZACJA]: Usunieto przeterminowana "
                        + expired + " (wiek=" + (currentTime - expired.donationTime) + " dni)");
                removed++;
            }
        }
        return removed;
    }

    /**
     * Probuje zrealizowac zapotrzebowanie szpitala na krew danego typu.
     * Stosuje zasade FIFO - wydaje najstarsza pasujaca jednostke.
     *
     * @return BloodEntry jesli krew dostepna, null jesli niedobor
     */
    public BloodEntry fulfillRequest(String bloodType, float requestedAmount) {
        Deque<BloodEntry> queue = bloodStorage.get(bloodType);
        if (queue == null || queue.isEmpty()) {
            shortageCount++;
            System.out.println("Transport [NIEDOR]: Brak krwi typu " + bloodType
                    + " | Laczne niedobory: " + shortageCount);
            return null;
        }
        // FIFO: pobierz najstarsza (z przodu)
        BloodEntry unit = queue.pollFirst();
        if (queue.isEmpty()) bloodStorage.remove(bloodType);

        System.out.println("Transport [WYDANO]: " + unit
                + " | Pozostalo " + bloodType + ": "
                + bloodStorage.getOrDefault(bloodType, new ArrayDeque<>()).size() + " szt.");
        return unit;
    }

    // -----------------------------------------------------------------------
    // Statystyki
    // -----------------------------------------------------------------------

    public int getShortageCount() {
        return shortageCount;
    }

    public int getTotalUnits() {
        return bloodStorage.values().stream().mapToInt(Deque::size).sum();
    }

    public int getUnitsOfType(String bloodType) {
        Deque<BloodEntry> q = bloodStorage.get(bloodType);
        return (q == null) ? 0 : q.size();
    }

    /** Zwraca czytelny stan magazynu dla kazdej grupy krwi. */
    public String getStorageSummary() {
        if (bloodStorage.isEmpty()) return "PUSTY";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Deque<BloodEntry>> e : bloodStorage.entrySet()) {
            if (!e.getValue().isEmpty())
                sb.append(e.getKey()).append(":").append(e.getValue().size()).append(" ");
        }
        return sb.toString().trim();
    }
}