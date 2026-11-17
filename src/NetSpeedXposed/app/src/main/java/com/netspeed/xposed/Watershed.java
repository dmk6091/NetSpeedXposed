package com.netspeed.xposed;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Where the river beside you ends up: "Pavana → Mula-Mutha → Bhima → Krishna → Bay of Bengal".
 * A hand-made table of major rivers (the river is named from OpenStreetMap by RiverFlow); unknown rivers give nothing.
 */
final class Watershed {
    private Watershed() {}

    private static final String BOB = "Bay of Bengal", ARB = "Arabian Sea";
    /** river -> {next rivers..., sea}; the last element is the sea. */
    private static final Map<String, String[]> M = new HashMap<>();

    private static void put(String sea, String river, String... via) {
        String[] a = new String[via.length + 1];
        System.arraycopy(via, 0, a, 0, via.length);
        a[via.length] = sea;
        M.put(river.toLowerCase(Locale.ROOT), a);
    }

    static {
        // Krishna basin (to the Bay of Bengal)
        put(BOB, "Krishna");
        put(BOB, "Bhima", "Krishna");
        put(BOB, "Pavana", "Mula-Mutha", "Bhima", "Krishna");
        put(BOB, "Mula", "Mula-Mutha", "Bhima", "Krishna");
        put(BOB, "Mutha", "Mula-Mutha", "Bhima", "Krishna");
        put(BOB, "Mula-Mutha", "Bhima", "Krishna");
        put(BOB, "Indrayani", "Bhima", "Krishna");
        put(BOB, "Ghod", "Bhima", "Krishna");
        put(BOB, "Bhama", "Bhima", "Krishna");
        put(BOB, "Nira", "Bhima", "Krishna");
        put(BOB, "Sina", "Bhima", "Krishna");
        put(BOB, "Man", "Bhima", "Krishna");
        put(BOB, "Koyna", "Krishna");
        put(BOB, "Warna", "Krishna");
        put(BOB, "Panchganga", "Krishna");
        put(BOB, "Ghataprabha", "Krishna");
        put(BOB, "Malaprabha", "Krishna");
        put(BOB, "Tungabhadra", "Krishna");
        put(BOB, "Vedganga", "Krishna");
        // Godavari basin
        put(BOB, "Godavari");
        put(BOB, "Pravara", "Godavari");
        put(BOB, "Mula (Pravara)", "Pravara", "Godavari");
        put(BOB, "Manjara", "Godavari");
        put(BOB, "Purna", "Godavari");
        put(BOB, "Wainganga", "Godavari");
        put(BOB, "Wardha", "Godavari");
        put(BOB, "Penganga", "Godavari");
        put(BOB, "Indravati", "Godavari");
        // other east-flowing
        put(BOB, "Kaveri"); put(BOB, "Cauvery");
        put(BOB, "Mahanadi"); put(BOB, "Penna"); put(BOB, "Pennar");
        put(BOB, "Ganga", "Ganga"); M.put("ganga", new String[]{BOB});
        put(BOB, "Ganges"); put(BOB, "Brahmaputra");
        put(BOB, "Yamuna", "Ganga"); put(BOB, "Gomti", "Ganga"); put(BOB, "Chambal", "Yamuna", "Ganga");
        put(BOB, "Son", "Ganga"); put(BOB, "Kosi", "Ganga"); put(BOB, "Gandak", "Ganga");
        // west-flowing (to the Arabian Sea)
        put(ARB, "Tapi"); put(ARB, "Tapti"); put(ARB, "Purna (Tapi)", "Tapi");
        put(ARB, "Narmada"); put(ARB, "Mahi"); put(ARB, "Sabarmati");
        put(ARB, "Ulhas"); put(ARB, "Vaitarna"); put(ARB, "Kundalika"); put(ARB, "Savitri");
        put(ARB, "Vashishti"); put(ARB, "Shastri"); put(ARB, "Mithi"); put(ARB, "Amba");
        put(ARB, "Mandovi"); put(ARB, "Zuari"); put(ARB, "Kali"); put(ARB, "Sharavati");
        put(ARB, "Periyar"); put(ARB, "Bharathapuzha"); put(ARB, "Indus");
        put(ARB, "Sutlej", "Indus"); put(ARB, "Chenab", "Indus"); put(ARB, "Jhelum", "Chenab", "Indus"); put(ARB, "Ravi", "Chenab", "Indus"); put(ARB, "Beas", "Sutlej", "Indus");
        // Marathi spellings seen on OpenStreetMap
        alias("पवना", "pavana"); alias("मुळा", "mula"); alias("मुठा", "mutha"); alias("मुळा-मुठा", "mula-mutha");
        alias("इंद्रायणी", "indrayani"); alias("भीमा", "bhima"); alias("कृष्णा", "krishna"); alias("गोदावरी", "godavari");
        alias("नीरा", "nira"); alias("कोयना", "koyna"); alias("पंचगंगा", "panchganga");
    }

    private static void alias(String a, String of) { M.put(a.toLowerCase(Locale.ROOT), M.get(of)); }

    private static String clean(String n) {
        return n.replaceAll("\\(.*?\\)", "").replaceAll("~\\d+m", "").replaceAll("(?i)^(river|canal|stream|nadi|nala)\\s+|\\s+(river|nadi)$", "").trim();
    }

    /** The river's name out of RiverFlow's text ("River Pavana (320m) 1.6 m³/s ▼"), or "". */
    static String riverName(String rv) {
        if (rv == null || !rv.startsWith("River")) return "";
        String s = rv.replaceAll("\\s+[0-9.]+k?\\s*m³/s.*$", "");
        return clean(s);
    }

    /** {river, then the rivers it joins in order, then the sea}, with names as RiverInfo knows them; null when the river is not in the table. */
    static String[] path(String river) {
        String nm = clean(river);
        String[] via = M.get(nm.toLowerCase(Locale.ROOT));
        if (via == null) return null;
        String ascii = nm;
        for (Map.Entry<String, String[]> e : M.entrySet()) if (e.getValue() == via && e.getKey().matches("[a-z\\-() ]+")) { ascii = e.getKey(); break; }
        String head = Character.toUpperCase(ascii.charAt(0)) + ascii.substring(1);
        if (head.equals("Mula-mutha")) head = "Mula-Mutha";
        String[] out = new String[via.length + 1];
        out[0] = head;
        System.arraycopy(via, 0, out, 1, via.length);
        return out;
    }

    /** Chain texts from the longest to the shortest ("Pavana → Mula-Mutha → Bhima → Krishna → Bay of Bengal", then with fewer middle names), or an empty list. */
    static List<String> chains(String river) {
        List<String> out = new ArrayList<>();
        String nm = clean(river);
        String[] via = M.get(nm.toLowerCase(Locale.ROOT));
        if (via == null) return out;
        String ascii = nm;
        for (Map.Entry<String, String[]> e : M.entrySet()) if (e.getValue() == via && e.getKey().matches("[a-z\\-() ]+")) { ascii = e.getKey(); break; }
        String head = Character.toUpperCase(ascii.charAt(0)) + ascii.substring(1);
        if (head.equals("Mula-mutha")) head = "Mula-Mutha";
        String sea = via[via.length - 1];
        // full, then dropping middle rivers one at a time, then just river + sea
        for (int keep = via.length - 1; keep >= 0; keep--) {
            StringBuilder sb = new StringBuilder("💧 ").append(head);
            for (int i = 0; i < via.length - 1; i++) {
                // keep the last 'keep' rivers (closest to the sea)
                if (i >= via.length - 1 - keep) sb.append(" → ").append(via[i]);
            }
            sb.append(" → ").append(sea);
            out.add(sb.toString());
        }
        return out;
    }
}
