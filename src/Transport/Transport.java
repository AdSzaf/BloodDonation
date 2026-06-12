package Transport;

import java.util.*;

/**
 * Singleton - Centrum Logistyczno-Magazynowe RCKiK.
 *
 * Zmiany v3:
 *  - fulfillRequest() wydaje tyle jednostek ile wynika z requestedAmount (nie tylko 1)
 *  - fulfillRequest() przyjmuje flage isUrgent - nagle zamowienia obslugiwane priorytetowo:
 *    jesli brakuje dokladnego typu, szpital moze dostac krew 0_MINUS (uniwersalny dawca)
 */
public class Transport {

    public static final double EXPIRY_DAYS = 42.0;

    // Uniwersalna grupa krwi - stosowana jako fallback dla naglych zamowien
    public static final String UNIVERSAL_TYPE = "0_MINUS";

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

    // Mapa: grupa krwi -> kolejka FIFO (przod = najstarsza jednostka)
    private final Map<String, Deque<BloodEntry>> bloodStorage = new HashMap<>();

    // Laczna liczba zarejestrowanych niedoborow (po stronie magazynu)
    private int shortageCount = 0;

    // -----------------------------------------------------------------------
    // Operacje na magazynie
    // -----------------------------------------------------------------------

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

    public int removeExpired(double currentTime) {
        int removed = 0;
        for (Map.Entry<String, Deque<BloodEntry>> entry : bloodStorage.entrySet()) {
            Deque<BloodEntry> queue = entry.getValue();
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
     * Realizuje zapotrzebowanie - zwraca liste wydanych jednostek (moze byc wiele).
     *
     * Logika:
     *  1. Oblicz ile jednostek (0.45l) potrzeba z requestedAmount.
     *  2. Pobierz tyle jednostek FIFO z kolejki danego typu.
     *  3. Jesli zamowienie NAGLE i brakuje jednostek zadanego typu:
     *     - uzyj krwi 0_MINUS jako uniwersalnego zastepstwa.
     *  4. Jesli nadal brak - rejestruj niedobor i zwroc co udalo sie zebrać
     *     (moze byc czesciowa realizacja).
     *
     * @return lista wydanych jednostek (pusta jesli kompletny niedobor)
     */
    public List<BloodEntry> fulfillRequest(String bloodType, float requestedAmount,
                                           boolean isUrgent) {
        int unitsNeeded = Math.max(1, Math.round(requestedAmount / 0.45f));
        List<BloodEntry> result = new ArrayList<>();

        // Pobierz ile mozna z zadanego typu
        result.addAll(pollUnits(bloodType, unitsNeeded));

        // Jesli nagle zamowienie i nie zebralismy wszystkiego - uzupelnij 0_MINUS
        int stillNeeded = unitsNeeded - result.size();
        if (stillNeeded > 0 && isUrgent && !bloodType.equals(UNIVERSAL_TYPE)) {
            List<BloodEntry> fallback = pollUnits(UNIVERSAL_TYPE, stillNeeded);
            if (!fallback.isEmpty()) {
                System.out.println("Transport [PILNE-FALLBACK]: Brakuje " + stillNeeded
                        + " szt. " + bloodType + " -> uzupelniam " + fallback.size()
                        + " szt. " + UNIVERSAL_TYPE);
                result.addAll(fallback);
            }
        }

        // Policz niedobory (brakujace jednostki)
        int shortage = unitsNeeded - result.size();
        if (shortage > 0) {
            shortageCount += shortage;
            System.out.println("Transport [NIEDOR]: Brakuje " + shortage
                    + " szt. typu " + bloodType
                    + (isUrgent ? " (NAGLE)" : " (planowe)")
                    + " | Laczne niedobory: " + shortageCount);
        }

        if (!result.isEmpty()) {
            System.out.println("Transport [WYDANO]: " + result.size()
                    + " szt. dla zamowienia " + bloodType
                    + " | Magazyn: " + getTotalUnits() + " szt.");
        }

        return result;
    }

    /**
     * Pomocnicza - pobiera do maxUnits jednostek z kolejki danego typu (FIFO).
     */
    private List<BloodEntry> pollUnits(String bloodType, int maxUnits) {
        List<BloodEntry> taken = new ArrayList<>();
        Deque<BloodEntry> queue = bloodStorage.get(bloodType);
        if (queue == null) return taken;
        while (!queue.isEmpty() && taken.size() < maxUnits) {
            taken.add(queue.pollFirst());
        }
        if (queue.isEmpty()) bloodStorage.remove(bloodType);
        return taken;
    }

    // -----------------------------------------------------------------------
    // Statystyki
    // -----------------------------------------------------------------------

    public int getShortageCount() { return shortageCount; }

    public int getTotalUnits() {
        return bloodStorage.values().stream().mapToInt(Deque::size).sum();
    }

    public int getUnitsOfType(String bloodType) {
        Deque<BloodEntry> q = bloodStorage.get(bloodType);
        return (q == null) ? 0 : q.size();
    }

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