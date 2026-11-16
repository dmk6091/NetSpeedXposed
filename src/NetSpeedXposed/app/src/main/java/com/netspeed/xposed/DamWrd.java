package com.netspeed.xposed;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Maharashtra's 138 major dams, from the state Water Resources Department's daily "Today's Storage Report" (a PDF at
 * mwrdpravah.in, public, no login). The PDF is encrypted with the standard empty user password and holds plain
 * Times text, so this reads it directly: RC4 decrypt, inflate, collect the text with its page positions, rebuild rows.
 * Dam positions are a fixed table (grade C = approximate). Used by {@link DamMh#find}.
 */
final class DamWrd {
    private DamWrd() {}

    static final String URL = "https://mwrdpravah.in/damsafety/control/pdfLatestReportEng";

    /** name|district|lat|lon|grade (A/B exact enough, C approximate) */
    static final String[] TABLE = {
        "Bawanthadi|Bhandara|21.5431|79.5328|B",
        "Gosikhurd|Bhandara|20.8738|79.6082|B",
        "Asolamendha|Chandrapur|20.242|79.822|B",
        "Dina|Gadchiroli|19.7542|80.1282|B",
        "Dhapewada|Gondiya|21.4481|80.0253|B",
        "Itiadoh|Gondiya|20.801|80.167|B",
        "Kalisarar|Gondiya|21.1766|80.453|B",
        "Pujaritola P.U.Weir|Gondiya|21.2122|80.424|B",
        "Sirpur|Gondiya|21.0316|80.4501|B",
        "Kamthi Khairy|Nagpur|21.4608|79.1903|B",
        "Khindsi|Nagpur|21.404|79.3635|B",
        "Nand|Nagpur|20.7358|79.1319|B",
        "Totladoh|Nagpur|21.6588|79.232|B",
        "Wadgaon|Nagpur|20.8936|78.9486|B",
        "Bor|Wardha|20.973|78.6992|B",
        "Lower Wardha|Wardha|20.8783|78.2594|B",
        "Katepurna|Akola|20.4802|77.1568|B",
        "Wan|Akola|21.186|76.7768|B",
        "Upper Wardha|Amravati|21.2753|78.0572|A",
        "Khadakpurna|Buldhana|20.0469|76.1264|B",
        "Nalganga|Buldhana|20.7261|76.1803|B",
        "Pentakli|Buldhana|20.2581|76.4719|B",
        "Arunavati|Yavatmal|20.1313|77.7493|B",
        "Bembla|Yavatmal|20.6064|78.1286|B",
        "Isapur|Yavatmal|19.726|77.437|A",
        "Pus|Yavatmal|19.9917|77.5083|B",
        "Apegaon H L B|Chhatrapati Sambhajinagar|19.4425|75.505|B",
        "Paithan (Jayakwadi)|Chhatrapati Sambhajinagar|19.4857|75.37|B",
        "Borgaon Anjanpur|Beed|18.852|76.154|C",
        "Dhanegaon High Level Barrage|Beed|18.6186|76.0914|B",
        "Dongargaon H L B|Beed|18.785|76.182|C",
        "Majalgaon|Beed|19.16|76.1879|A",
        "Manjara|Beed|18.6599|75.6198|A",
        "Roshanpuri H L B|Beed|19.3858|75.7894|B",
        "Sirasmarg L L B|Beed|18.6783|76.2847|B",
        "Wangdari L T B|Beed|18.6014|76.4389|B",
        "Siddeshwar|Hingoli|19.5905|76.9561|B",
        "Yeldari|Hingoli|19.7143|76.7121|A",
        "Amdura|Nanded|19.0489|77.5853|B",
        "Dhalegaon H L B|Nanded|19.1931|76.5356|B",
        "Digadi H L B|Nanded|19.635|78.11|C",
        "Digras H L B|Nanded|19.1319|77.1583|B",
        "Hiradpuri H L B|Nanded|19.4125|75.6611|B",
        "Jogladevi H L B|Nanded|19.0264|76.8458|B",
        "Kinwat ( Mangrul ) H LB|Nanded|19.615|78.185|C",
        "Lonisawangi H L B|Nanded|19.0861|77.0189|B",
        "Lower Manar|Nanded|18.8424|77.3236|A",
        "Mangrul H L B|Nanded|19.115|76.685|C",
        "Mudgal H L B|Nanded|19.2486|76.3958|B",
        "Rajatakli H L B|Nanded|19.3458|76.0486|B",
        "Vishnupuri|Nanded|19.1047|77.2722|B",
        "Aurad K.T. Weir|Dharashiv|18.0419|76.6853|B",
        "Gunjarga L T B|Dharashiv|18.025|76.615|C",
        "Killari 2 L T B|Dharashiv|18.0558|76.5789|B",
        "Limbala L T B|Dharashiv|18.095|76.485|C",
        "Lower Terna|Dharashiv|18.0286|76.4304|A",
        "Madansuri L T B|Dharashiv|18.0683|76.6417|B",
        "Rajegaon L T B|Dharashiv|18.045|76.525|C",
        "Sina kolegaon|Dharashiv|18.3188|75.3958|B",
        "Tagarkheda L T B|Dharashiv|18.0819|76.7153|B",
        "Bhusani|Latur|18.415|76.825|C",
        "Bindgihal L T B|Latur|18.4986|76.6853|B",
        "Karsa Pohregaon|Latur|18.545|76.645|C",
        "Khulgapur|Latur|18.645|76.985|C",
        "Nagzari L T B|Latur|18.445|76.595|C",
        "Sai|Latur|18.4589|76.5417|B",
        "Shivni|Latur|18.185|76.655|C",
        "Takalgaon-Deola|Latur|18.475|76.745|C",
        "Wanjarkheda High level Barrage|Latur|18.5286|76.5519|B",
        "Lower Dudhana|Parbhani|19.5316|76.3933|A",
        "Bhandardara|Ahilyanagar|19.5453|73.7583|A",
        "Mula|Ahilyanagar|19.34|74.61|A",
        "Nilwande-2|Ahilyanagar|19.5461|73.9014|B",
        "Upper Tapi Hatnur|Jalgaon|21.0731|75.9457|A",
        "Waghur|Jalgaon|20.9268|75.7098|A",
        "Arjunsagar|Nashik|20.4719|73.9286|B",
        "Bham Dam|Nashik|19.6592|73.6453|B",
        "Bhavali|Nashik|19.6351|73.5906|B",
        "Chankapur|Nashik|20.4989|73.8834|B",
        "Darna|Nashik|19.8055|73.74|A",
        "Gangapur|Nashik|20.0261|73.6671|B",
        "Girna|Nashik|20.4766|74.7153|A",
        "Kadwa|Nashik|19.7555|73.7938|B",
        "Karanjwan|Nashik|20.291|73.764|B",
        "Mukane|Nashik|19.8143|73.6431|B",
        "Ozarkhed|Nashik|20.28|73.8759|B",
        "Palkhed|Nashik|20.1911|73.8834|B",
        "Punegaon|Nashik|20.3599|73.8347|B",
        "Tisgaon|Nashik|20.2581|73.9528|B",
        "Upper Vaitarna|Nashik|19.8181|73.5055|A",
        "Waghad|Nashik|20.2221|73.7166|B",
        "Waki Dam|Nashik|19.7083|73.6819|B",
        "Dudhaganga|Kolhapur|16.3533|74.0158|B",
        "Radhanagari H E P|Kolhapur|16.4061|73.96|B",
        "Tillari (Dhamne)|Kolhapur|15.8114|74.1619|B",
        "Tulshi|Kolhapur|16.5333|74.0189|B",
        "Nira Deoghar|Pune|18.0964|73.7253|A",
        "Dimbhe|Pune|19.096|73.742|A",
        "Bhama Askhed|Pune|18.835|73.723|A",
        "Yedgaon|Pune|19.1718|74.0036|B",
        "Chaskaman|Pune|18.9608|73.7854|A",
        "Pimpalgaon Joge|Pune|19.3247|73.8368|B",
        "Wadaj|Pune|19.1523|73.8491|B",
        "Manikdoh|Pune|19.2357|73.8144|A",
        "Ghod (Chinchani)|Pune|18.678|74.4924|B",
        "Pawana|Pune|18.6794|73.4753|B",
        "Bhatghar|Pune|18.1744|73.8701|A",
        "Khadakwasla|Pune|18.4417|73.7681|B",
        "Panshet|Pune|18.3784|73.613|B",
        "Warasgaon|Pune|18.3842|73.6114|B",
        "Gunjawani|Pune|18.3014|73.6719|B",
        "Temghar|Pune|18.4526|73.5558|B",
        "Mulshi Tata|Pune|18.5441|73.465|B",
        "Lonavala Tata|Pune|18.7364|73.3958|B",
        "Walwan Tata|Pune|18.7718|73.4272|B",
        "Shirawta Tata|Pune|18.8286|73.4419|B",
        "Thokerwadi Tata|Pune|18.9004|73.5353|B",
        "Kundali Tata|Pune|18.6789|73.3917|B",
        "Warna|Sangli|17.1361|73.8583|B",
        "Dhom Balkawadi|Satara|17.9942|73.7389|B",
        "Tarali|Satara|17.4458|73.8953|B",
        "Dhom|Satara|17.982|73.8183|A",
        "Koyna|Satara|17.4017|73.7522|B",
        "Kanher|Satara|17.7375|73.9159|A",
        "Urmodi|Satara|17.6671|73.9093|A",
        "Veer|Satara|18.1268|74.0966|A",
        "Bhima (Ujjani)|Solapur|18.0739|75.12|B",
        "Dhamni|Palghar|19.9251|73.0608|B",
        "Kawdas P. U. Weir|Palghar|19.7958|73.0389|B",
        "Middle Vaitarna|Palghar|19.7049|73.4341|B",
        "Dolwahal weir|Raigad|18.426|73.2204|B",
        "Tillari|Sindhudurg|15.7599|74.0886|A",
        "Barvi|Thane|19.19|73.3363|A",
        "Bhatsa|Thane|19.5127|73.4178|A",
        "Lower Chondhe|Thane|19.5619|73.4286|B",
        "Modaksagar|Thane|19.6923|73.3443|B",
        "Tansa|Thane|19.5589|73.2624|B",
        "Upper Ghatghar|Thane|19.5442|73.6689|B",
    };

    static final class Row {
        String name = "", date = "", time = "";      // name as printed in the report
        double dead, live, gross, liveNow, grossNow, pct, ly;   // Mcum; pct and last year in %
        // from the table
        String district = "";
        double lat = Double.NaN, lon = Double.NaN;
        boolean approx;
    }

    static volatile String why = "not tried yet";
    private static List<Row> rows;
    private static long rowsAt;

    // ---------------------------------------------------------------- PDF

    private static byte[] md5(byte[]... parts) throws Exception {
        java.security.MessageDigest m = java.security.MessageDigest.getInstance("MD5");
        for (byte[] p : parts) m.update(p);
        return m.digest();
    }

    private static byte[] rc4(byte[] key, byte[] data) {
        int[] s = new int[256];
        for (int i = 0; i < 256; i++) s[i] = i;
        int j = 0;
        for (int i = 0; i < 256; i++) { j = (j + s[i] + (key[i % key.length] & 255)) & 255; int t = s[i]; s[i] = s[j]; s[j] = t; }
        byte[] out = new byte[data.length];
        int a = 0, b = 0;
        for (int n = 0; n < data.length; n++) {
            a = (a + 1) & 255; b = (b + s[a]) & 255;
            int t = s[a]; s[a] = s[b]; s[b] = t;
            out[n] = (byte) (data[n] ^ s[(s[a] + s[b]) & 255]);
        }
        return out;
    }

    private static byte[] hex(String h) {
        h = h.replaceAll("[^0-9A-Fa-f]", "");
        byte[] r = new byte[h.length() / 2];
        for (int i = 0; i < r.length; i++) r[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        return r;
    }

    private static final byte[] PAD = hex("28BF4E5E4E758A4164004E56FFFA01082E2E00B6D0683E802F0CA9FE6453697A");

    /** Text items {y, x, text} from every page, in device space (y grows downward). */
    static List<Object[]> items(byte[] pdf) throws Exception {
        String d = new String(pdf, "ISO-8859-1");
        // encryption: standard handler, empty user password, 128-bit RC4 (V2 R3) or 40-bit (R2)
        byte[] key = null;
        java.util.regex.Matcher em = java.util.regex.Pattern.compile("/Encrypt\\s+(\\d+)\\s+\\d+\\s+R").matcher(d);
        if (em.find()) {
            java.util.regex.Matcher eo = java.util.regex.Pattern.compile("(?s)\\n" + em.group(1) + " \\d+ obj\\s*<<(.*?)>>\\s*endobj").matcher(d);
            if (!eo.find()) throw new Exception("encryption dictionary not found");
            String ed = eo.group(1);
            java.util.regex.Matcher mo = java.util.regex.Pattern.compile("/O\\s*<([0-9A-Fa-f\\s]+)>").matcher(ed);
            java.util.regex.Matcher mp = java.util.regex.Pattern.compile("/P\\s+(-?\\d+)").matcher(ed);
            java.util.regex.Matcher mr = java.util.regex.Pattern.compile("/R\\s+(\\d+)").matcher(ed);
            java.util.regex.Matcher ml = java.util.regex.Pattern.compile("/Length\\s+(\\d+)").matcher(ed);
            java.util.regex.Matcher mi = java.util.regex.Pattern.compile("/ID\\s*\\[\\s*<([0-9A-Fa-f]+)>").matcher(d);
            if (!mo.find() || !mp.find() || !mr.find() || !mi.find()) throw new Exception("unsupported encryption");
            if (ed.contains("/CF") || ed.contains("/V 4") || ed.contains("/V 5")) throw new Exception("unsupported encryption (AES)");
            int r = Integer.parseInt(mr.group(1));
            int klen = r >= 3 && ml.find() ? Integer.parseInt(ml.group(1)) / 8 : 5;
            int p = Integer.parseInt(mp.group(1));
            byte[] pb = {(byte) p, (byte) (p >> 8), (byte) (p >> 16), (byte) (p >> 24)};
            byte[] k = md5(PAD, hex(mo.group(1)), pb, hex(mi.group(1)));
            if (r >= 3) for (int i = 0; i < 50; i++) k = md5(java.util.Arrays.copyOf(k, klen));
            key = java.util.Arrays.copyOf(k, klen);
        }
        List<Object[]> out = new ArrayList<>();
        int pg = 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+) (\\d+) obj\\s*<<([^>]*)>>\\s*stream\\r?\\n").matcher(d);
        while (m.find()) {
            String hdr = m.group(3);
            if (!hdr.contains("FlateDecode") || hdr.contains("Image") || hdr.contains("/Subtype")) continue;
            int s = m.end(), e = d.indexOf("endstream", s);
            if (e < 0) continue;
            byte[] raw = java.util.Arrays.copyOfRange(pdf, s, e);
            int num = Integer.parseInt(m.group(1)), gen = Integer.parseInt(m.group(2));
            byte[] plain = null;
            for (int trim = 0; trim < 3 && plain == null; trim++) {   // the data may be followed by a line break before "endstream"
                int n = raw.length - (trim == 0 ? 0 : trim == 1 ? 1 : 2);
                if (n <= 0) break;
                byte[] c = java.util.Arrays.copyOf(raw, n);
                try {
                    if (key != null) {
                        byte[] ok = md5(key, new byte[]{(byte) num, (byte) (num >> 8), (byte) (num >> 16), (byte) gen, (byte) (gen >> 8)});
                        c = rc4(java.util.Arrays.copyOf(ok, Math.min(key.length + 5, 16)), c);
                    }
                    java.util.zip.Inflater inf = new java.util.zip.Inflater();
                    inf.setInput(c);
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[65536];
                    while (!inf.finished()) {
                        int k2 = inf.inflate(buf);
                        if (k2 == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                        bo.write(buf, 0, k2);
                    }
                    inf.end();
                    if (bo.size() > 0) plain = bo.toByteArray();
                } catch (Throwable ignored) { }
            }
            if (plain == null) continue;
            String c = new String(plain, "ISO-8859-1");
            if (!c.contains("BT")) continue;
            text(c, out, ++pg);
        }
        return out;
    }

    /** Walks a page's content stream keeping the transform, and collects the strings shown by Tj / TJ. */
    private static void text(String c, List<Object[]> out, int pg) {
        double[] ctm = {1, 0, 0, 1, 0, 0};
        ArrayList<double[]> stack = new ArrayList<>();
        double[] tm = {1, 0, 0, 1, 0, 0};
        ArrayList<Object> args = new ArrayList<>();
        int i = 0, n = c.length();
        while (i < n) {
            char ch = c.charAt(i);
            if (ch <= ' ') { i++; continue; }
            if (ch == '(') {
                StringBuilder sb = new StringBuilder();
                int depth = 1;
                i++;
                while (i < n && depth > 0) {
                    char x = c.charAt(i);
                    if (x == '\\' && i + 1 < n) {
                        char y = c.charAt(i + 1);
                        if (y >= '0' && y <= '7') {
                            int v = 0, q = 0;
                            while (q < 3 && i + 1 < n && c.charAt(i + 1) >= '0' && c.charAt(i + 1) <= '7') { v = v * 8 + (c.charAt(i + 1) - '0'); i++; q++; }
                            sb.append((char) v);
                            i++;
                            continue;
                        }
                        sb.append(y == 'n' ? '\n' : y == 'r' ? '\r' : y == 't' ? '\t' : y);
                        i += 2;
                        continue;
                    }
                    if (x == '(') depth++;
                    else if (x == ')') { depth--; if (depth == 0) { i++; break; } }
                    sb.append(x);
                    i++;
                }
                args.add(sb.toString());
                continue;
            }
            if (ch == '[') { args.add("["); i++; continue; }
            if (ch == ']') { args.add("]"); i++; continue; }
            if (ch == '/') { int j = i + 1; while (j < n && c.charAt(j) > ' ' && "/[]()<>".indexOf(c.charAt(j)) < 0) j++; args.add("/" + c.substring(i + 1, j)); i = j; continue; }
            if (ch == '<' || ch == '>') { i += (i + 1 < n && c.charAt(i + 1) == ch) ? 2 : 1; continue; }
            int j = i;
            while (j < n && c.charAt(j) > ' ' && "/[]()<>".indexOf(c.charAt(j)) < 0) j++;
            String tok = c.substring(i, j);
            i = j;
            char t0 = tok.charAt(0);
            if ((t0 >= '0' && t0 <= '9') || t0 == '-' || t0 == '+' || t0 == '.') {
                try { args.add(Double.parseDouble(tok)); } catch (Exception e) { args.add(0.0); }
                continue;
            }
            // operator
            switch (tok) {
                case "q": stack.add(ctm.clone()); break;
                case "Q": if (!stack.isEmpty()) ctm = stack.remove(stack.size() - 1); break;
                case "cm":
                    if (args.size() >= 6) {
                        double[] a = nums(args, 6);
                        ctm = mul(a, ctm);
                    }
                    break;
                case "BT": tm = new double[]{1, 0, 0, 1, 0, 0}; break;
                case "Tm": if (args.size() >= 6) tm = nums(args, 6); break;
                case "Td": case "TD":
                    if (args.size() >= 2) { double[] a = nums(args, 2); tm = mul(new double[]{1, 0, 0, 1, a[0], a[1]}, tm); }
                    break;
                case "Tj": case "TJ": {
                    StringBuilder sb = new StringBuilder();
                    for (Object o : args) if (o instanceof String && !"[".equals(o) && !"]".equals(o) && !((String) o).startsWith("/")) sb.append((String) o);
                    double[] dev = mul(tm, ctm);
                    if (sb.length() > 0) out.add(new Object[]{dev[5], dev[4], sb.toString(), pg});
                    break;
                }
                default: break;
            }
            args.clear();
        }
    }

    private static double[] nums(ArrayList<Object> a, int k) {
        double[] r = new double[k];
        int s = a.size() - k, q = 0;
        for (int i = s; i < a.size(); i++) r[q++] = a.get(i) instanceof Double ? (Double) a.get(i) : 0;
        return r;
    }

    /** m1 applied first, then m2 (PDF row-vector convention). */
    private static double[] mul(double[] a, double[] b) {
        return new double[]{a[0] * b[0] + a[1] * b[2], a[0] * b[1] + a[1] * b[3], a[2] * b[0] + a[3] * b[2], a[2] * b[1] + a[3] * b[3],
                a[4] * b[0] + a[5] * b[2] + b[4], a[4] * b[1] + a[5] * b[3] + b[5]};
    }

    private static final java.util.regex.Pattern ROW = java.util.regex.Pattern.compile(
            "^\\s*(\\d+)\\s+(.+?)\\s+(\\d{2}/\\d{2}/\\d{4})\\s+(\\d{1,2}:\\d{2}\\s*[AP]M)\\s+([0-9.]+)\\s+([0-9.]+)\\s+([0-9.]+)\\s+([0-9.]+)\\s+([0-9.]+)\\s+([0-9.]+)\\s*%\\s+([0-9.]+)\\s*%");

    private static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** The dam rows of the report, joined to the position table. */
    static List<Row> parse(byte[] pdf) throws Exception {
        List<Object[]> it = items(pdf);
        // one row per date cell: the items on the same page within 6 units of it (cells of a row can sit a few units apart)
        List<List<Object[]>> lines = new ArrayList<>();
        for (Object[] a : it) {
            if (!((String) a[2]).trim().matches("\\d{2}/\\d{2}/\\d{4}")) continue;
            List<Object[]> l = new ArrayList<>();
            for (Object[] o : it) if ((Integer) o[3] == (int) (Integer) a[3] && Math.abs((Double) o[0] - (Double) a[0]) <= 6) l.add(o);
            lines.add(l);
        }
        List<Row> res = new ArrayList<>();
        boolean[] used = new boolean[TABLE.length];
        for (List<Object[]> l : lines) {
            java.util.Collections.sort(l, (a, b) -> Double.compare((Double) a[1], (Double) b[1]));
            StringBuilder sb = new StringBuilder();
            for (Object[] o : l) sb.append(' ').append((String) o[2]);
            java.util.regex.Matcher m = ROW.matcher(sb.toString().replaceAll("\\s+", " ").trim());
            if (!m.find()) continue;
            Row r = new Row();
            r.name = m.group(2).trim();
            r.date = m.group(3);
            r.time = m.group(4).replaceAll("\\s+", " ");
            r.dead = Double.parseDouble(m.group(5));
            r.live = Double.parseDouble(m.group(6));
            r.gross = Double.parseDouble(m.group(7));
            r.liveNow = Double.parseDouble(m.group(8));
            r.grossNow = Double.parseDouble(m.group(9));
            r.pct = Double.parseDouble(m.group(10));
            r.ly = Double.parseDouble(m.group(11));
            // join to the table: same name, else the table name that starts with the printed one (names that wrap onto a second line)
            String pn = norm(r.name);
            int best = -1;
            for (int t = 0; t < TABLE.length && best < 0; t++) if (!used[t] && norm(TABLE[t].split("\\|")[0]).equals(pn)) best = t;
            for (int t = 0; t < TABLE.length && best < 0; t++) if (!used[t] && norm(TABLE[t].split("\\|")[0]).startsWith(pn) && pn.length() >= 4) best = t;
            if (best < 0) continue;
            used[best] = true;
            String[] f = TABLE[best].split("\\|");
            r.district = f[1];
            r.lat = Double.parseDouble(f[2]);
            r.lon = Double.parseDouble(f[3]);
            r.approx = f[4].equals("C");
            r.name = f[0].replaceAll("\\s+", " ").replace("( ", "(").replace(" )", ")").trim();
            res.add(r);
        }
        return res;
    }

    // ---------------------------------------------------------------- network + nearest

    static volatile boolean lenient;   // the last download had to accept an unrecognised certificate chain

    private static byte[] download() throws Exception {
        try {
            return download(false);
        } catch (javax.net.ssl.SSLHandshakeException e) {
            // the department's server sends a certificate chain Android cannot complete (you chose to accept it for this public report)
            return download(true);
        }
    }

    private static byte[] download(boolean accept) throws Exception {
        javax.net.ssl.HttpsURLConnection c = (javax.net.ssl.HttpsURLConnection) new java.net.URL(URL).openConnection();
        if (accept) {
            javax.net.ssl.SSLContext cx = javax.net.ssl.SSLContext.getInstance("TLS");
            cx.init(null, new javax.net.ssl.TrustManager[]{new javax.net.ssl.X509TrustManager() {
                public void checkClientTrusted(java.security.cert.X509Certificate[] ch, String t) { }
                public void checkServerTrusted(java.security.cert.X509Certificate[] ch, String t) { }
                public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
            }}, null);
            c.setSSLSocketFactory(cx.getSocketFactory());
            c.setHostnameVerifier((h, ss) -> h.equals("mwrdpravah.in"));   // still only this host
        }
        lenient = accept;
        c.setConnectTimeout(12_000);
        c.setReadTimeout(40_000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) NetSpeedXposed/1.0");
        int rc = c.getResponseCode();
        if (rc != 200) throw new java.io.IOException("HTTP " + rc);
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        try (java.io.InputStream in = c.getInputStream()) {
            byte[] b = new byte[32768];
            int k;
            while ((k = in.read(b)) > 0) bo.write(b, 0, k);
        }
        byte[] r = bo.toByteArray();
        if (r.length < 1000 || r[0] != '%') throw new java.io.IOException("not a PDF (" + r.length + " bytes)");
        return r;
    }

    private static android.app.Application app() {
        try { return (android.app.Application) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null); }
        catch (Throwable t) { return null; }
    }

    private static java.io.File cacheFile() {
        android.app.Application ap = app();
        return ap == null ? null : new java.io.File(ap.getCacheDir(), "dam_wrd.txt");
    }

    private static String serialize(List<Row> l) {
        StringBuilder sb = new StringBuilder();
        for (Row r : l) sb.append(String.join("|", r.name, r.district, "" + r.lat, "" + r.lon, r.approx ? "C" : "B", r.date, r.time,
                "" + r.live, "" + r.liveNow, "" + r.pct, "" + r.ly)).append('\n');
        return sb.toString();
    }

    private static List<Row> deserialize(String s) {
        List<Row> l = new ArrayList<>();
        for (String ln : s.split("\\n")) {
            String[] f = ln.split("\\|");
            if (f.length < 11) continue;
            Row r = new Row();
            r.name = f[0]; r.district = f[1]; r.lat = Double.parseDouble(f[2]); r.lon = Double.parseDouble(f[3]); r.approx = f[4].equals("C");
            r.date = f[5]; r.time = f[6]; r.live = Double.parseDouble(f[7]); r.liveNow = Double.parseDouble(f[8]); r.pct = Double.parseDouble(f[9]); r.ly = Double.parseDouble(f[10]);
            l.add(r);
        }
        return l;
    }

    /** Today's rows: fetched at most every 3 h; the last good copy (up to 3 days old) when the fetch fails. */
    private static synchronized List<Row> load(long now, StringBuilder log) {
        if (rows != null && now - rowsAt < 3 * 3600_000L) return rows;
        try {
            List<Row> l = parse(download());
            if (l.size() < 50) throw new Exception("only " + l.size() + " dam rows found in the report");
            rows = l; rowsAt = now;
            java.io.File f = cacheFile();
            if (f != null) java.nio.file.Files.write(f.toPath(), serialize(l).getBytes("UTF-8"));
            return l;
        } catch (Throwable t) {
            log.append("report fetch failed: ").append(t.getClass().getSimpleName()).append(t.getMessage() == null ? "" : " " + t.getMessage()).append("; ");
        }
        try {
            java.io.File f = cacheFile();
            if (f != null && f.exists() && now - f.lastModified() < 3 * 86400_000L) {
                List<Row> l = deserialize(new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8"));
                if (!l.isEmpty()) { rows = l; rowsAt = now - 2 * 3600_000L; log.append("using the saved copy from ").append(new java.util.Date(f.lastModified())).append("; "); return l; }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /** The n nearest dams in the report already held (memory or the saved copy); no network. {Row, km}. */
    static List<Object[]> nearby(double lat, double lon, int n) {
        List<Row> l = rows;
        try {
            if (l == null) {
                java.io.File f = cacheFile();
                if (f != null && f.exists()) l = deserialize(new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8"));
            }
        } catch (Throwable ignored) { }
        List<Object[]> o = new java.util.ArrayList<>();
        if (l == null) return o;
        for (Row r : l) if (!Double.isNaN(r.lat)) o.add(new Object[]{r, Dam.km(lat, lon, r.lat, r.lon)});
        java.util.Collections.sort(o, (a, b) -> Double.compare((Double) a[1], (Double) b[1]));
        return o.size() > n ? new ArrayList<>(o.subList(0, n)) : o;
    }

    static String isoDate(String dmy) {
        String[] p = dmy.split("/");
        return p.length == 3 ? p[2] + "-" + p[1] + "-" + p[0] : dmy;
    }

    /** Nearest of the 138 dams within 80 km, as a Hit; null if none or the report cannot be read. */
    static DamMh.Hit find(double lat, double lon, long now) {
        StringBuilder log = new StringBuilder();
        List<Row> l = load(now, log);
        if (l == null) { why = log.toString(); return null; }
        Row best = null;
        double bk = 1e9;
        for (Row r : l) {
            double k = Dam.km(lat, lon, r.lat, r.lon);
            if (k < bk) { bk = k; best = r; }
        }
        if (best == null || bk > 80) { why = "no major dam within 80 km" + (log.length() > 0 ? " (" + log + ")" : ""); return null; }
        DamMh.Hit h = new DamMh.Hit();
        Dam.Res r = new Dam.Res();
        r.uid = "MH:wrd/" + norm(best.name);
        r.name = best.name;
        r.lat = best.lat; r.lon = best.lon;
        r.liveCap = best.live / 1000.0;
        r.approx = best.approx;
        r.district = best.district;
        Dam.Reading g = new Dam.Reading();
        g.storage = best.liveNow / 1000.0;
        g.liveCap = best.live / 1000.0;
        g.pct = best.pct;
        g.date = isoDate(best.date);
        g.ly = best.ly;
        g.time = best.time;
        h.res = r; h.rd = g; h.km = bk;
        why = best.name + " " + Math.round(best.pct) + "%, " + Math.round(bk) + " km, Maharashtra WRD report " + best.date + " " + best.time + " (" + l.size() + " dams" + (lenient ? "; the site's certificate is not one Android recognises, accepted for this public report only" : "") + ")"
                + (log.length() > 0 ? "; " + log : "");
        return h;
    }

    // for testing on a computer
    public static void main(String[] a) throws Exception {
        byte[] pdf = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(a[0]));
        for (Row r : parse(pdf)) System.out.println(String.join(",", r.district, r.name, r.date, r.time, "" + r.dead, "" + r.live, "" + r.gross, "" + r.liveNow, "" + r.grossNow, "" + r.pct, "" + r.ly, "" + r.lat, "" + r.lon, r.approx ? "C" : "B"));
    }
}
