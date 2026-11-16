package com.netspeed.xposed;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import java.util.Map;

/** Read-only view of the module's settings, so System UI can read them. */
public class SettingsProvider extends ContentProvider {
    static final String PREFS = "settings";

    /** Device-protected storage, so settings are readable right after boot, before unlock. */
    static SharedPreferences prefs(android.content.Context ctx) {
        return ctx.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, 0);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    /** Callers that may read the token: System UI, Settings, this app (null = same process). */
    private boolean trusted() {
        String p = getCallingPackage();
        return p == null || p.equals("com.android.systemui") || p.equals("com.android.settings")
                || p.equals("android") || p.equals(getContext().getPackageName());
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        MatrixCursor c = new MatrixCursor(new String[]{"key", "value"});
        SharedPreferences sp = prefs(getContext());
        boolean ok = trusted();
        for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
            if (!ok && (Cfg.OCID_KEY.equals(e.getKey()) || Cfg.WAPI_KEY.equals(e.getKey()))) continue;   // keys stay private
            c.addRow(new Object[]{e.getKey(), String.valueOf(e.getValue())});
        }
        return c;
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues v) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
