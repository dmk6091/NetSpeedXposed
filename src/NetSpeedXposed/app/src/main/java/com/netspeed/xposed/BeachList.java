package com.netspeed.xposed;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Built-in beaches of the west and east coast of India (approximate points, a few km), so the nearest one never needs the internet. */
final class BeachList {
    private BeachList() {}

    // name, lat, lon  (west coast south to north is listed first, then the east coast south to north)
    private static final Object[][] T = {
        // Gujarat, Daman
        {"Dwarka", 22.24, 68.96}, {"Mandvi (Kutch)", 22.82, 69.35}, {"Porbandar", 21.63, 69.60}, {"Somnath", 20.90, 70.40},
        {"Nagoa (Diu)", 20.71, 70.93}, {"Dumas", 21.08, 72.71}, {"Dandi", 20.89, 72.81}, {"Tithal", 20.62, 72.90}, {"Jampore (Daman)", 20.41, 72.83},
        // Maharashtra
        {"Dahanu", 19.97, 72.70}, {"Kelva", 19.62, 72.72}, {"Arnala", 19.46, 72.73}, {"Vasai", 19.37, 72.79}, {"Uttan", 19.29, 72.79},
        {"Gorai", 19.23, 72.78}, {"Aksa", 19.18, 72.79}, {"Versova", 19.13, 72.81}, {"Juhu", 19.10, 72.83}, {"Dadar Chowpatty", 19.02, 72.84},
        {"Girgaon Chowpatty", 18.95, 72.81}, {"Kihim", 18.72, 72.88}, {"Nagaon", 18.60, 72.87}, {"Alibag", 18.64, 72.87}, {"Revdanda", 18.54, 72.92},
        {"Kashid", 18.43, 72.91}, {"Murud", 18.33, 72.96}, {"Diveagar", 18.18, 72.99}, {"Shrivardhan", 18.05, 72.99}, {"Harihareshwar", 17.99, 73.02},
        {"Velas", 17.98, 73.04}, {"Kelshi", 17.93, 73.02}, {"Anjarle", 17.83, 73.08}, {"Harnai", 17.81, 73.10}, {"Karde", 17.76, 73.17},
        {"Guhagar", 17.48, 73.19}, {"Velneshwar", 17.40, 73.21}, {"Ganpatipule", 17.14, 73.27}, {"Aare Ware", 17.06, 73.27}, {"Bhatye (Ratnagiri)", 16.98, 73.27},
        {"Vijaydurg", 16.56, 73.33}, {"Kunkeshwar", 16.38, 73.40}, {"Malvan", 16.06, 73.46}, {"Tarkarli", 16.01, 73.47}, {"Vengurla", 15.86, 73.63},
        // Goa
        {"Arambol", 15.69, 73.70}, {"Morjim", 15.63, 73.73}, {"Vagator", 15.60, 73.74}, {"Baga", 15.55, 73.75}, {"Calangute", 15.54, 73.76},
        {"Miramar", 15.48, 73.81}, {"Colva", 15.28, 73.91}, {"Agonda", 15.04, 73.99}, {"Palolem", 15.01, 74.02},
        // Karnataka
        {"Karwar", 14.81, 74.12}, {"Om Beach (Gokarna)", 14.52, 74.31}, {"Honnavar", 14.28, 74.43}, {"Murudeshwar", 14.09, 74.48},
        {"Maravanthe", 13.71, 74.63}, {"Malpe", 13.38, 74.70}, {"Kaup", 13.22, 74.74}, {"Panambur", 12.95, 74.80}, {"Ullal", 12.80, 74.84},
        // Kerala, southern tip
        {"Bekal", 12.39, 75.03}, {"Payyambalam", 11.88, 75.36}, {"Kappad", 11.38, 75.72}, {"Kozhikode", 11.26, 75.77}, {"Cherai", 10.14, 76.18},
        {"Fort Kochi", 9.97, 76.24}, {"Alappuzha", 9.49, 76.31}, {"Varkala", 8.73, 76.70}, {"Kovalam", 8.40, 76.98}, {"Kanyakumari", 8.08, 77.54},
        // Tamil Nadu
        {"Tiruchendur", 8.49, 78.12}, {"Dhanushkodi", 9.18, 79.43}, {"Velankanni", 10.68, 79.85}, {"Puducherry", 11.93, 79.84},
        {"Mahabalipuram", 12.62, 80.20}, {"Marina (Chennai)", 13.05, 80.28}, {"Pulicat", 13.42, 80.32},
        // Andhra Pradesh
        {"Mypadu (Nellore)", 14.50, 80.19}, {"Suryalanka", 15.88, 80.54}, {"Manginapudi", 16.20, 81.22}, {"Kakinada", 16.95, 82.28},
        {"RK Beach (Visakhapatnam)", 17.71, 83.32}, {"Bheemili", 17.89, 83.45}, {"Kalingapatnam", 18.34, 84.13},
        // Odisha, West Bengal
        {"Gopalpur", 19.26, 84.91}, {"Puri", 19.80, 85.83}, {"Chandrabhaga (Konark)", 19.89, 86.12}, {"Paradip", 20.32, 86.61},
        {"Chandipur", 21.46, 87.03}, {"Digha", 21.62, 87.54}, {"Mandarmani", 21.66, 87.74}, {"Bakkhali", 21.56, 88.27},
    };

    static List<Beach.Hit> nearest(double lat, double lon, int n) {
        List<Beach.Hit> out = new ArrayList<>();
        for (Object[] r : T) {
            Beach.Hit h = new Beach.Hit();
            h.name = (String) r[0]; h.lat = (Double) r[1]; h.lon = (Double) r[2];
            h.km = Beach.km(lat, lon, h.lat, h.lon);
            h.dir = Beach.dir(lat, lon, h.lat, h.lon);
            out.add(h);
        }
        java.util.Collections.sort(out, (a, b) -> Double.compare(a.km, b.km));
        return out.size() > n ? new ArrayList<>(out.subList(0, n)) : out;
    }
}
