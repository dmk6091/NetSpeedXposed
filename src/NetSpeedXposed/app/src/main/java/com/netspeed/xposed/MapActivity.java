package com.netspeed.xposed;

import android.app.Activity;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.Locale;

/** Live map: street or relief base, bedrock (Macrostrat) and fault lines on top, your position, tap anywhere for rock and soil. GPS runs only while this screen is open. */
public class MapActivity extends Activity implements LocationListener {
    private WebView web;
    private LocationManager lm;
    private boolean pageReady;
    private Location last;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this);
        setContentView(web);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String u) { pageReady = true; if (last != null) push(last); }
        });
        web.loadDataWithBaseURL("https://fieldatlas.local/", PAGE, "text/html", "UTF-8", null);
        lm = getSystemService(LocationManager.class);
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION}, 7);
                return;
            }
            for (String p : new String[]{LocationManager.PASSIVE_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER}) {
                try {
                    Location x = lm.getLastKnownLocation(p);
                    if (x != null && (last == null || x.getTime() > last.getTime())) last = x;
                } catch (Throwable ignored) { }
            }
            if (last != null && pageReady) push(last);
            for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                try { lm.requestLocationUpdates(p, 5000, 10, this); } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { lm.removeUpdates(this); } catch (Throwable ignored) { }   // no GPS when the screen is not in front
    }

    @Override
    public void onRequestPermissionsResult(int rc, String[] p, int[] r) { onResume(); }

    @Override
    public void onLocationChanged(Location l) { last = l; if (pageReady) push(l); }
    @Override public void onProviderEnabled(String p) { }
    @Override public void onProviderDisabled(String p) { }
    @Override public void onStatusChanged(String p, int s, Bundle e) { }

    private void push(Location l) {
        web.evaluateJavascript(String.format(Locale.US, "setPos(%.6f,%.6f,%.0f)", l.getLatitude(), l.getLongitude(), l.getAccuracy()), null);
        try {
            getSharedPreferences(DamJob.PREFS, Context.MODE_PRIVATE).edit().putFloat("lat", (float) l.getLatitude())
                    .putFloat("lon", (float) l.getLongitude()).putLong("fixAt", System.currentTimeMillis()).apply();
        } catch (Throwable ignored) { }
    }

    private class Bridge {
        @JavascriptInterface
        public String faults(double lat, double lon) {
            StringBuilder sb = new StringBuilder("[");
            try {
                boolean first = true;
                for (float[][] l : Faults.linesIn(lat, lon, 120)) {
                    if (!first) sb.append(',');
                    first = false;
                    sb.append('[');
                    for (int i = 0; i < l[0].length; i++) {
                        if (i > 0) sb.append(',');
                        sb.append(String.format(Locale.US, "[%.4f,%.4f]", l[1][i], l[0][i]));
                    }
                    sb.append(']');
                }
            } catch (Throwable ignored) { }
            return sb.append(']').toString();
        }

        @JavascriptInterface
        public void query(final double lat, final double lon) {
            new Thread(() -> {
                String rock = Geo.rockText(lat, lon), soil = Geo.soilText(lat, lon), fault = "";
                try {
                    java.util.List<Faults.Hit> hs = Faults.nearest(lat, lon, 1);
                    if (!hs.isEmpty()) fault = hs.get(0).label() + " · " + Math.round(hs.get(0).km) + " km " + hs.get(0).dir;
                } catch (Throwable ignored) { }
                final String js = "showInfo(" + q(lat) + "," + q(lon) + "," + jq(rock) + "," + jq(soil) + "," + jq(fault) + ")";
                runOnUiThread(() -> web.evaluateJavascript(js, null));
            }).start();
        }

        private String q(double d) { return String.format(Locale.US, "%.5f", d); }
        private String jq(String s) { return org.json.JSONObject.quote(s == null ? "" : s); }
    }

    private static final String PAGE = "<!doctype html><html><head><meta name=viewport content='width=device-width,initial-scale=1,maximum-scale=1'>"
            + "<link rel=stylesheet href='https://unpkg.com/leaflet@1.9.4/dist/leaflet.css'>"
            + "<script src='https://unpkg.com/leaflet@1.9.4/dist/leaflet.js'></script>"
            + "<style>html,body,#m{height:100%;margin:0;background:#1b1f23}"
            + "#bar{position:absolute;z-index:999;top:10px;left:10px;display:flex;gap:6px;flex-wrap:wrap}"
            + "#bar button{font:600 13px sans-serif;border:0;border-radius:18px;padding:8px 13px;background:rgba(20,26,32,.88);color:#e8edf2}"
            + "#bar button.on{background:#2f7fd1;color:#fff}"
            + "#card{position:absolute;z-index:999;left:10px;right:10px;bottom:10px;background:rgba(10,14,18,.92);color:#e4eaf0;font:14px sans-serif;border-radius:14px;padding:12px 14px;display:none}"
            + "#card b{color:#fff;font-size:15px}#card .k{color:#96a5b4;display:inline-block;width:52px}#card .f{color:#ff8c78}</style></head><body>"
            + "<div id=m></div><div id=bar></div><div id=card></div>"
            + "<script>"
            + "var map=L.map('m',{zoomControl:false}).setView([18.62,73.80],14);"
            + "var osm=L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'© OpenStreetMap'});"
            + "var topo=L.tileLayer('https://tile.opentopomap.org/{z}/{x}/{y}.png',{maxZoom:17,attribution:'© OpenTopoMap'});"
            + "var geo=L.tileLayer('https://tiles.macrostrat.org/carto/{z}/{x}/{y}.png',{maxZoom:15,opacity:.6,attribution:'Macrostrat'});"
            + "var flt=L.layerGroup(),base=osm,pos=null,acc=null,pin=null,first=true,follow=true,fx=null;osm.addTo(map);flt.addTo(map);"
            + "function btn(id,t,fn){var b=document.createElement('button');b.id=id;b.textContent=t;b.onclick=function(){fn(b)};document.getElementById('bar').appendChild(b);return b}"
            + "btn('b1','Street',function(b){sw(osm)}).className='on';btn('b2','Relief',function(b){sw(topo)});"
            + "btn('b3','Rock',function(b){if(map.hasLayer(geo)){map.removeLayer(geo);b.className=''}else{geo.addTo(map);b.className='on'}});"
            + "btn('b4','Faults',function(b){if(map.hasLayer(flt)){map.removeLayer(flt);b.className=''}else{flt.addTo(map);b.className='on'}}).className='on';"
            + "btn('b5','Me',function(b){follow=true;if(pos)map.setView(pos.getLatLng(),Math.max(map.getZoom(),14))});"
            + "function sw(l){if(l==base)return;map.removeLayer(base);base=l;l.addTo(map);l.bringToBack();document.getElementById('b1').className=l==osm?'on':'';document.getElementById('b2').className=l==topo?'on':''}"
            + "function drawFaults(lat,lon){flt.clearLayers();try{var a=JSON.parse(Android.faults(lat,lon));for(var i=0;i<a.length;i++)L.polyline(a[i],{color:'#ff5046',weight:3,opacity:.9}).addTo(flt)}catch(e){}}"
            + "function setPos(lat,lon,a){var ll=[lat,lon];if(!pos){pos=L.circleMarker(ll,{radius:8,color:'#fff',weight:3,fillColor:'#2f7fd1',fillOpacity:1}).addTo(map);acc=L.circle(ll,{radius:a,color:'#2f7fd1',weight:1,fillOpacity:.12}).addTo(map)}else{pos.setLatLng(ll);acc.setLatLng(ll);acc.setRadius(a)}"
            + "if(first||follow){map.setView(ll,first?14:map.getZoom());first=false}"
            + "if(!fx||map.distance(fx,ll)>30000){fx=ll;drawFaults(lat,lon)}}"
            + "map.on('dragstart',function(){follow=false});"
            + "map.on('click',function(e){if(pin)map.removeLayer(pin);pin=L.circleMarker(e.latlng,{radius:6,color:'#fff',weight:2,fillColor:'#f5a623',fillOpacity:1}).addTo(map);"
            + "var c=document.getElementById('card');c.style.display='block';c.innerHTML='<b>Looking up…</b><br>rock and soil servers can take 10–30 s';Android.query(e.latlng.lat,e.latlng.lng)});"
            + "function showInfo(lat,lon,rock,soil,fault){var c=document.getElementById('card');"
            + "c.innerHTML='<b>'+lat.toFixed(4)+', '+lon.toFixed(4)+'</b><br><span class=k>Rock</span>'+(rock||'no data')+'<br><span class=k>Soil</span>'+(soil||'no data (built-up land or server busy)')+'<br><span class=k>Fault</span><span class=f>'+(fault||'-')+'</span>'}"
            + "</script></body></html>";
}
