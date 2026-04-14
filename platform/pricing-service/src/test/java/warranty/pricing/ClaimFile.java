package warranty.pricing;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import warranty.pricing.domain.ClaimInput;
import warranty.pricing.domain.Terms;

/** Reads the synthetic claims, the reference values and the results the PL/SQL produced for them. */
final class ClaimFile {
    private ClaimFile() {}

    record Row(String claimNo, ClaimInput input) {}

    record Golden(BigDecimal pct, BigDecimal labor, BigDecimal parts, BigDecimal travel, BigDecimal total) {}

    private static final Map<String, String> RATES = Map.of("D101", "118.00", "D102", "124.50", "D201", "109.75",
            "D202", "112.35", "D301", "139.95", "D302", "131.15");
    private static final Map<String, String> CAPS = Map.of("HYD-PUMP", "4.25", "HYD-HOSE", "1.50", "ENG-HEAD", "11.75",
            "ENG-INJ", "3.25", "TRN-CLUT", "8.00", "ELE-HARN", "5.75", "ELE-CTRL", "2.00", "CAB-HVAC", "3.75");
    private static final Map<String, int[]> TERMS = Map.of("T40S", new int[] {24, 36, 2000, 0},
            "T40E", new int[] {36, 60, 4000, 100}, "T40G", new int[] {24, 72, 6000, 0},
            "T90S", new int[] {24, 42, 2500, 0}, "T90E", new int[] {48, 71, 5000, 150},
            "T90G", new int[] {24, 84, 8000, 0}, "H250S", new int[] {12, 24, 1200, 0},
            "H250E", new int[] {24, 47, 3000, 250}, "H250G", new int[] {12, 60, 5000, 75});

    static Terms terms(ClaimInput c) {
        int[] t = TERMS.get(c.modelCode() + c.claimType());
        return new Terms(new BigDecimal(RATES.get(c.dealerCode())), new BigDecimal(CAPS.get(c.repairCode())), t[0], t[1],
                BigDecimal.valueOf(t[2]), BigDecimal.valueOf(t[3]));
    }

    static List<Row> claims() {
        return read("/claims.csv").stream().map(ClaimFile::row).toList();
    }

    static Map<String, Golden> golden() {
        Map<String, Golden> out = new LinkedHashMap<>();
        for (String[] f : read("/legacy-results.csv")) {
            out.put(f[0], new Golden(num(f[1]), num(f[2]), num(f[3]), num(f[4]), num(f[5])));
        }
        return out;
    }

    private static Row row(String[] f) {
        List<ClaimInput.PartLine> lines = f[10].isEmpty() ? List.of() : Arrays.stream(f[10].split(";"))
                .map(l -> l.split(":")).map(l -> new ClaimInput.PartLine(Integer.parseInt(l[0]), num(l[1]))).toList();
        return new Row(f[0], new ClaimInput(f[1], f[2], f[3], LocalDate.parse(f[4]), LocalDate.parse(f[5]), num(f[6]),
                f[7], num(f[8]), num(f[9]), lines));
    }

    private static BigDecimal num(String s) {
        return s.isEmpty() ? null : new BigDecimal(s);
    }

    private static List<String[]> read(String name) {
        try (var in = ClaimFile.class.getResourceAsStream(name);
             var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            return reader.lines().map(l -> l.split(",", -1)).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
