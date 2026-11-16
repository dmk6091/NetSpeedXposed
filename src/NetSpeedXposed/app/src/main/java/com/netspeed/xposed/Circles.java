package com.netspeed.xposed;

import java.util.HashMap;
import java.util.Map;

/**
 * Indian mobile network codes (MCC-MNC) to operator + licence circle. Only codes known with
 * confidence are listed; anything else is "unknown" rather than a guess.
 */
final class Circles {
    private Circles() {}

    /** {operator, circle short code, circle name} */
    private static final Map<String, String[]> M = new HashMap<>();

    private static void add(String op, String circle, String name, String... codes) {
        for (String c : codes) M.put(c, new String[]{op, circle, name});
    }

    static {
        // Jio (MCC 405)
        add("Jio", "WB", "West Bengal", "405840");
        add("Jio", "AP", "Andhra Pradesh", "405854");
        add("Jio", "AS", "Assam", "405855");
        add("Jio", "BR", "Bihar", "405856");
        add("Jio", "GJ", "Gujarat", "405857");
        add("Jio", "HR", "Haryana", "405858");
        add("Jio", "HP", "Himachal Pradesh", "405859");
        add("Jio", "JK", "Jammu & Kashmir", "405860");
        add("Jio", "KA", "Karnataka", "405861");
        add("Jio", "KL", "Kerala", "405862");
        add("Jio", "MP", "Madhya Pradesh", "405863");
        add("Jio", "MH", "Maharashtra", "405864");
        add("Jio", "NE", "North East", "405865");
        add("Jio", "OD", "Odisha", "405866");
        add("Jio", "PB", "Punjab", "405867");
        add("Jio", "RJ", "Rajasthan", "405868");
        add("Jio", "TN", "Tamil Nadu", "405869");
        add("Jio", "UW", "UP West", "405870");
        add("Jio", "UE", "UP East", "405871");
        add("Jio", "DL", "Delhi", "405872");
        add("Jio", "KO", "Kolkata", "405873");
        add("Jio", "MU", "Mumbai", "405874");
        // Airtel
        add("Airtel", "DL", "Delhi", "40410");
        add("Airtel", "KO", "Kolkata", "40431");
        add("Airtel", "CH", "Chennai", "40440");
        add("Airtel", "KA", "Karnataka", "40445");
        add("Airtel", "AP", "Andhra Pradesh", "40449");
        add("Airtel", "MH", "Maharashtra", "40490");
        add("Airtel", "MU", "Mumbai", "40492");
        add("Airtel", "MP", "Madhya Pradesh", "40493");
        add("Airtel", "TN", "Tamil Nadu", "40494");
        add("Airtel", "KL", "Kerala", "40495");
        add("Airtel", "HR", "Haryana", "40496");
        add("Airtel", "UW", "UP West", "40497");
        add("Airtel", "GJ", "Gujarat", "40498");
        add("Airtel", "PB", "Punjab", "40402");
        add("Airtel", "HP", "Himachal Pradesh", "40403");
        add("Airtel", "RJ", "Rajasthan", "40470");
        add("Airtel", "NE", "North East", "40416");
        // Vi (Vodafone Idea)
        add("Vi", "MU", "Mumbai", "40420");
        add("Vi", "MH", "Maharashtra", "40422", "40427");
        add("Vi", "KA", "Karnataka", "40486", "40444");
        add("Vi", "GJ", "Gujarat", "40405", "40424");
        add("Vi", "DL", "Delhi", "40411", "40404");
        add("Vi", "AP", "Andhra Pradesh", "40413", "40407");
        add("Vi", "TN", "Tamil Nadu", "40443");
        add("Vi", "KL", "Kerala", "40446", "40419");
        add("Vi", "HR", "Haryana", "40401", "40412");
        add("Vi", "UE", "UP East", "40415");
        add("Vi", "UW", "UP West", "40456");
        add("Vi", "KO", "Kolkata", "40430");
        add("Vi", "PB", "Punjab", "40414");
        add("Vi", "MP", "Madhya Pradesh", "40478");
        // BSNL
        add("BSNL", "HR", "Haryana", "40434");
        add("BSNL", "AS", "Assam", "40438");
        add("BSNL", "HP", "Himachal Pradesh", "40451");
        add("BSNL", "PB", "Punjab", "40453");
        add("BSNL", "UE", "UP East", "40455");
        add("BSNL", "GJ", "Gujarat", "40457");
        add("BSNL", "MP", "Madhya Pradesh", "40458");
        add("BSNL", "RJ", "Rajasthan", "40459");
        add("BSNL", "JK", "Jammu & Kashmir", "40462");
        add("BSNL", "CH", "Chennai", "40464");
        add("BSNL", "MH", "Maharashtra", "40466");
        add("BSNL", "KA", "Karnataka", "40471");
        add("BSNL", "KL", "Kerala", "40472");
        add("BSNL", "AP", "Andhra Pradesh", "40473");
        add("BSNL", "WB", "West Bengal", "40474");
        add("BSNL", "BR", "Bihar", "40475");
        add("BSNL", "OD", "Odisha", "40476");
        add("BSNL", "NE", "North East", "40477");
        add("BSNL", "AN", "Andaman & Nicobar", "40479");
        add("BSNL", "TN", "Tamil Nadu", "40480");
        add("BSNL", "KO", "Kolkata", "40481");
        // MTNL
        add("MTNL", "DL", "Delhi", "40468");
        add("MTNL", "MU", "Mumbai", "40469");
    }

    /** Vi network codes by the company that built them before the 2020 merger. */
    private static final java.util.Set<String> VODAFONE = new java.util.HashSet<>(java.util.Arrays.asList(
            "40420", "40427", "40405", "40411", "40413", "40443", "40446", "40401", "40415", "40430", "40486"));
    private static final java.util.Set<String> IDEA = new java.util.HashSet<>(java.util.Arrays.asList(
            "40422", "40424", "40404", "40407", "40419", "40412", "40414", "40444", "40456", "40478"));

    /** "Vodafone" / "Idea" for a Vi network code, else null. */
    static String legacy(String numeric) {
        if (numeric == null) return null;
        String n = numeric.replaceAll("[^0-9]", "");
        if (n.length() == 6 && n.charAt(3) == '0') n = n.substring(0, 3) + n.substring(4);
        return VODAFONE.contains(n) ? "Vodafone" : IDEA.contains(n) ? "Idea" : null;
    }

    /** {operator, circle code, circle name} for "40469" / "404-69" / "404069", or null. */
    static String[] of(String numeric) {
        if (numeric == null) return null;
        String n = numeric.replaceAll("[^0-9]", "");
        String[] r = M.get(n);
        if (r == null && n.length() == 6 && n.charAt(3) == '0') r = M.get(n.substring(0, 3) + n.substring(4));
        return r;
    }

    /** Network circle when it differs from the SIM's home circle (roaming), else null. */
    static String roamCircle(String simNumeric, String netNumeric) {
        String[] s = of(simNumeric), n = of(netNumeric);
        if (s == null || n == null) return null;
        return s[1].equals(n[1]) ? null : n[1];
    }
}
