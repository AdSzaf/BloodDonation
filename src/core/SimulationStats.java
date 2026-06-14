package core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class SimulationStats {

    private static final List<HospitalSnapshot> hospitalSnapshots = new ArrayList<>();
    private static final List<TransportSnapshot> transportSnapshots = new ArrayList<>();
    private static final Map<Integer, HospitalFinal> hospitalFinals = new LinkedHashMap<>();
    private static int collectedUnits = 0;
    private static int mobileCollectedUnits = 0;
    private static int stationaryCollectedUnits = 0;
    private static int deliveredUnits = 0;

    private SimulationStats() {}

    public static synchronized void recordDonation(boolean isMobile) {
        collectedUnits++;
        if (isMobile) {
            mobileCollectedUnits++;
        } else {
            stationaryCollectedUnits++;
        }
    }

    public static synchronized void recordDelivery() {
        deliveredUnits++;
    }

    public static synchronized void recordHospitalSnapshot(double time, int hospitalId,
                                                           int totalRequests, int unmetRequests,
                                                           int deliveredUnits, int pendingRequests,
                                                           double shortageRate, double averageBloodAgeDays) {
        hospitalSnapshots.add(new HospitalSnapshot(time, hospitalId, totalRequests, unmetRequests,
                deliveredUnits, pendingRequests, shortageRate, averageBloodAgeDays));
    }

    public static synchronized void recordHospitalFinal(int hospitalId, int totalRequests,
                                                        int unmetRequests, int deliveredUnits,
                                                        int pendingRequests, double shortageRate,
                                                        double averageBloodAgeDays, int plasmaUnits,
                                                        int plateletUnits, int redCellUnits) {
        hospitalFinals.put(hospitalId, new HospitalFinal(hospitalId, totalRequests, unmetRequests,
                deliveredUnits, pendingRequests, shortageRate, averageBloodAgeDays,
                plasmaUnits, plateletUnits, redCellUnits));
    }

    public static synchronized void recordTransportSnapshot(double time, int storageUnits,
                                                            int shortageCount, String storageSummary) {
        transportSnapshots.add(new TransportSnapshot(time, storageUnits, shortageCount, storageSummary));
    }

    public static synchronized void writeReport(double endTime) {
        try {
            Files.createDirectories(Paths.get("logs"));
            Path path = Paths.get("logs", "simulation-report.html");
            Files.write(path, buildHtml(endTime).getBytes(StandardCharsets.UTF_8));
            printConsoleSummary(endTime);
        } catch (IOException e) {
            System.out.println("[SimulationStats] Nie udalo sie zapisac raportu: " + e.getMessage());
        }
    }

    private static void printConsoleSummary(double endTime) {
        System.out.println();
        System.out.println("=====================================================");
        System.out.println("PODSUMOWANIE SYMULACJI t=0.." + fmt(endTime));
        System.out.println("Pobrane jednostki: " + collectedUnits
                + " (stacjonarne=" + stationaryCollectedUnits
                + ", mobilne=" + mobileCollectedUnits + ")");
        System.out.println("Wydane jednostki do szpitali: " + deliveredUnits);
        TransportSnapshot lastTransport = lastTransportSnapshot();
        if (lastTransport != null) {
            System.out.println("Magazyn koncowy: " + lastTransport.storageUnits
                    + " szt. | Niedobory magazynu: " + lastTransport.shortageCount);
        }
        for (HospitalFinal h : hospitalFinals.values()) {
            System.out.println("Szpital #" + h.hospitalId
                    + ": zamowienia=" + h.totalRequests
                    + ", niedobor=" + fmt(h.shortageRate) + "%"
                    + ", sredni wiek krwi=" + fmt(h.averageBloodAgeDays) + " dni"
                    + ", dostarczone=" + h.deliveredUnits);
        }
        System.out.println("Raport z wykresami: logs/simulation-report.html");
        System.out.println("=====================================================");
    }

    private static String buildHtml(double endTime) {
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html lang=\"pl\"><head><meta charset=\"utf-8\">")
                .append("<title>BloodDonation Simulation Report</title>")
                .append("<style>")
                .append("body{font-family:Arial,sans-serif;margin:24px;background:#f7f7f5;color:#222}")
                .append("h1,h2{margin:0 0 12px}section{margin:22px 0;padding:18px;background:#fff;border:1px solid #ddd;border-radius:6px}")
                .append(".grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:12px}")
                .append(".metric{border:1px solid #ddd;border-radius:6px;padding:12px;background:#fafafa}.metric b{font-size:24px;display:block}")
                .append("table{border-collapse:collapse;width:100%;font-size:14px}th,td{border-bottom:1px solid #ddd;padding:8px;text-align:left}")
                .append("svg{width:100%;height:auto;border:1px solid #ddd;background:#fff}.axis{stroke:#888;stroke-width:1}.line{fill:none;stroke-width:2.5}.muted{color:#666}")
                .append("</style></head><body>");

        html.append("<h1>BloodDonation Simulation Report</h1>")
                .append("<p class=\"muted\">Zakres czasu symulacji: t=0..").append(fmt(endTime)).append("</p>");

        TransportSnapshot lastTransport = lastTransportSnapshot();
        html.append("<section><h2>Najwazniejsze statystyki</h2><div class=\"grid\">")
                .append(metric("Pobrane jednostki", collectedUnits))
                .append(metric("Stacjonarne", stationaryCollectedUnits))
                .append(metric("Mobilne", mobileCollectedUnits))
                .append(metric("Wydane szpitalom", deliveredUnits));
        if (lastTransport != null) {
            html.append(metric("Magazyn koncowy", lastTransport.storageUnits))
                    .append(metric("Niedobory magazynu", lastTransport.shortageCount));
        }
        html.append("</div></section>");

        html.append("<section><h2>Szpitale</h2><table><tr><th>Szpital</th><th>Zamowienia</th><th>Niezrealizowane</th><th>Wskaznik niedoboru</th><th>Sredni wiek krwi</th><th>Dostarczone</th><th>Oczekujace</th><th>Skladniki</th></tr>");
        for (HospitalFinal h : hospitalFinals.values()) {
            html.append("<tr><td>#").append(h.hospitalId).append("</td><td>").append(h.totalRequests)
                    .append("</td><td>").append(h.unmetRequests)
                    .append("</td><td>").append(fmt(h.shortageRate)).append("%</td><td>")
                    .append(fmt(h.averageBloodAgeDays)).append(" dni</td><td>")
                    .append(h.deliveredUnits).append("</td><td>").append(h.pendingRequests)
                    .append("</td><td>osocze ").append(h.plasmaUnits)
                    .append(", plytki ").append(h.plateletUnits)
                    .append(", krwinki ").append(h.redCellUnits).append("</td></tr>");
        }
        html.append("</table></section>");

        html.append("<section><h2>Wskaznik niedoboru w czasie</h2>")
                .append(lineChart(hospitalShortageSeries(), endTime, 100.0, "%", new String[]{"#4e79a7", "#e15759", "#59a14f", "#f28e2b"}))
                .append("</section>");
        html.append("<section><h2>Sredni wiek krwi w czasie</h2>")
                .append(lineChart(hospitalAgeSeries(), endTime, maxHospitalAge(), "dni", new String[]{"#76b7b2", "#edc949", "#af7aa1", "#ff9da7"}))
                .append("</section>");
        html.append("<section><h2>Stan magazynu i niedobory transportu</h2>")
                .append(lineChart(transportSeries(), endTime, maxTransportValue(), "szt.", new String[]{"#4e79a7", "#e15759"}))
                .append("</section>");
        html.append("</body></html>");
        return html.toString();
    }

    private static String metric(String label, int value) {
        return "<div class=\"metric\"><b>" + value + "</b>" + label + "</div>";
    }

    private static Map<String, List<Point>> hospitalShortageSeries() {
        Map<String, List<Point>> series = new LinkedHashMap<>();
        for (HospitalSnapshot s : hospitalSnapshots) {
            series.computeIfAbsent("Szpital #" + s.hospitalId, k -> new ArrayList<>())
                    .add(new Point(s.time, s.shortageRate));
        }
        return series;
    }

    private static Map<String, List<Point>> hospitalAgeSeries() {
        Map<String, List<Point>> series = new LinkedHashMap<>();
        for (HospitalSnapshot s : hospitalSnapshots) {
            series.computeIfAbsent("Szpital #" + s.hospitalId, k -> new ArrayList<>())
                    .add(new Point(s.time, s.averageBloodAgeDays));
        }
        return series;
    }

    private static Map<String, List<Point>> transportSeries() {
        Map<String, List<Point>> series = new LinkedHashMap<>();
        for (TransportSnapshot s : transportSnapshots) {
            series.computeIfAbsent("Magazyn", k -> new ArrayList<>()).add(new Point(s.time, s.storageUnits));
            series.computeIfAbsent("Niedobory", k -> new ArrayList<>()).add(new Point(s.time, s.shortageCount));
        }
        return series;
    }

    private static String lineChart(Map<String, List<Point>> series, double maxX, double maxY,
                                    String unit, String[] colors) {
        if (series.isEmpty()) return "<p class=\"muted\">Brak danych do wykresu.</p>";
        maxY = Math.max(1.0, maxY);
        int width = 900;
        int height = 320;
        int left = 54;
        int top = 24;
        int chartW = 790;
        int chartH = 240;
        StringBuilder svg = new StringBuilder();
        svg.append("<svg viewBox=\"0 0 ").append(width).append(' ').append(height).append("\">");
        svg.append("<line class=\"axis\" x1=\"").append(left).append("\" y1=\"").append(top + chartH)
                .append("\" x2=\"").append(left + chartW).append("\" y2=\"").append(top + chartH).append("\"/>");
        svg.append("<line class=\"axis\" x1=\"").append(left).append("\" y1=\"").append(top)
                .append("\" x2=\"").append(left).append("\" y2=\"").append(top + chartH).append("\"/>");
        svg.append("<text x=\"").append(left).append("\" y=\"").append(height - 18).append("\">t=0</text>");
        svg.append("<text x=\"").append(left + chartW - 52).append("\" y=\"").append(height - 18).append("\">t=")
                .append(fmt(maxX)).append("</text>");
        svg.append("<text x=\"8\" y=\"").append(top + 12).append("\">").append(fmt(maxY)).append(' ').append(unit).append("</text>");

        int i = 0;
        int legendY = 20;
        for (Map.Entry<String, List<Point>> entry : series.entrySet()) {
            String color = colors[i % colors.length];
            svg.append("<polyline class=\"line\" stroke=\"").append(color).append("\" points=\"");
            for (Point p : entry.getValue()) {
                double x = left + (Math.min(p.x, maxX) / maxX) * chartW;
                double y = top + chartH - (Math.min(p.y, maxY) / maxY) * chartH;
                svg.append(fmt(x)).append(',').append(fmt(y)).append(' ');
            }
            svg.append("\"/>");
            int legendX = left + 16 + (i * 155);
            svg.append("<rect x=\"").append(legendX).append("\" y=\"").append(legendY)
                    .append("\" width=\"12\" height=\"12\" fill=\"").append(color).append("\"/>");
            svg.append("<text x=\"").append(legendX + 18).append("\" y=\"").append(legendY + 11)
                    .append("\">").append(entry.getKey()).append("</text>");
            i++;
        }
        svg.append("</svg>");
        return svg.toString();
    }

    private static double maxHospitalAge() {
        double max = 1.0;
        for (HospitalSnapshot s : hospitalSnapshots) {
            max = Math.max(max, s.averageBloodAgeDays);
        }
        return Math.ceil(max + 1.0);
    }

    private static double maxTransportValue() {
        double max = 1.0;
        for (TransportSnapshot s : transportSnapshots) {
            max = Math.max(max, Math.max(s.storageUnits, s.shortageCount));
        }
        return Math.ceil(max + 1.0);
    }

    private static TransportSnapshot lastTransportSnapshot() {
        if (transportSnapshots.isEmpty()) return null;
        return transportSnapshots.get(transportSnapshots.size() - 1);
    }

    private static String fmt(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private static final class Point {
        final double x;
        final double y;

        Point(double x, double y) {
            this.x = x;
            this.y = y;
        }
    }

    private static final class HospitalSnapshot {
        final double time;
        final int hospitalId;
        final int totalRequests;
        final int unmetRequests;
        final int deliveredUnits;
        final int pendingRequests;
        final double shortageRate;
        final double averageBloodAgeDays;

        HospitalSnapshot(double time, int hospitalId, int totalRequests, int unmetRequests,
                         int deliveredUnits, int pendingRequests, double shortageRate,
                         double averageBloodAgeDays) {
            this.time = time;
            this.hospitalId = hospitalId;
            this.totalRequests = totalRequests;
            this.unmetRequests = unmetRequests;
            this.deliveredUnits = deliveredUnits;
            this.pendingRequests = pendingRequests;
            this.shortageRate = shortageRate;
            this.averageBloodAgeDays = averageBloodAgeDays;
        }
    }

    private static final class TransportSnapshot {
        final double time;
        final int storageUnits;
        final int shortageCount;
        final String storageSummary;

        TransportSnapshot(double time, int storageUnits, int shortageCount, String storageSummary) {
            this.time = time;
            this.storageUnits = storageUnits;
            this.shortageCount = shortageCount;
            this.storageSummary = storageSummary;
        }
    }

    private static final class HospitalFinal {
        final int hospitalId;
        final int totalRequests;
        final int unmetRequests;
        final int deliveredUnits;
        final int pendingRequests;
        final double shortageRate;
        final double averageBloodAgeDays;
        final int plasmaUnits;
        final int plateletUnits;
        final int redCellUnits;

        HospitalFinal(int hospitalId, int totalRequests, int unmetRequests, int deliveredUnits,
                      int pendingRequests, double shortageRate, double averageBloodAgeDays,
                      int plasmaUnits, int plateletUnits, int redCellUnits) {
            this.hospitalId = hospitalId;
            this.totalRequests = totalRequests;
            this.unmetRequests = unmetRequests;
            this.deliveredUnits = deliveredUnits;
            this.pendingRequests = pendingRequests;
            this.shortageRate = shortageRate;
            this.averageBloodAgeDays = averageBloodAgeDays;
            this.plasmaUnits = plasmaUnits;
            this.plateletUnits = plateletUnits;
            this.redCellUnits = redCellUnits;
        }
    }
}
