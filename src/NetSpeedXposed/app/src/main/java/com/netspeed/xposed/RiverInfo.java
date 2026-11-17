package com.netspeed.xposed;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Facts about the main rivers of Maharashtra and the Deccan (source, length, basin, where it ends, discharge, confluences).
 * Taken from Wikipedia river pages on 2026-11-16; a blank means the page did not say. Nothing is guessed.
 */
final class RiverInfo {
    private RiverInfo() {}

    static final class R {
        String source = "", length = "", basin = "", ends = "", discharge = "", states = "", meets = "";
    }

    private static final Map<String, R> M = new HashMap<>();

    private static R r(String name) {
        R x = new R();
        M.put(name.toLowerCase(Locale.ROOT), x);
        return x;
    }

    static R get(String name) { return name == null ? null : M.get(name.toLowerCase(Locale.ROOT)); }

    static {
        R x;
        x = r("Pavana"); x.source = "Western Ghats, about 6 km south of Lonavala"; x.length = "about 58–60 km"; x.ends = "joins the Mula near Sangvi, before Pune city";
        x = r("Mula"); x.ends = "meets the Mutha at Sangamwadi, Pune; together they are the Mula-Mutha";
        x = r("Mutha"); x.source = "Western Ghats, Pune district"; x.length = "36 km"; x.ends = "meets the Mula at Sangamwadi, Pune";
        x = r("Mula-Mutha"); x.source = "formed where the Mula and the Mutha meet in Pune (Sangamwadi)"; x.ends = "joins the Bhima at Ranjangaon Sandas";
        x = r("Indrayani"); x.source = "Kurvande village, near Lonavala"; x.length = "104 km"; x.ends = "joins the Bhima near Tulapur";
        x = r("Nira"); x.source = "Shirgaon, Bhor (about 750 m)"; x.length = "209 km"; x.ends = "joins the Bhima near Nira Narsingpur, close to Akluj";
        x = r("Bhima"); x.source = "Bhimashankar hills, Khed taluka, Pune district (about 945 m)"; x.length = "861 km"; x.basin = "70,614 km²";
        x.ends = "joins the Krishna on the Karnataka–Telangana border, about 24 km north of Raichur";
        x.meets = "Indrayani at Tulapur · Mula-Mutha at Ranjangaon Sandas · Ghod opposite Nanvij · Nira near Nira Narsingpur";
        x = r("Koyna"); x.source = "Hunter's Point, near Mahabaleshwar"; x.length = "130 km"; x.ends = "joins the Krishna at Pritisangam, near Karad";
        x = r("Panchganga"); x.source = "Prayag Sangam, Padali BK, Karvir taluka, Kolhapur"; x.length = "80.7 km"; x.ends = "joins the Krishna at Narsobawadi";
        x.meets = "formed by the Kasari, Kumbhi, Tulsi and Bhogawati (local tradition adds an underground Saraswati)";
        x = r("Tungabhadra"); x.source = "Tunga and Bhadra rise at Gangamoola (Western Ghats) and meet at Koodli"; x.length = "about 531 km below Koodli"; x.basin = "about 69,552 km²";
        x.ends = "joins the Krishna near Gudimalla and Murvakonda"; x.states = "Karnataka, Andhra Pradesh, Telangana";
        x = r("Krishna"); x.source = "Jor village near Mahabaleshwar, Satara"; x.length = "about 1,400 km"; x.basin = "258,948 km²";
        x.ends = "Bay of Bengal, at Hamsaladeevi, Krishna district, Andhra Pradesh"; x.discharge = "about 2,213 m³/s (Vijayawada, 1901–1979 average)";
        x.states = "Maharashtra, Karnataka, Telangana, Andhra Pradesh";
        x.meets = "Koyna at Pritisangam (Karad) · Panchganga, Warana and Yerla near Sangli · Malaprabha at Kudalasangama · Bhima near Raichur · Tungabhadra near Gudimalla";
        x = r("Godavari"); x.source = "Brahmagiri, Trimbakeshwar, Nashik (about 920 m)"; x.length = "1,465 km"; x.basin = "312,812 km²";
        x.ends = "Bay of Bengal, at Antarvedi, Konaseema district, Andhra Pradesh"; x.discharge = "about 3,505 m³/s at the mouth";
        x.states = "Maharashtra, Telangana, Andhra Pradesh, Chhattisgarh, Odisha";
        x.meets = "Pravara at Pravara Sangam, Nevasa · Purna at Jambulbet · Manjira at Kandakurthi · Pranhita at Kaleshwaram · Indravati at Somnoor Sangam";
        x = r("Pravara"); x.source = "eastern slopes of the Sahyadris, Ahmednagar–Nashik, between Kulang and Ratangad"; x.length = "208 km"; x.ends = "joins the Godavari at Pravara Sangam, Nevasa";
        x = r("Tapi"); x.source = "Multai, Madhya Pradesh"; x.length = "about 724 km"; x.basin = "62,225 km²"; x.ends = "Arabian Sea, Gulf of Khambhat, at Dumas near Surat";
        x.discharge = "about 489 m³/s"; x.states = "Madhya Pradesh, Maharashtra, Gujarat";
        M.put("tapti", x);
        x = r("Narmada"); x.source = "Narmada Kund, Amarkantak plateau, Anuppur district, Madhya Pradesh"; x.length = "about 1,312 km"; x.basin = "98,796 km²";
        x.ends = "Arabian Sea, Gulf of Khambhat, about 30 km west of Bharuch"; x.discharge = "about 1,447 m³/s"; x.states = "Madhya Pradesh, Maharashtra (border), Gujarat";
        x = r("Ulhas"); x.source = "near Tungarli Lake, Lonavala"; x.length = "about 122 km"; x.ends = "splits at Thane: one arm to the sea near Vasai, one into Thane Creek";
        x = r("Vaitarna"); x.source = "Sahyadri, near Trimbakeshwar"; x.ends = "meets the Tansa just before the Arabian Sea";
        x = r("Kundalika"); x.source = "Bhira, Mangaon taluka, Raigad"; x.ends = "Arabian Sea";
        x = r("Savitri"); x.source = "Savitri Point, near Mahabaleshwar"; x.length = "about 110 km"; x.ends = "Arabian Sea, at Bankot Creek near Harihareshwar";
        x = r("Vashishti"); x.source = "Western Ghats"; x.ends = "Arabian Sea";
    }
}
