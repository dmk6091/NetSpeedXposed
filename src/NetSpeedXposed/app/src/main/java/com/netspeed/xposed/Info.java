package com.netspeed.xposed;

import java.util.Locale;

/** Builds the feed for the app's Info tab (runs in System UI): lines "S|label|value", S = A astronomy, E earth, D disasters. */
final class Info {
    private Info() {}

    private static void add(StringBuilder b, char s, String label, String v) {
        if (v == null) return;
        v = v.replace('\n', ' ').replace('|', '/').trim();
        if (v.isEmpty() || v.equals("-")) return;
        b.append(s).append('|').append(label).append('|').append(v).append('\n');
    }

    static volatile String row3 = "", row4 = "";
    static final String[] radioDiag = {"", "", "", ""};   // per SIM slot: tower direction and the radio frames it has right now

    static String feed(double lat, double lon, long now) {
        StringBuilder b = new StringBuilder();
        try { for (String[] p : SkyStrip.infoLines(now)) add(b, 'A', p[0], p[1]); } catch (Throwable ignored) { }
        try { add(b, 'A', "Overhead now", Sat.issText); } catch (Throwable ignored) { }
        try { add(b, 'E', "River flow", RiverFlow.text()); } catch (Throwable ignored) { }
        try { add(b, 'E', "Dam / weir", EarthLine.dam); } catch (Throwable ignored) { }
        try { add(b, 'E', "Rain", EarthLine.rain); } catch (Throwable ignored) { }
        try {
            String soil = EarthLine.soilType == null ? "" : EarthLine.soilType;
            if (!soil.isEmpty()) {
                String x = soil + (EarthLine.soilSplit == null || EarthLine.soilSplit.isEmpty() ? "" : " · " + EarthLine.soilSplit)
                        + (EarthLine.soilMoist == null || EarthLine.soilMoist.isEmpty() ? "" : " · " + EarthLine.soilMoist);
                add(b, 'E', "Soil", x);
            }
        } catch (Throwable ignored) { }
        try {
            Tide.Now t = Tide.now(now);
            if (t != null) {
                java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("HH:mm", Locale.US);
                add(b, 'E', "Tide " + (t.place == null ? "" : t.place), String.format(Locale.US, "%.1f m, %s; next %s %.1f m at %s", t.height,
                        t.rising ? "rising" : "falling", t.nextHigh ? "high" : "low", t.nextHeight, f.format(new java.util.Date(t.nextAt))));
            }
        } catch (Throwable ignored) { }
        try { add(b, 'E', "Lookups for the status bar", "beach: " + Tide.beachWhy + " \u00b7 wetland: " + Wetland.why); } catch (Throwable ignored) { }
        try {
            java.util.List<String> ws = Watershed.chains(Watershed.riverName(RiverFlow.text()));
            if (!ws.isEmpty()) add(b, 'E', "Where the water goes", ws.get(0).replace("\uD83D\uDCA7 ", ""));
        } catch (Throwable ignored) { }
        try {
            double[] bp = Tide.beachPt(lat, lon);
            if (bp != null && Tide.beachName != null)
                add(b, 'E', "Nearest beach", Tide.beachName + " \u00b7 " + Math.round(Beach.km(lat, lon, bp[0], bp[1])) + " km " + Beach.dir(lat, lon, bp[0], bp[1]) + " (straight line)");
        } catch (Throwable ignored) { }
        try { if (!EarthLine.rockType.isEmpty()) add(b, 'E', "Rock", EarthLine.rockType + (EarthLine.rockUnit.isEmpty() ? "" : " \u00b7 " + EarthLine.rockUnit) + (EarthLine.rockAge.isEmpty() ? "" : " \u00b7 " + EarthLine.rockAge)); } catch (Throwable ignored) { }
        try { add(b, 'E', "Power", EarthLine.powerLine + (EarthLine.powerSub == null || EarthLine.powerSub.isEmpty() ? "" : " · " + EarthLine.powerSub)); } catch (Throwable ignored) { }
        try { add(b, 'E', "Terrain", EarthLine.slope); } catch (Throwable ignored) { }
        try { add(b, 'E', "Highest point nearby", EarthLine.peak); } catch (Throwable ignored) { }
        try { add(b, 'E', "Ground", EarthLine.ground); } catch (Throwable ignored) { }
        try { add(b, 'E', "District", EarthLine.district); } catch (Throwable ignored) { }
        try { add(b, 'D', "Earthquakes", EarthLine.quake); } catch (Throwable ignored) { }
        try {
            int n = 0;
            for (Hazards.Ev e : Hazards.events) {
                if (n++ >= 4) break;
                add(b, 'D', (e.red ? "🔴 " : "🟠 ") + Hazards.icon(e.type) + " " + (e.name == null ? "" : e.name),
                        (e.place == null ? "" : e.place) + (e.km >= 0 ? " · " + e.km + " km " + (e.dir == null ? "" : e.dir) : ""));
            }
        } catch (Throwable ignored) { }
        try { if (!Float.isNaN(Hazards.kp)) add(b, 'D', "Geomagnetic Kp", String.format(Locale.US, "%.0f%s", Hazards.kp, Hazards.kp >= 5 ? " (storm)" : " (quiet)")); } catch (Throwable ignored) { }
        try { strips(b, lat, lon, now); } catch (Throwable ignored) { }
        try { diag(b, lat, lon, now); } catch (Throwable ignored) { }
        return b.toString();
    }

    /** The new items by section: Sky (weather feed), Earth (plate, boundary, El Nino, sea), and the layout lines. */
    private static void diag(StringBuilder b, double lat, double lon, long now) {
        java.util.Locale us = java.util.Locale.US;
        final String offE = Cfg.b(Cfg.EARTH_SLOT, Cfg.D_EARTH_SLOT) ? "" : "\u23F8 off in settings (row 4 earth slot) \u00b7 ";
        final String offS = Cfg.b(Cfg.SEA_FRAMES, Cfg.D_SEA_FRAMES) ? "" : "\u23F8 sea frames off in settings \u00b7 ";
        try {
            double sa = Astro.sun(now, lat, lon).alt;
            float sh = SkyView.shine(sa);
            add(b, 'A', "Sunshine now", SkyView.rad < 0 ? "no radiation value yet" : String.format(us, "%.0f W/m\u00b2", SkyView.rad) + (sh >= 0 ? String.format(us, " \u00b7 %.0f%% of a clear sky at this sun height", sh * 100) : " \u00b7 sun too low (under 5\u00b0) to compare") + " \u00b7 drives the Sun's glow");
        } catch (Throwable ignored) { }
        try {   // day / night time left, shown above the weekday
            double[] st = SunTimes.today(lat, lon);
            if (Double.isNaN(st[0]) || Double.isNaN(st[1])) add(b, 'A', "Time left (above the weekday)", "no sunrise / sunset for this place today");
            else {
                java.util.Calendar cl = java.util.Calendar.getInstance();
                cl.setTimeInMillis(now);
                double mm = cl.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cl.get(java.util.Calendar.MINUTE);
                boolean dayNow = mm >= st[0] && mm < st[1];
                int lm = (int) Math.round(dayNow ? st[1] - mm : (mm < st[0] ? st[0] - mm : st[0] + 1440 - mm));
                add(b, 'A', "Time left (above the weekday)", (dayNow ? "day, " : "night, ") + (lm / 60) + "h " + (lm % 60) + "m until " + (dayNow ? "sunset " + SunTimes.hhmm(st[1]) : "sunrise " + SunTimes.hhmm(st[0]))
                        + String.format(us, " \u00b7 day is %.0f h %.0f min long", Math.floor((st[1] - st[0]) / 60), (st[1] - st[0]) % 60) + " \u00b7 sunrise/sunset from the sun's position at your coordinates");
            }
        } catch (Throwable ignored) { }
        try {   // the point the weather is calculated for
            add(b, 'A', "Weather is calculated for", Float.isNaN(TowerArea.WX_GRID_KM) ? "model point not known yet (strip falls back to the place name)"
                    : (TowerArea.WX_GRID_NAME.isEmpty() ? "an unnamed spot" : TowerArea.WX_GRID_NAME) + " \u00b7 " + (TowerArea.WX_GRID_KM < 0.3f ? "on your spot" : String.format(us, "%.1f km %s from you", TowerArea.WX_GRID_KM, TowerArea.WX_GRID_DIR))
                            + " \u00b7 modelled by Open-Meteo for that grid point, not measured by a station (nearest airport reports are used only within 60 km, none now)");
        } catch (Throwable ignored) { }
        add(b, 'A', "Display options (today's additions)", String.format(us, "SIM boxes up %s \u00b7 Q for 2nd SIM %s \u00b7 moon + day-type initials %s \u00b7 layout %s",
                Cfg.b(Cfg.SIM_UP, Cfg.D_SIM_UP) ? "on" : "off", Cfg.b(Cfg.Q_SECOND, Cfg.D_Q_SECOND) ? "on" : "off", Cfg.b(Cfg.SEA_MOON, Cfg.D_SEA_MOON) ? "on" : "off",
                Cfg.i(Cfg.LAYOUT, Cfg.D_LAYOUT) == 3 ? "Tower column" : String.valueOf(Cfg.i(Cfg.LAYOUT, Cfg.D_LAYOUT))));
        add(b, 'A', "Weather feed (Open-Meteo)", Diag.line("api.open-meteo.com") + " \u00b7 " + WxSky.why);
        try {
            String pc = EarthSci.plateCode(lat, lon);
            if (pc != null) {
                double[] mo = EarthSci.motion(pc);
                add(b, 'E', "Tectonic plate", offE + EarthSci.plateTitle(pc) + (mo == null ? "" : String.format(us, " \u00b7 about %.1f cm/yr towards %s (rough, no-net-rotation)", mo[0], EarthSci.dir16(mo[1]))));
            }
            EarthSci.Bound bd = EarthSci.nearest(lat, lon);
            if (bd != null) add(b, 'E', "Nearest plate boundary", offE + bd.kind + " (" + bd.label + ") " + bd.pair + " \u00b7 " + EarthSci.fmtKm(bd.km) + " km " + EarthSci.dir16(bd.az) + (bd.rate >= 0 ? String.format(us, " \u00b7 %.1f cm/yr relative motion", bd.rate) : "") + " \u00b7 data: Bird 2003 (PB2002)");
        } catch (Throwable ignored) { }
        add(b, 'E', "El Ni\u00f1o (Pacific)", offE + (Double.isNaN(EarthSci.oni) ? "not available yet" : EarthSci.phase() + String.format(us, " %+.1f\u00b0 ONI %s", EarthSci.oni, EarthSci.oniSeason) + (EarthSci.strength().isEmpty() ? "" : " \u00b7 " + EarthSci.strength())));
        add(b, 'E', "El Ni\u00f1o: where the water is", offE + (EarthSci.whereLong().isEmpty() ? "" : "strongest in " + EarthSci.whereLong()));
        add(b, 'E', "El Ni\u00f1o feed (NOAA CPC)", Diag.line("www.cpc.ncep.noaa.gov") + (EarthSci.why.isEmpty() ? "" : " \u00b7 " + EarthSci.why));
        add(b, 'E', "Climate topics (IOD, MJO, NAO, AO, ATL3, QBO, PDO, Arabian Sea)", offE + (ClimIdx.at == 0 ? "not fetched yet" : String.format(us, "IOD %s (%s) \u00b7 MJO %s \u00b7 NAO %s (%s) \u00b7 PDO %s (%s) \u00b7 AO %s \u00b7 ATL3 %s \u00b7 QBO %s \u00b7 sea %s \u00b7 ENSO trend %s",
                Double.isNaN(ClimIdx.iod) ? "-" : ClimIdx.fmt(ClimIdx.iod), ClimIdx.MON[ClimIdx.iodM],
                Double.isNaN(ClimIdx.mjoAmp) ? "-" : "ph" + ClimIdx.mjoPhase + " amp " + String.format(us, "%.1f", ClimIdx.mjoAmp),
                Double.isNaN(ClimIdx.nao) ? "-" : ClimIdx.fmt(ClimIdx.nao), ClimIdx.MON[ClimIdx.naoM],
                Double.isNaN(ClimIdx.pdo) ? "-" : ClimIdx.fmt(ClimIdx.pdo), ClimIdx.MON[ClimIdx.pdoM],
                Double.isNaN(ClimIdx.ao) ? "-" : ClimIdx.fmt(ClimIdx.ao), Double.isNaN(ClimIdx.atl) ? "-" : ClimIdx.fmt(ClimIdx.atl), Double.isNaN(ClimIdx.qbo) ? "-" : String.format(us, "%.0f", ClimIdx.qbo),
                Double.isNaN(ClimIdx.sst) ? "-" : String.format(us, "%.1f\u00b0", ClimIdx.sst), Double.isNaN(ClimIdx.trend) ? "-" : ClimIdx.fmt(ClimIdx.trend))));
        try {
            float[] sv = Tide.sea(now);
            double[] xy = Tide.placeXY();
            String cn = xy == null ? null : EarthSci.currentName(xy[0], xy[1]);
            add(b, 'E', "Sea at " + (Tide.place == null ? "the tide place" : Tide.place), offS + (sv == null ? "no marine data yet" : (Float.isNaN(sv[0]) ? "" : String.format(us, "waves %.1f m", sv[0]) + (Float.isNaN(sv[1]) ? "" : String.format(us, " \u00b7 %.0f s", sv[1])) + " \u00b7 " + Tide.stateWord(sv[0]) + " \u00b7 ") + (Float.isNaN(sv[2]) ? "no current" : String.format(us, "current %.1f kn", sv[2] / 1.852f)) + (cn == null ? "" : " \u00b7 " + cn + " (name from a built-in table, approximate)")));
            add(b, 'E', "Marine feed (Open-Meteo)", Diag.line("marine-api.open-meteo.com") + (Tide.fetchedAt > 0 ? " \u00b7 data from " + Math.max(0, (now - Tide.fetchedAt) / 60000L) + " min ago" : ""));
        } catch (Throwable ignored) { }
        for (int i = 0; i < 4; i++) if (!radioDiag[i].isEmpty()) add(b, 'L', "SIM box radio frames, SIM slot " + (i + 1), radioDiag[i]);
        add(b, 'L', "Row 3 (tide and sea)", row3.isEmpty() ? "not drawn yet" : row3);
        add(b, 'L', "Row 4 (weather, disaster, earth)", row4.isEmpty() ? "not drawn yet" : row4);
    }

    /** One line per strip of the place line ("Ravet \u00b7 ..."): does it have data right now, and if not, why. Lines "S|label|state". */
    private static void strip(StringBuilder b, String label, boolean on, String val, String why) {
        String v = !on ? "\u23F8 off in settings" : val != null && !val.trim().isEmpty() ? "\u2705 " + val.trim() : "\u26A0\uFE0F no data" + (why == null || why.isEmpty() ? "" : " \u00b7 " + why);
        b.append("S|").append(label).append('|').append(v.replace('\n', ' ').replace('|', '/')).append('\n');
    }

    private static void strips(StringBuilder b, double lat, double lon, long now) {
        try { strip(b, "River flow", Cfg.b(Cfg.RIVER, Cfg.D_RIVER), RiverFlow.text(), RiverFlow.why); } catch (Throwable t) { strip(b, "River flow", true, "", t.toString()); }
        try {
            java.util.List<String> ws = Watershed.chains(Watershed.riverName(RiverFlow.text()));
            strip(b, "Where the river goes", Cfg.b(Cfg.RIVER, Cfg.D_RIVER), ws.isEmpty() ? "" : ws.get(0), "river has no name yet, or is not in the river table");
        } catch (Throwable ignored) { }
        try { strip(b, "Dam / weir nearby", Cfg.b(Cfg.RIVER, Cfg.D_RIVER), EarthLine.dam, "none within the search radius, or the lookup failed"); } catch (Throwable ignored) { }
        try {
            Dam.Reading dg = Dam.curRead;
            boolean fresh = dg != null && Dam.cur != null && System.currentTimeMillis() - Dam.fetchedAt < 3 * 86400_000L;
            strip(b, "Reservoir level", Cfg.b(Cfg.DAM, Cfg.D_DAM), fresh ? Dam.cur.name + " " + Math.round(dg.pct) + "% full" : "", Dam.lastError);
        } catch (Throwable ignored) { }
        try { strip(b, "Rain", Cfg.b(Cfg.EL_RAIN, Cfg.D_EL_RAIN), EarthLine.rain, EarthLine.why); } catch (Throwable ignored) { }
        try { strip(b, "Cloud and wind", Cfg.b(Cfg.WX_SKY, Cfg.D_WX_SKY), WxSky.kindLine + " \u00b7 " + WxSky.dirLine + " \u00b7 " + WxSky.windLine, WxSky.why); } catch (Throwable ignored) { }
        try { strip(b, "Soil", Cfg.b(Cfg.EL_SOIL, Cfg.D_EL_SOIL), EarthLine.soilType, "SoilGrids has no data here or was slow"); } catch (Throwable ignored) { }
        try { strip(b, "Rock", Cfg.b(Cfg.EL_ROCK, Cfg.D_EL_ROCK), EarthLine.rockType, "Macrostrat has no unit here or was slow"); } catch (Throwable ignored) { }
        try { strip(b, "Power line / substation", Cfg.b(Cfg.EL_POWER, Cfg.D_EL_POWER), EarthLine.powerLine, "none mapped within the radius, or lookup failed"); } catch (Throwable ignored) { }
        try { strip(b, "Highest point / land", Cfg.b(Cfg.EL_LAND, Cfg.D_EL_LAND), EarthLine.peak, "no named peak found nearby"); } catch (Throwable ignored) { }
        try { strip(b, "Earthquakes", Cfg.b(Cfg.EL_QUAKE, Cfg.D_EL_QUAKE), EarthLine.quake, "not fetched yet"); } catch (Throwable ignored) { }
        try { strip(b, "District / place", Cfg.b(Cfg.EL_EARTH, Cfg.D_EL_EARTH), EarthLine.district, "no district name yet"); } catch (Throwable ignored) { }
        try {
            java.util.List<Faults.Hit> fh = Faults.nearest(lat, lon, 1);
            double lim = Cfg.i(Cfg.FAULT_KM, Cfg.D_FAULT_KM);
            strip(b, "Fault lines", Cfg.b(Cfg.FAULT, Cfg.D_FAULT), !fh.isEmpty() ? Faults.text(fh.get(0)) + (fh.get(0).km > lim ? " (beyond your " + Math.round(lim) + " km limit, shown as 'none within')" : "") : "", "fault data not loaded");
        } catch (Throwable ignored) { }
        try {
            Wetland.Hit w = Wetland.wetland;
            strip(b, "Wetland / lake", Cfg.b(Cfg.EL_WETLAND, Cfg.D_EL_WETLAND), w == null ? "" : w.text(), Wetland.why);
            Wetland.Hit rs = Wetland.ramsar;
            strip(b, "Ramsar site", Cfg.b(Cfg.EL_WETLAND, Cfg.D_EL_WETLAND), rs != null && rs.km <= 200 ? rs.text() : "", rs == null ? "no Ramsar site matched in OpenStreetMap (normal for most places)" : "nearest is over 200 km away");
        } catch (Throwable ignored) { }
        try {
            Tide.Now t = Tide.now(now);
            strip(b, "Tide", Cfg.b(Cfg.TIDE, Cfg.D_TIDE), t == null ? "" : (t.place == null ? "" : t.place + " ") + String.format(Locale.US, "%.1f m", t.height), "marine model has no data for the nearest coast yet");
            strip(b, "Sea distance (nearest beach)", Cfg.b(Cfg.TIDE, Cfg.D_TIDE), Tide.beachName == null ? "" : Tide.beachName + " " + Math.round(Tide.seaKm(lat, lon)) + " km", Tide.beachWhy);
        } catch (Throwable ignored) { }
    }
}
