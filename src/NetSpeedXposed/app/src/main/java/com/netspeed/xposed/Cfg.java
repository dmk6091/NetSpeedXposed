package com.netspeed.xposed;

import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;

import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.XposedBridge;

/**
 * Settings shared between the settings screen (module app) and System UI.
 * The app stores them and serves them through {@link SettingsProvider};
 * System UI reads them here and reloads whenever the app reports a change.
 */
final class Cfg {
    static final String AUTHORITY = "com.netspeed.xposed.settings";
    static final Uri URI = Uri.parse("content://" + AUTHORITY + "/all");

    // ---- keys and defaults (int values are dp / sp)
    static final String WEATHER_GAP = "weather_gap_dp"; static final int D_WEATHER_GAP = 2;
    static final String ROW_GAP = "row_gap_dp";        static final int D_ROW_GAP = 1;
    static final String ROW2_H = "row2_dp";            static final int D_ROW2_H = 20;
    static final String ROW2_END = "row2_end_dp";       static final int D_ROW2_END = 3;
    static final String EXTRA = "extra_dp";            static final int D_EXTRA = 0;
    static final String TEMP_SP = "temps_sp";          static final int D_TEMP_SP = 8;
    static final String WEATHER_SP = "weather_sp";     static final int D_WEATHER_SP = 9;
    static final String SPEED_SP = "speed_sp";         static final int D_SPEED_SP = 8;
    static final String BOLD = "bold_small";           static final boolean D_BOLD = true;
    static final String SHOW_CPU = "show_cpu";         static final boolean D_SHOW_CPU = true;
    static final String SHOW_BAT = "show_bat";         static final boolean D_SHOW_BAT = true;
    static final String SHOW_WEATHER = "show_weather"; static final boolean D_SHOW_WEATHER = true;
    static final String SHOW_POWER = "show_power";     static final boolean D_SHOW_POWER = true;
    static final String BATT_CELL = "batt_cell";       static final boolean D_BATT_CELL = false;
    static final String BATT_PCT_IN = "batt_pct_inside"; static final boolean D_BATT_PCT_IN = true;
    static final String BATT_TEXT = "batt_text";       static final boolean D_BATT_TEXT = true;
    static final String CLOCK_SECONDS = "clock_seconds"; static final boolean D_CLOCK_SECONDS = true;
    static final String AUTO_CONTRAST = "auto_contrast"; static final boolean D_AUTO_CONTRAST = true;
    static final String CAM_TEXT = "camera_text";      static final boolean D_CAM_TEXT = true;
    static final String DYN_COLOR = "dynamic_color";   static final boolean D_DYN_COLOR = false;
    static final String WX_DETAILS = "wx_details";     static final boolean D_WX_DETAILS = false;
    static final String CLOCK_INFO = "clock_info";     static final boolean D_CLOCK_INFO = true;
    static final String CLOCK_SP = "clock_sp_v2";      static final int D_CLOCK_SP = 10;
    static final String CLOCK_SUN = "clock_sun";       static final boolean D_CLOCK_SUN = true;
    static final String DATE_INFO = "date_info";       static final boolean D_DATE_INFO = false;
    static final String SPEED_GAUGE = "speed_gauge";   static final boolean D_SPEED_GAUGE = true;
    static final String SINR_MAST = "sinr_mast";       static final boolean D_SINR_MAST = true;
    static final String COMBINE_TEMPS = "combine_temps"; static final boolean D_COMBINE_TEMPS = true;
    static final String TOWER_ICON = "tower_icon";     static final boolean D_TOWER_ICON = true;
    static final String TOWER_PCT = "tower_pct";       static final int D_TOWER_PCT = 100;
    static final String SHOW_SNR = "show_snr";         static final boolean D_SHOW_SNR = false;
    static final String CELL_TABLE = "cell_table";     static final boolean D_CELL_TABLE = true;
    static final String TABLE_SP = "table_sp";         static final int D_TABLE_SP = 7;
    static final String MAST_PCT = "mast_pct";         static final int D_MAST_PCT = 140;
    static final String POWER_SP = "power_sp";         static final int D_POWER_SP = 8;
    static final String SHOW_BAND = "show_band";       static final boolean D_SHOW_BAND = true;
    static final String BAND_SP = "band_sp";           static final int D_BAND_SP = 9;
    static final String SHOW_DBM = "show_dbm";         static final boolean D_SHOW_DBM = true;
    static final String SHOW_DIST = "show_dist";       static final boolean D_SHOW_DIST = true;
    static final String TOWER_DB = "tower_db";         static final boolean D_TOWER_DB = true;
    static final String SHOW_CELLS = "show_cells";     static final boolean D_SHOW_CELLS = true;
    static final String VO_BADGE = "vo_badge";         static final boolean D_VO_BADGE = true;
    static final String NAV_IMMERSIVE = "nav_immersive"; static final boolean D_NAV_IMMERSIVE = false;
    static final String QUAKE_MIN = "quake_min_x10";   static final int D_QUAKE_MIN = 25;
    /** Earthquakes only within this distance of you, in 100 km steps; 200 (20,000 km) = anywhere. */
    static final String QUAKE_KM = "quake_km_x100";    static final int D_QUAKE_KM = 200;
    static final String PILL_DY = "pill_dy_dp";         static final int D_PILL_DY = 9;
    static final String APP_TOP_TRIM = "app_top_trim_dp"; static final int D_APP_TOP_TRIM = 0;
    static final String PREDICT = "app_suggestions";   static final boolean D_PREDICT = false;
    static final String NET_TYPE = "net_type_arrows";  static final boolean D_NET_TYPE = true;
    static final String ANIM_HEAT = "anim_heat";       static final boolean D_ANIM_HEAT = true;
    static final String ANIM_SPARK = "anim_spark";     static final boolean D_ANIM_SPARK = true;
    static final String ANIM_DIM = "anim_dim";         static final boolean D_ANIM_DIM = false;
    static final String NET_COLOR = "net_color";       static final boolean D_NET_COLOR = true;
    static final String ANIM_SHIMMER = "anim_shimmer"; static final boolean D_ANIM_SHIMMER = true;
    static final String ANIM_ARROWS = "anim_arrows";   static final boolean D_ANIM_ARROWS = true;
    static final String ANIM_SWITCH = "anim_switch";   static final boolean D_ANIM_SWITCH = true;
    static final String ANIM_CHARGE = "anim_charge";   static final boolean D_ANIM_CHARGE = true;
    static final String ANIM_BELL = "anim_bell";       static final boolean D_ANIM_BELL = true;
    static final String ANIM_PING = "anim_ping";       static final boolean D_ANIM_PING = true;
    /** Low heat: turns off the always-moving animations and the ping (they redraw the bar many times
     *  a second or keep the modem busy). One-shot effects (switch flash, bell) stay. */
    static final String SKY_MOTION = "sky_motion";     static final int D_SKY_MOTION = 1;   // 0 smooth, 1 slow, 2 still
    static final String FLASH = "flash_warn";          static final boolean D_FLASH = true;
    static final String FLASH_W = "flash_w";           static final int D_FLASH_W = 6;
    static final String FLASH_BAT = "flash_bat";       static final int D_FLASH_BAT = 42;
    static final String FLASH_CPU = "flash_cpu";       static final int D_FLASH_CPU = 70;
    static final String FLASH_SEC = "flash_sec";       static final int D_FLASH_SEC = 1;   // 0 = 10 s, 1 = 30 s, 2 = 60 s
    static final String LOW_HEAT = "low_heat";         static final boolean D_LOW_HEAT = true;
    static final String LEARN = "learn_towers";        static final boolean D_LEARN = true;
    /** While moving, one GPS fix a minute for tower learning. */
    static final String RIDE_GPS = "ride_gps";         static final boolean D_RIDE_GPS = true;
    /** Weak 5G -> 4G for a while, until this much 4G data is used today (MB). */
    static final String NR_FALLBACK = "nr_fallback";   static final boolean D_NR_FALLBACK = true;
    static final String INTEGRITY_NOTIFY = "integrity_notify"; static final boolean D_INTEGRITY_NOTIFY = true;
    static final String BG_MAP = "bg_map";             static final boolean D_BG_MAP = true;
    static final String WX_FX = "wx_fx";               static final boolean D_WX_FX = true;
    static final String WX_SKY = "wx_sky_words";       static final boolean D_WX_SKY = true;   // row-4 frames: cloud type + base, cloud drift, wind kind
    static final String FX_DEV = "fx_dev";             static final boolean D_FX_DEV = true;
    static final String HAZARDS = "hazards";           static final boolean D_HAZARDS = true;
    static final String EL_RAIN = "el_rain";   static final boolean D_EL_RAIN = true;     // rotating line: rain and soil
    static final String EL_LAND = "el_land";   static final boolean D_EL_LAND = true;     // slope, nearest peak
    static final String EL_QUAKE = "el_quake"; static final boolean D_EL_QUAKE = true;    // last quake nearby
    static final String EL_EARTH = "el_earth"; static final boolean D_EL_EARTH = true;    // ground temperature, sun height, district
    static final String EL_ROCK = "el_rock";   static final boolean D_EL_ROCK = true;     // bedrock type (Macrostrat)
    static final String EL_SOIL = "el_soil";   static final boolean D_EL_SOIL = true;     // soil texture (SoilGrids)
    static final String EL_HABITAT = "el_habitat"; static final boolean D_EL_HABITAT = true;   // ecoregion, land cover and plants
    static final String EL_WETLAND = "el_wetland"; static final boolean D_EL_WETLAND = true;   // nearest wetland and Ramsar site
    static final String EL_POWER = "el_power"; static final boolean D_EL_POWER = true;   // nearest power line, substation
    static final String EL_SPEED = "el_speed"; static final int D_EL_SPEED = 5;            // seconds per frame
    static final String SPEED_STOCK = "speed_stock"; static final boolean D_SPEED_STOCK = true;  // speed readout when the 4-row bar is off
    static final String SH_HEALTH = "sh_health"; static final boolean D_SH_HEALTH = true;
    static final String SH_RAM = "sh_ram"; static final boolean D_SH_RAM = true;
    static final String SH_ALARM = "sh_alarm"; static final boolean D_SH_ALARM = true;
    static final String SH_NOTIF = "sh_notif"; static final boolean D_SH_NOTIF = true;
    static final String SH_BTBAT = "sh_btbat"; static final boolean D_SH_BTBAT = true;
    static final String SH_SLEEP = "sh_sleep"; static final boolean D_SH_SLEEP = true;
    static final String SH_LOAD = "sh_load"; static final boolean D_SH_LOAD = false;
    static final String SH_GHZ = "sh_ghz"; static final boolean D_SH_GHZ = true;
    static final String SH_DATA = "sh_data"; static final boolean D_SH_DATA = true;
    static final String EL_ORDER = "el_order"; static final int D_EL_ORDER = 0;
    static final String EL_DAM_KM = "el_dam_km"; static final int D_EL_DAM_KM = 10;
    static final String EL_POWER_KM = "el_power_km"; static final int D_EL_POWER_KM = 5;
    static final String EL_PEAK_KM = "el_peak_km"; static final int D_EL_PEAK_KM = 30;
    static final String RIVER = "river_flow";          static final boolean D_RIVER = true;   // river flow on the place line
    static final String BANDS1 = "bands_1";             static final String BANDS2 = "bands_2";
    static final String RELIEFWEB_APP = "reliefweb_app";
    static final String ZONES = "zones";               static final boolean D_ZONES = true;
    static final String NEWBAR = "new_bar";          static final boolean D_NEWBAR = true;
    static final String FAULT = "fault_frame";         static final boolean D_FAULT = true;
    static final String FAULT_KM = "fault_km";         static final int D_FAULT_KM = 300;
    static final String DAM = "dam_watch";             static final boolean D_DAM = true;
    static final String SKY_NAV = "sky_nav";         static final boolean D_SKY_NAV = true;
    static final String SKY_PEOPLE = "sky_people";   static final boolean D_SKY_PEOPLE = true;
    static final String SKY_STAR = "sky_star";       static final boolean D_SKY_STAR = true;
    static final String SKY_PAIR = "sky_pair";       static final boolean D_SKY_PAIR = true;
    static final String SKY_AST = "sky_ast";         static final boolean D_SKY_AST = true;
    static final String SKY_SUN = "sky_sun";         static final boolean D_SKY_SUN = true;
    static final String LOCK_BAR = "lock_bar";       static final boolean D_LOCK_BAR = true;
    static final String SKY_SPEED = "sky_speed"; static final int D_SKY_SPEED = 5;   // seconds per frame, satellite strip
    static final String SKY_LAUNCH = "sky_launch";   static final boolean D_SKY_LAUNCH = true;
    static final String LCH_DAYS = "lch_days";       static final int D_LCH_DAYS = 7;
    static final String LCH_NAME = "lch_name";       static final int D_LCH_NAME = 0;      // 0 mission, 1 rocket, 2 both
    static final String LCH_AGENCY = "lch_agency";   static final boolean D_LCH_AGENCY = false;
    static final String LCH_COUNTRY = "lch_country"; static final boolean D_LCH_COUNTRY = true;
    static final String LCH_SITE = "lch_site";       static final boolean D_LCH_SITE = true;
    static final String LCH_PAD = "lch_pad";         static final boolean D_LCH_PAD = false;
    static final String LCH_TIME = "lch_time";       static final int D_LCH_TIME = 0;      // 0 countdown, 1 date and time
    static final String LCH_UTC = "lch_utc";         static final boolean D_LCH_UTC = false;
    static final String LCH_REGION = "lch_region";   static final int D_LCH_REGION = 0;    // 0 all, 1 India, 2 USA, 3 China, 4 Europe, 5 Russia, 6 Japan
    static final String LCH_LIVE = "lch_live";       static final boolean D_LCH_LIVE = true;
    static final String SKY_VOY = "sky_voy";         static final boolean D_SKY_VOY = true;
    static final String DAM_NOTIF = "dam_notif";       static final boolean D_DAM_NOTIF = true;
    static final String SEA_FRAMES = "sea_frames";     static final boolean D_SEA_FRAMES = true;   // tide slot rotates: tide, waves, sea state, current
    static final String REFRESH_REQ = "refresh_req";   static final int D_REFRESH_REQ = 0;   // "Refresh data now" button: counts up
    static final String EARTH_SLOT = "earth_slot";     static final boolean D_EARTH_SLOT = true;  // row 4, right of the disaster item: plate, nearest plate boundary, El Nino
    static final String SEA_ROW3 = "sea_row3";         static final boolean D_SEA_ROW3 = true;    // row 3: tide, sea temperature, waves, sea state and current side by side; the disaster item goes to row 4
    static final String WX_ROW4 = "wx_row4";           static final boolean D_WX_ROW4 = true;     // weather on row 4; tide and the disaster item on row 3
    static final String TIDE = "tide";                 static final boolean D_TIDE = true;
    static final String DIM_NIGHT = "dim_night";       static final boolean D_DIM_NIGHT = true;
    static final String DATA_PERIOD = "data_period";   static final int D_DATA_PERIOD = 0;  // 0 daily (D), 1 monthly (M), 2 both at once, 3 D and M taking turns every 5 s
    static final String Q_BOX = "q_box";               static final boolean D_Q_BOX = true;      // signal quality: the Q in a small box
    static final String SEA_MOON = "sea_moon"; static final boolean D_SEA_MOON = true;   // day type + moon phase name after the sea line (when the moon band above the SIM boxes is off)
    static final String SIM_UP = "sim_up"; static final boolean D_SIM_UP = true;   // SIM boxes move up; the moon-phase / time-name line above them is dropped
    static final String Q_SECOND = "q_second"; static final boolean D_Q_SECOND = true;   // signal quality Q also for the SIM that is not carrying data (folded layouts)
    static final String TC_SITE = "tc_site";   static final boolean D_TC_SITE = false;   // tower column: site and sector
    static final String TC_BW = "tc_bw";       static final boolean D_TC_BW = true;     // tower column: channel width
    static final String TC_NB = "tc_nb";       static final boolean D_TC_NB = true;     // tower column: strongest neighbour
    static final String TC_TINT = "tc_tint";   static final boolean D_TC_TINT = true;   // tint behind each SIM box
    static final String TOWER_MAST = "tower_mast";    static final boolean D_TOWER_MAST = true;   // tower mast instead of the satellite dish before the distance to the tower
    static final String DATA_LABELS = "data_labels";   static final boolean D_DATA_LABELS = true; // "4G" / "5G" labels in the data box (off: colour only)
    static final String COAST_MODE = "coast_mode";   static final int D_COAST_MODE = 1;   // 0 nearest water (creeks too), 1 open sea
    static final String TIDE_BY = "tide_by";           static final int D_TIDE_BY = 0;     // 0 nearest tide point, 1 nearest beach (named)
    static final String TIDE_PLACE = "tide_place";     static final int D_TIDE_PLACE = 0;  // 0 nearest, n = place n
    static final String CORNER = "pro_corner";         static final int D_CORNER = 1;   // 0 off, 1 5G share, 2 quality
    static final String LAYOUT = "bar_layout";         static final int D_LAYOUT = 0;   // 0 classic (data / Q column at the far right), 1 folded (data / Q under the data SIM box)
    static final String INTEGRITY_TOAST = "integrity_toast"; static final boolean D_INTEGRITY_TOAST = true;
    static final String QTI_NR = "qti_nr";             static final boolean D_QTI_NR = false;
    static final String NR_MODE = "nr_mode";           static final int D_NR_MODE = 0;            // 0 automatic, 1 always allow 5G, 2 force 4G
    static final String NR_NETCHECK = "nr_netcheck";   static final boolean D_NR_NETCHECK = true;
    static final String AUTO_SIM = "auto_sim";         static final boolean D_AUTO_SIM = false;
    static final String NR_QUOTA_MB = "nr_quota_mb";   static final int D_NR_QUOTA_MB = 1500;
    /** OpenCelliD access token (pk.…), stored only on the phone. Empty = not used. */
    static final String OCID_KEY = "ocid_key";
    static final String WAPI_KEY = "wapi_key";
    /** Custom operator name per SIM (empty = real name). */
    static final String OP_NAME1 = "op_name_1";
    static final String OP_NAME2 = "op_name_2";
    /** Network's own name at home ("Jio True5G"), "SIM · network" in another state. */
    static final String SMART_CARRIER = "smart_carrier"; static final boolean D_SMART_CARRIER = true;
    /** Apps, System UI and Settings see "roaming" while the SIM and tower circles differ (the phone service itself is untouched). */
    static final String CIRCLE_ROAM = "circle_roam";   static final boolean D_CIRCLE_ROAM = false;
    static final String HIDE_IDLE = "hide_idle";       static final boolean D_HIDE_IDLE = false;
    static final String NIGHT = "night_sky";           static final boolean D_NIGHT = true;
    static final String SKY_LOW = "sky_low";          static final int D_SKY_LOW = 20;
    static final String HEAT = "heat_warn";           static final boolean D_HEAT = true;
    static final String HEAT_BAT = "heat_bat";        static final int D_HEAT_BAT = 44;
    static final String HEAT_CPU = "heat_cpu";        static final int D_HEAT_CPU = 85;
    static final String THR = "heat_throttle";        static final boolean D_THR = true;
    static final String THR_BAT = "thr_bat";          static final int D_THR_BAT = 42;
    static final String THR_CPU = "thr_cpu";          static final int D_THR_CPU = 75;
    static final String THR_PCT = "thr_pct";          static final int D_THR_PCT = 60;
    static final String THR_GPU = "thr_gpu";          static final int D_THR_GPU = 55;
    static final String THR_DIM = "thr_dim";          static final boolean D_THR_DIM = false;
    static final String THR_DIM_PCT = "thr_dim_pct";  static final int D_THR_DIM_PCT = 60;
    static final String SKY = "sky";                  static final boolean D_SKY = true;
    static final String SKY_OPACITY = "sky_opacity";  static final int D_SKY_OPACITY = 80;
    static final String SKY_FADE = "sky_fade";        static final boolean D_SKY_FADE = true;
    static final String STATS_COL = "stats_col";       static final boolean D_STATS_COL = true;
    static final String QS_BATT_HIDE = "qs_batt_hide"; static final boolean D_QS_BATT_HIDE = false;
    /** Pixel-style lock screen exit: the lock screen grows and fades while the launcher scales in. */
    static final String UNLOCK_ANIM = "unlock_anim";   static final boolean D_UNLOCK_ANIM = false;
    static final String LOCK_GLOBE = "lock_globe";     static final boolean D_LOCK_GLOBE = true;
    static final String GLOBE_SPIN = "globe_spin";     static final boolean D_GLOBE_SPIN = true;
    static final String READABLE = "readable_bar";     static final boolean D_READABLE = false;
    static final String PRO = "pro_bar";              static final boolean D_PRO = true;
    static final String LOOK2 = "look_gauge";         static final boolean D_LOOK2 = true;
    static final String CHIPS = "chips_look";         static final boolean D_CHIPS = false;
    static final String PRO_EXTRA = "pro_extra_dp";   static final int D_PRO_EXTRA = 11;
    static final String E2E = "force_e2e";            static final boolean D_E2E = true;
    static final String SYS_ICONS = "own_row2_icons"; static final boolean D_SYS_ICONS = true;
    static final String BATT_PORTRAIT = "batt_portrait"; static final boolean D_BATT_PORTRAIT = true;
    static final String DUAL = "dual_row";             static final boolean D_DUAL = true;
    /** Hide the big side "R" next to the signal bars (the small raised R stays). */
    static final String HIDE_SIDE_R = "hide_side_r";   static final boolean D_HIDE_SIDE_R = true;
    static final String SPEED_LEFT = "speed_after_weather"; static final boolean D_SPEED_LEFT = true;

    private static final String TAG = "NetSpeedXposed: [cfg] ";
    private static volatile Map<String, String> sValues = new HashMap<>();
    private static volatile int sVersion = 0;
    private static boolean sInit = false;

    private Cfg() {}

    private static Handler sBg;
    private static Context sApp;
    private static volatile boolean sLoaded = false;
    private static volatile long sLastTry = 0;

    /**
     * Starts loading settings in the background and follows changes. Never blocks the
     * calling thread (System UI's main thread). Safe to call repeatedly.
     */
    static synchronized void init(Context ctx) {
        if (sInit) return;
        sInit = true;
        final Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        sApp = app;
        try {
            HandlerThread t = new HandlerThread("netspeed-cfg");
            t.start();
            sBg = new Handler(t.getLooper());
            sBg.post(() -> load(app));
            app.getContentResolver().registerContentObserver(URI, true,
                    new ContentObserver(sBg) {
                        @Override
                        public void onChange(boolean selfChange) {
                            load(app);
                        }
                    });
        } catch (Throwable th) {
            XposedBridge.log(TAG + "observer failed: " + th);
        }
    }

    /** Retries loading every few seconds until it has worked once (e.g. right after boot). */
    static void retryIfNeeded() {
        if (sLoaded || sBg == null || sApp == null) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - sLastTry < 5000) return;
        sLastTry = now;
        sBg.post(() -> load(sApp));
    }

    private static void load(Context ctx) {
        sLastTry = android.os.SystemClock.elapsedRealtime();
        Map<String, String> m = new HashMap<>();
        try (Cursor c = ctx.getContentResolver().query(URI, null, null, null, null)) {
            if (c == null) {
                XposedBridge.log(TAG + "settings app not reachable yet, using defaults");
                return;
            }
            while (c.moveToNext()) m.put(c.getString(0), c.getString(1));
            sLoaded = true;
            sValues = m;
            sVersion++;
            XposedBridge.log(TAG + "loaded " + m.size() + " settings");
        } catch (Throwable th) {
            XposedBridge.log(TAG + "load failed (using defaults): " + th);
        }
    }

    /** Increases every time settings are reloaded. */
    static int version() {
        return sVersion;
    }

    static int i(String key, int def) {
        String v = sValues.get(key);
        if (v == null) return def;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    static String s(String key) {
        String v = sValues.get(key);
        return v == null ? "" : v.trim();
    }

    static boolean b(String key, boolean def) {
        if (key != LOW_HEAT && (key == ANIM_HEAT || key == ANIM_SHIMMER || key == ANIM_ARROWS
                || key == ANIM_CHARGE || key == ANIM_PING) && b(LOW_HEAT, D_LOW_HEAT)) return false;
        String v = sValues.get(key);
        return v == null ? def : Boolean.parseBoolean(v);
    }
}
