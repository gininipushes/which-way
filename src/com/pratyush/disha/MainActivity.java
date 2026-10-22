package com.pratyush.disha;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Fully offline: places come from assets/places.csv (OpenStreetMap export),
 * position from the GPS chip, heading from the phone's compass sensors.
 * No internet permission is even requested.
 */
public class MainActivity extends Activity implements SensorEventListener, LocationListener, CompassView.Listener, PinStore.Listener {

    static final String PIN_KIND = "your pin", FRIEND_KIND = "friend pin";
    private List<List<Place>> base;
    private PinStore store;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());

    private CompassView view;
    private SensorManager sensors;
    private LocationManager locations;
    private GeomagneticField geo;
    private final float[] rot = new float[9];
    private final float[] remapped = new float[9];
    private final float[] orient = new float[3];
    private float[] grav, mag;
    private boolean haveRotationVector;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        store = PinStore.get(this);
        base = loadPlaces();
        view = new CompassView(this, merged());
        view.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        view.setListener(this);
        setContentView(view);
        sensors = (SensorManager) getSystemService(SENSOR_SERVICE);
        locations = (LocationManager) getSystemService(LOCATION_SERVICE);
    }

    private List<List<Place>> loadPlaces() {
        List<List<Place>> cats = new ArrayList<>();
        for (int i = 0; i < 3; i++) cats.add(new ArrayList<Place>());
        try (BufferedReader r = new BufferedReader(new InputStreamReader(getAssets().open("places.csv"), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] p = line.split("\t", 5);
                if (p.length < 4) continue;
                int c = Integer.parseInt(p[0]);
                cats.get(c).add(new Place(Double.parseDouble(p[1]), Double.parseDouble(p[2]), p[3], p.length > 4 ? p[4] : ""));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return cats;
    }

    @Override
    protected void onResume() {
        super.onResume();
        Sensor rv = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        haveRotationVector = rv != null;
        if (haveRotationVector) {
            sensors.registerListener(this, rv, SensorManager.SENSOR_DELAY_UI);
        } else {
            Sensor a = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            Sensor m = sensors.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
            if (a != null) sensors.registerListener(this, a, SensorManager.SENSOR_DELAY_UI);
            if (m != null) sensors.registerListener(this, m, SensorManager.SENSOR_DELAY_UI);
            if (a == null || m == null) view.setNoCompass(true);
        }
        startLocation();
        store.addListener(this);
        view.setPlaces(merged());
        if (store.syncEnabled() && SyncService.hasPermissions(this) && !SyncService.running) SyncService.start(this);
        ui.post(tick);
    }

    private final Runnable tick = new Runnable() {
        public void run() {
            boolean near = false;
            for (PinStore.Friend f : store.friends()) if (SyncService.nearby.containsKey(f.id)) near = true;
            view.setSyncState(SyncService.running, near);
            ui.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onPause() {
        super.onPause();
        store.removeListener(this);
        ui.removeCallbacks(tick);
        sensors.unregisterListener(this);
        try { locations.removeUpdates(this); } catch (SecurityException ignored) { }
    }

    private boolean hasPerm() {
        return Build.VERSION.SDK_INT < 23 ||
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void startLocation() {
        if (!hasPerm()) {
            view.setStatus("location permission needed");
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, 7);
            return;
        }
        try {
            Location best = null;
            for (String p : locations.getProviders(true)) {
                Location l = locations.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
            if (best != null) onLocationChanged(best);
            else view.setStatus("waiting for gps…");
            if (locations.isProviderEnabled(LocationManager.GPS_PROVIDER))
                locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 1, this);
            else view.setStatus("turn on location / gps");
            // network provider only helps if it exists; app works fine without it
            if (locations.getAllProviders().contains(LocationManager.NETWORK_PROVIDER)
                    && locations.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                locations.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000, 5, this);
        } catch (SecurityException e) {
            view.setStatus("location permission needed");
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code == 9) { afterBlePerms(); return; }
        if (hasPerm()) startLocation();
        else view.setStatus("location permission denied");
    }

    @Override
    public void onLocationChanged(Location l) {
        geo = new GeomagneticField((float) l.getLatitude(), (float) l.getLongitude(),
                (float) l.getAltitude(), System.currentTimeMillis());
        view.setLocation(l.getLatitude(), l.getLongitude(), l.getAccuracy());
    }

    @Override public void onProviderEnabled(String p) { startLocation(); }
    @Override public void onProviderDisabled(String p) { }
    @Override public void onStatusChanged(String p, int s, Bundle e) { }

    @Override
    public void onSensorChanged(SensorEvent e) {
        boolean ok = false;
        if (e.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rot, e.values);
            ok = true;
        } else {
            if (e.sensor.getType() == Sensor.TYPE_ACCELEROMETER) grav = lowPass(e.values.clone(), grav);
            if (e.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) mag = lowPass(e.values.clone(), mag);
            if (grav != null && mag != null) ok = SensorManager.getRotationMatrix(rot, null, grav, mag);
        }
        if (!ok) return;
        int r = getWindowManager().getDefaultDisplay().getRotation();
        float[] m = rot;
        if (r == Surface.ROTATION_90) { SensorManager.remapCoordinateSystem(rot, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, remapped); m = remapped; }
        else if (r == Surface.ROTATION_270) { SensorManager.remapCoordinateSystem(rot, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, remapped); m = remapped; }
        SensorManager.getOrientation(m, orient);
        float az = (float) Math.toDegrees(orient[0]);
        if (geo != null) az += geo.getDeclination(); // magnetic -> true north
        view.setHeading((az + 360f) % 360f);
    }

    private static float[] lowPass(float[] in, float[] out) {
        if (out == null) return in;
        for (int i = 0; i < in.length; i++) out[i] += 0.15f * (in[i] - out[i]);
        return out;
    }

    @Override
    public void onAccuracyChanged(Sensor s, int acc) {
        if (s.getType() == Sensor.TYPE_MAGNETIC_FIELD || s.getType() == Sensor.TYPE_ROTATION_VECTOR)
            view.setLowAccuracy(acc <= SensorManager.SENSOR_STATUS_ACCURACY_LOW);
    }

    // ------------------------------------------------------------ pins (own + synced from friends)
    private List<List<Place>> merged() {
        List<List<Place>> out = new ArrayList<>();
        for (int i = 0; i < 3; i++) out.add(new ArrayList<>(base.get(i)));
        String me = store.myId();
        for (PinStore.Pin p : store.livePins()) {
            boolean mine = me.equals(p.ownerId);
            Place pl = new Place(p.lat, p.lon, p.name, mine ? PIN_KIND : FRIEND_KIND);
            pl.pinId = p.id; pl.owner = p.owner;
            out.get(p.cat).add(pl);
        }
        return out;
    }

    @Override
    public void onPinsChanged() {
        ui.post(() -> view.setPlaces(merged()));
    }

    @Override
    public void onLongPress(final int cat, final Place sel, boolean hasFix, final double la, final double lo) {
        if (sel != null && sel.pinId != null) { confirmRemove(sel); return; }
        if (!hasFix) {
            android.widget.Toast.makeText(this, "wait for a gps fix to pin a spot", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        final android.widget.EditText in = new android.widget.EditText(this);
        in.setHint("name (optional)");
        in.setSingleLine(true);
        android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        int pad = dp(20);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(in);
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Pin this spot under " + CompassView.TABS[cat] + "?").setView(box)
            .setPositiveButton("Pin", (d, w) -> {
                String name = in.getText().toString().trim();
                if (name.isEmpty()) name = "my " + CompassView.TABS[cat].toLowerCase() + " spot";
                PinStore.Pin p = store.add(cat, la, lo, name);
                view.choose(cat, null);
            })
            .setNegativeButton("Cancel", null).show();
    }

    private void confirmRemove(final Place sel) {
        String who = PIN_KIND.equals(sel.kind) ? "" : "\npinned by " + sel.owner + ". Removing it removes it for friends too.";
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Remove pin?").setMessage(sel.name + who)
            .setPositiveButton("Remove", (d, w) -> store.delete(sel.pinId))
            .setNegativeButton("Keep", null).show();
    }

    // ------------------------------------------------------------ small ui kit (dark, minimal)
    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private android.widget.TextView text(String s, float sp, int color, String family) {
        android.widget.TextView t = new android.widget.TextView(this);
        t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        t.setTypeface(android.graphics.Typeface.create(family, android.graphics.Typeface.NORMAL));
        return t;
    }

    private android.app.Dialog sheet(String title, android.view.View body) {
        final android.app.Dialog d = new android.app.Dialog(this, android.R.style.Theme_Material_NoActionBar);
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0A0A0A);
        root.setPadding(dp(24), dp(28), dp(24), dp(12));
        android.widget.LinearLayout head = new android.widget.LinearLayout(this);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.widget.TextView t = text(title, 13, 0xFFF0F0F0, "sans-serif-medium");
        t.setLetterSpacing(0.25f);
        head.addView(t, new android.widget.LinearLayout.LayoutParams(0, -2, 1));
        android.widget.TextView close = text("CLOSE", 12, 0xFF6A6A6A, "sans-serif-medium");
        close.setLetterSpacing(0.2f); close.setPadding(dp(12), dp(8), 0, dp(8));
        close.setOnClickListener(v -> d.dismiss());
        head.addView(close);
        root.addView(head);
        root.addView(body, new android.widget.LinearLayout.LayoutParams(-1, 0, 1));
        d.setContentView(root);
        if (d.getWindow() != null) {
            d.getWindow().setStatusBarColor(0xFF0A0A0A);
            d.getWindow().setNavigationBarColor(0xFF0A0A0A);
        }
        return d;
    }

    // ------------------------------------------------------------ LIST: every place in the tab
    @Override
    public void onList(final int cat) {
        final List<Place> list;
        final boolean fromCampus = !view.hasFix();
        if (fromCampus) {
            list = new ArrayList<>(merged().get(cat));
            for (Place p : list) p.measureFrom(CompassView.CAMPUS_LAT, CompassView.CAMPUS_LON);
            java.util.Collections.sort(list, CompassView.BY_DIST);
        } else list = new ArrayList<>(view.sortedList(cat));
        final Place cur = view.selected(cat);
        final int accent = CompassView.ACCENT[cat];

        android.widget.LinearLayout body = new android.widget.LinearLayout(this);
        body.setOrientation(android.widget.LinearLayout.VERTICAL);
        android.widget.TextView sub = text(list.size() + " places · " + (fromCampus ? "distance from campus (no gps yet)" : "nearest first"),
                12, 0xFF5E5E5E, "sans-serif-light");
        sub.setPadding(0, dp(6), 0, dp(14));
        body.addView(sub);
        android.widget.ListView lv = new android.widget.ListView(this);
        lv.setDivider(new android.graphics.drawable.ColorDrawable(0xFF161616));
        lv.setDividerHeight(1);
        lv.setSelector(new android.graphics.drawable.ColorDrawable(0xFF141414));
        body.addView(lv, new android.widget.LinearLayout.LayoutParams(-1, -1));
        final android.app.Dialog d = sheet(CompassView.TABS[cat], body);

        lv.setAdapter(new android.widget.BaseAdapter() {
            public int getCount() { return list.size(); }
            public Object getItem(int i) { return list.get(i); }
            public long getItemId(int i) { return i; }
            public android.view.View getView(int i, android.view.View cv, android.view.ViewGroup parent) {
                Place p = list.get(i);
                android.widget.LinearLayout row = new android.widget.LinearLayout(MainActivity.this);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(14), 0, dp(14));
                android.widget.LinearLayout col = new android.widget.LinearLayout(MainActivity.this);
                col.setOrientation(android.widget.LinearLayout.VERTICAL);
                boolean isCur = !fromCampus && cur != null && p.key().equals(cur.key());
                android.widget.TextView n = text(p.name.isEmpty() ? "unnamed" : p.name, 16, isCur ? accent : 0xFFE6E6E6, "sans-serif-light");
                n.setSingleLine(true); n.setEllipsize(android.text.TextUtils.TruncateAt.END);
                String k = FRIEND_KIND.equals(p.kind) ? "pinned by " + p.owner : p.kind;
                android.widget.TextView m = text(k.toLowerCase(), 12, 0xFF5E5E5E, "sans-serif-light");
                col.addView(n); col.addView(m);
                row.addView(col, new android.widget.LinearLayout.LayoutParams(0, -2, 1));
                android.widget.TextView ds = text(Place.fmtDist(p.dist), 15, isCur ? accent : 0xFF9A9A9A, "sans-serif-light");
                ds.setPadding(dp(12), 0, 0, 0);
                row.addView(ds);
                return row;
            }
        });
        lv.setOnItemClickListener((a, v, i, id) -> {
            Place p = list.get(i);
            if (!fromCampus) view.choose(cat, i == 0 ? null : p);
            d.dismiss();
        });
        lv.setOnItemLongClickListener((a, v, i, id) -> {
            Place p = list.get(i);
            if (p.pinId == null) return false;
            d.dismiss();
            confirmRemove(p);
            return true;
        });
        d.show();
    }

    // ------------------------------------------------------------ FRIENDS + bluetooth sync
    private android.app.Dialog friendsDialog;
    private android.widget.LinearLayout friendsBody;
    private android.widget.TextView syncStatus, syncToggle;
    private String friendsSig = "";

    @Override
    public void onFriends() {
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        friendsBody = new android.widget.LinearLayout(this);
        friendsBody.setOrientation(android.widget.LinearLayout.VERTICAL);
        sv.addView(friendsBody);
        friendsDialog = sheet("FRIENDS", sv);
        friendsDialog.setOnDismissListener(x -> { friendsDialog = null; ui.removeCallbacks(friendsTick); });
        friendsSig = "";
        buildFriends();
        friendsDialog.show();
        ui.postDelayed(friendsTick, 1500);
    }

    private final Runnable friendsTick = new Runnable() {
        public void run() {
            if (friendsDialog == null) return;
            if (!sig().equals(friendsSig)) buildFriends();
            else if (syncStatus != null) syncStatus.setText(statusLine());
            ui.postDelayed(this, 1500);
        }
    };

    private String sig() {
        StringBuilder sb = new StringBuilder(store.myName()).append(store.syncEnabled()).append(SyncService.running);
        for (PinStore.Friend f : store.friends()) sb.append(f.id).append(f.name).append(f.lastSync / 60000);
        for (String id : new java.util.TreeSet<>(SyncService.nearby.keySet())) sb.append(id).append(SyncService.nearbyNames.get(id));
        return sb.toString();
    }

    private String statusLine() {
        if (!store.syncEnabled()) return "off · pins stay on this phone";
        return SyncService.status;
    }

    private android.widget.TextView label(String s) {
        android.widget.TextView t = text(s, 11, 0xFF5E5E5E, "sans-serif-medium");
        t.setLetterSpacing(0.2f);
        t.setPadding(0, dp(26), 0, dp(10));
        return t;
    }

    private static String ago(long t) {
        if (t == 0) return "never synced";
        long m = (System.currentTimeMillis() - t) / 60000;
        if (m < 1) return "synced just now";
        if (m < 60) return "synced " + m + " min ago";
        if (m < 48 * 60) return "synced " + (m / 60) + " h ago";
        return "synced " + (m / 1440) + " d ago";
    }

    private void buildFriends() {
        friendsSig = sig();
        android.widget.LinearLayout b = friendsBody;
        b.removeAllViews();

        b.addView(label("YOU"));
        android.widget.TextView name = text(store.myName() + "  ✎", 20, 0xFFEDEDED, "sans-serif-light");
        name.setOnClickListener(v -> rename());
        b.addView(name);
        android.widget.TextView code = text("code  " + PinStore.pretty(store.myId()), 14, 0xFF9A9A9A, "monospace");
        code.setPadding(0, dp(6), 0, 0);
        b.addView(code);
        b.addView(text("friends add you with this code, or by tapping you under nearby phones", 11, 0xFF4A4A4A, "sans-serif-light"));

        b.addView(label("BLUETOOTH SYNC"));
        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        syncStatus = text(statusLine(), 14, 0xFFBDBDBD, "sans-serif-light");
        row.addView(syncStatus, new android.widget.LinearLayout.LayoutParams(0, -2, 1));
        syncToggle = text(store.syncEnabled() ? "ON" : "OFF", 13, store.syncEnabled() ? 0xFF8FD3C9 : 0xFF6A6A6A, "sans-serif-medium");
        syncToggle.setLetterSpacing(0.2f);
        syncToggle.setPadding(dp(16), dp(10), 0, dp(10));
        syncToggle.setOnClickListener(v -> toggleSync());
        row.addView(syncToggle);
        b.addView(row);
        b.addView(text("runs in the background while on. pins hop between phones of friends who added each other, and onward through their friends.", 11, 0xFF4A4A4A, "sans-serif-light"));

        if (store.syncEnabled()) {
            List<String> strangers = new ArrayList<>();
            for (String id : SyncService.nearby.keySet()) if (!store.isFriend(id)) strangers.add(id);
            b.addView(label("NEARBY PHONES"));
            if (strangers.isEmpty()) b.addView(text("no other Which Way phones in range", 14, 0xFF4A4A4A, "sans-serif-light"));
            for (final String id : strangers) {
                android.widget.LinearLayout r = new android.widget.LinearLayout(this);
                r.setGravity(android.view.Gravity.CENTER_VERTICAL);
                r.setPadding(0, dp(8), 0, dp(8));
                r.addView(text(PinStore.pretty(id), 14, 0xFFBDBDBD, "monospace"), new android.widget.LinearLayout.LayoutParams(0, -2, 1));
                android.widget.TextView add = text("ADD", 12, 0xFF8FD3C9, "sans-serif-medium");
                add.setLetterSpacing(0.2f); add.setPadding(dp(16), dp(6), 0, dp(6));
                add.setOnClickListener(v -> addFriendDialog(id));
                r.addView(add);
                b.addView(r);
            }
        }

        b.addView(label("FRIENDS"));
        List<PinStore.Friend> fs = store.friends();
        if (fs.isEmpty()) b.addView(text("no friends yet. your own second phone counts too.", 14, 0xFF4A4A4A, "sans-serif-light"));
        for (final PinStore.Friend f : fs) {
            android.widget.LinearLayout r = new android.widget.LinearLayout(this);
            r.setOrientation(android.widget.LinearLayout.VERTICAL);
            r.setPadding(0, dp(10), 0, dp(10));
            boolean near = SyncService.nearby.containsKey(f.id);
            r.addView(text(f.name + (near ? "  ●" : ""), 16, near ? 0xFF8FD3C9 : 0xFFE6E6E6, "sans-serif-light"));
            r.addView(text(PinStore.pretty(f.id) + "   ·   " + ago(f.lastSync), 11, 0xFF5E5E5E, "sans-serif-light"));
            r.setOnLongClickListener(v -> {
                new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Remove " + f.name + "?").setMessage("Pins already synced stay.")
                    .setPositiveButton("Remove", (d, w) -> store.removeFriend(f.id))
                    .setNegativeButton("Cancel", null).show();
                return true;
            });
            b.addView(r);
        }
        android.widget.TextView add = text("+ ADD BY CODE", 12, 0xFF9A9A9A, "sans-serif-medium");
        add.setLetterSpacing(0.2f); add.setPadding(0, dp(18), 0, dp(24));
        add.setOnClickListener(v -> addFriendDialog(null));
        b.addView(add);
    }

    private void rename() {
        final android.widget.EditText in = new android.widget.EditText(this);
        in.setSingleLine(true); in.setText(store.myName());
        android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        box.setPadding(dp(20), dp(10), dp(20), 0); box.addView(in);
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Your name").setMessage("friends see this on pins you add").setView(box)
            .setPositiveButton("Save", (d, w) -> { String n = in.getText().toString().trim(); if (!n.isEmpty()) store.setMyName(n); buildFriends(); })
            .setNegativeButton("Cancel", null).show();
    }

    private void addFriendDialog(final String knownId) {
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(10), dp(20), 0);
        final android.widget.EditText code = new android.widget.EditText(this);
        code.setSingleLine(true); code.setHint("their code, e.g. A1B2-C3D4-E5F6");
        if (knownId != null) { code.setText(PinStore.pretty(knownId)); code.setEnabled(false); }
        final android.widget.EditText nm = new android.widget.EditText(this);
        nm.setSingleLine(true); nm.setHint("name");
        String guess = knownId == null ? null : SyncService.nearbyNames.get(knownId);
        if (guess != null) nm.setText(guess);
        box.addView(code); box.addView(nm);
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Add friend").setMessage("check the code matches their screen. they need to add you too.").setView(box)
            .setPositiveButton("Add", (d, w) -> {
                String id = PinStore.normalize(code.getText().toString());
                if (id.length() != 12) { android.widget.Toast.makeText(this, "codes have 12 characters", android.widget.Toast.LENGTH_SHORT).show(); return; }
                if (id.equals(store.myId())) { android.widget.Toast.makeText(this, "that's your own code", android.widget.Toast.LENGTH_SHORT).show(); return; }
                store.addFriend(id, nm.getText().toString().trim());
                if (!store.syncEnabled()) toggleSync();
                else buildFriends();
            })
            .setNegativeButton("Cancel", null).show();
    }

    private void toggleSync() {
        if (store.syncEnabled()) {
            store.setSyncEnabled(false);
            SyncService.stop(this);
            buildFriendsIfOpen();
            return;
        }
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            need.add(Manifest.permission.BLUETOOTH_SCAN);
            need.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            need.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS);
        List<String> missing = new ArrayList<>();
        for (String p : need) if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        if (!missing.isEmpty()) requestPermissions(missing.toArray(new String[0]), 9);
        else afterBlePerms();
    }

    @SuppressWarnings("MissingPermission")
    private void afterBlePerms() {
        if (!SyncService.hasPermissions(this)) {
            android.widget.Toast.makeText(this, "bluetooth permission is needed for sync", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        android.bluetooth.BluetoothManager bm = (android.bluetooth.BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        android.bluetooth.BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
        if (ad == null) {
            android.widget.Toast.makeText(this, "this phone has no bluetooth", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (!ad.isEnabled()) {
            try { startActivity(new android.content.Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)); } catch (Exception ignored) { }
        }
        store.setSyncEnabled(true);
        SyncService.start(this);
        buildFriendsIfOpen();
    }

    private void buildFriendsIfOpen() { if (friendsDialog != null) ui.postDelayed(this::buildFriends, 300); }
}
