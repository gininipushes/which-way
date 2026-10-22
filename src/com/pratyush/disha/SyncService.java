package com.pratyush.disha;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bluetooth LE "mesh" sync. Every phone running it both advertises (with its 6-byte Disha id)
 * and scans. When it sees a friend it connects and pulls that friend's full pin set over GATT.
 * Because each phone shares every pin it holds, pins hop across friends-of-friends (gossip).
 * Both sides must have added each other: a phone only serves pins to its friends, and only
 * accepts pins from its friends. No internet, no pairing dialog.
 */
public class SyncService extends Service {

    static final UUID SERVICE = UUID.fromString("6d3f1a20-4b7e-4c1e-9a55-d15000000001");
    static final UUID HELLO = UUID.fromString("6d3f1a20-4b7e-4c1e-9a55-d1500000a001");
    static final UUID NAME = UUID.fromString("6d3f1a20-4b7e-4c1e-9a55-d1500000a002");
    static final UUID DATA = UUID.fromString("6d3f1a20-4b7e-4c1e-9a55-d1500000a003");
    static final int CHUNK = 480;
    static final long RESYNC_MS = 90_000, NEARBY_MS = 60_000, TIMEOUT_MS = 25_000;

    // read by the UI
    static volatile boolean running;
    static volatile String status = "off";
    static final Map<String, Long> nearby = new ConcurrentHashMap<>();   // id -> last seen
    static final Map<String, String> nearbyNames = new ConcurrentHashMap<>();

    private PinStore store;
    private BluetoothManager bm;
    private BluetoothAdapter adapter;
    private BluetoothGattServer server;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothLeScanner scanner;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Map<String, Long> lastAttempt = new HashMap<>();
    private final ArrayDeque<BluetoothDevice> queue = new ArrayDeque<>();
    private final Map<BluetoothDevice, String> queuedIds = new HashMap<>();
    private final Map<String, ServerSession> sessions = new ConcurrentHashMap<>();
    private BluetoothGatt active;
    private String activeId;

    static void start(Context c) {
        Intent i = new Intent(c, SyncService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    static void stop(Context c) { c.stopService(new Intent(c, SyncService.class)); }

    static boolean hasPermissions(Context c) {
        String[] need = Build.VERSION.SDK_INT >= 31
                ? new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT}
                : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        for (String p : need) if (c.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground();
        if (!running) {
            running = true;
            store = PinStore.get(this);
            bootBle();
            h.postDelayed(watchdog, 15_000);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        h.removeCallbacksAndMessages(null);
        teardownBle();
        status = "off";
        nearby.clear();
        super.onDestroy();
    }

    private void goForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel("sync", "Nearby sync", NotificationManager.IMPORTANCE_MIN));
            b = new Notification.Builder(this, "sync");
        } else b = new Notification.Builder(this);
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        b.setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("Which Way?")
                .setContentText("syncing pins with friends nearby")
                .setContentIntent(pi).setOngoing(true);
        Notification n = b.build();
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            else startForeground(1, n);
        } catch (Exception e) {
            status = "bluetooth permission needed";
            stopSelf();
        }
    }

    // ------------------------------------------------------------------ lifecycle of the radio parts
    private final Runnable watchdog = new Runnable() {
        public void run() {
            if (!running) return;
            boolean on = adapter != null && adapter.isEnabled();
            if (!on) { teardownBle(); status = "bluetooth is off"; }
            else if (server == null || scanner == null) bootBle();
            long now = System.currentTimeMillis();
            for (Map.Entry<String, Long> e : nearby.entrySet())
                if (now - e.getValue() > NEARBY_MS) nearby.remove(e.getKey());
            h.postDelayed(this, 15_000);
        }
    };

    private final Runnable restartScan = new Runnable() {
        public void run() {
            if (!running || scanner == null) return;
            try { scanner.stopScan(scanCb); } catch (Exception ignored) { }
            startScan();
            h.postDelayed(this, 10 * 60_000);
        }
    };

    @SuppressWarnings("MissingPermission")
    private void bootBle() {
        if (!hasPermissions(this)) { status = "bluetooth permission needed"; return; }
        bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        adapter = bm == null ? null : bm.getAdapter();
        if (adapter == null) { status = "no bluetooth on this phone"; return; }
        if (!adapter.isEnabled()) { status = "bluetooth is off"; return; }
        try {
            openServer();
            advertise();
            scanner = adapter.getBluetoothLeScanner();
            startScan();
            h.removeCallbacks(restartScan);
            h.postDelayed(restartScan, 10 * 60_000);
            status = "looking for friends";
        } catch (Exception e) {
            status = "bluetooth error: " + e.getClass().getSimpleName();
            teardownBle();
        }
    }

    @SuppressWarnings("MissingPermission")
    private void teardownBle() {
        try { if (scanner != null) scanner.stopScan(scanCb); } catch (Exception ignored) { }
        try { if (advertiser != null) advertiser.stopAdvertising(advCb); } catch (Exception ignored) { }
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        try { if (active != null) active.close(); } catch (Exception ignored) { }
        scanner = null; advertiser = null; server = null; active = null; activeId = null;
        queue.clear(); queuedIds.clear(); sessions.clear();
    }

    // ------------------------------------------------------------------ server side: hand out our pins
    private static final class ServerSession { String peer; byte[] payload = new byte[4]; int chunk; }

    @SuppressWarnings("MissingPermission")
    private void openServer() {
        server = bm.openGattServer(this, serverCb);
        BluetoothGattService s = new BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        s.addCharacteristic(new BluetoothGattCharacteristic(HELLO,
                BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE));
        s.addCharacteristic(new BluetoothGattCharacteristic(NAME,
                BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ));
        s.addCharacteristic(new BluetoothGattCharacteristic(DATA,
                BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_READ | BluetoothGattCharacteristic.PERMISSION_WRITE));
        server.addService(s);
    }

    private final BluetoothGattServerCallback serverCb = new BluetoothGattServerCallback() {
        @Override
        public void onConnectionStateChange(BluetoothDevice d, int st, int newState) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) sessions.remove(d.getAddress());
        }

        @Override @SuppressWarnings("MissingPermission")
        public void onCharacteristicWriteRequest(BluetoothDevice d, int req, BluetoothGattCharacteristic c,
                                                 boolean prepared, boolean needResp, int offset, byte[] v) {
            ServerSession s = sessions.get(d.getAddress());
            if (s == null) { s = new ServerSession(); sessions.put(d.getAddress(), s); }
            if (HELLO.equals(c.getUuid()) && v != null && v.length == 6) {
                s.peer = PinStore.hex(v);
                boolean ok = store.isFriend(s.peer);
                byte[] body = ok ? store.snapshot() : new byte[0];
                s.payload = ByteBuffer.allocate(4 + body.length).putInt(ok ? body.length : -1).put(body).array();
                s.chunk = 0;
            } else if (DATA.equals(c.getUuid()) && v != null && v.length == 4) {
                s.chunk = ByteBuffer.wrap(v).getInt();
            }
            if (needResp && server != null) server.sendResponse(d, req, BluetoothGatt.GATT_SUCCESS, offset, null);
        }

        @Override @SuppressWarnings("MissingPermission")
        public void onCharacteristicReadRequest(BluetoothDevice d, int req, int offset, BluetoothGattCharacteristic c) {
            byte[] full;
            if (NAME.equals(c.getUuid())) {
                full = store.myName().getBytes(StandardCharsets.UTF_8);
            } else {
                ServerSession s = sessions.get(d.getAddress());
                byte[] p = s == null ? new byte[4] : s.payload;
                int from = Math.min(p.length, (s == null ? 0 : s.chunk) * CHUNK);
                full = Arrays.copyOfRange(p, from, Math.min(p.length, from + CHUNK));
            }
            byte[] part = offset >= full.length ? new byte[0] : Arrays.copyOfRange(full, offset, full.length);
            if (server != null) server.sendResponse(d, req, BluetoothGatt.GATT_SUCCESS, offset, part);
        }
    };

    @SuppressWarnings("MissingPermission")
    private void advertise() {
        advertiser = adapter.getBluetoothLeAdvertiser();
        if (advertiser == null) return; // phone can't advertise; it can still pull from others
        AdvertiseSettings set = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(true).setTimeout(0).build();
        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(false).setIncludeTxPowerLevel(false)
                .addServiceData(new ParcelUuid(SERVICE), PinStore.unhex(store.myId()))
                .build();
        advertiser.startAdvertising(set, data, advCb);
    }

    private final AdvertiseCallback advCb = new AdvertiseCallback() {
        @Override public void onStartFailure(int code) { status = "can't advertise (" + code + "), receiving only"; }
    };

    // ------------------------------------------------------------------ client side: pull friends' pins
    @SuppressWarnings("MissingPermission")
    private void startScan() {
        if (scanner == null) return;
        ScanFilter f = new ScanFilter.Builder()
                .setServiceData(new ParcelUuid(SERVICE), new byte[6], new byte[6]).build();
        ScanSettings s = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build();
        scanner.startScan(Collections.singletonList(f), s, scanCb);
    }

    private final ScanCallback scanCb = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult r) { seen(r); }
        @Override public void onBatchScanResults(java.util.List<ScanResult> rs) { for (ScanResult r : rs) seen(r); }
        @Override public void onScanFailed(int code) { status = "scan failed (" + code + ")"; }
    };

    private void seen(ScanResult r) {
        ScanRecord rec = r.getScanRecord();
        if (rec == null) return;
        byte[] sd = rec.getServiceData(new ParcelUuid(SERVICE));
        if (sd == null || sd.length < 6) return;
        String id = PinStore.hex(Arrays.copyOf(sd, 6));
        if (id.equals(store.myId())) return;
        long now = System.currentTimeMillis();
        nearby.put(id, now);
        if (!store.isFriend(id)) return;
        Long last = lastAttempt.get(id);
        if (last != null && now - last < RESYNC_MS) return;
        if (queuedIds.containsValue(id) || id.equals(activeId)) return;
        lastAttempt.put(id, now);
        queue.add(r.getDevice());
        queuedIds.put(r.getDevice(), id);
        next();
    }

    private final Runnable timeout = new Runnable() { public void run() { finish(); } };

    @SuppressWarnings("MissingPermission")
    private void next() {
        if (active != null || queue.isEmpty() || adapter == null) return;
        BluetoothDevice d = queue.poll();
        activeId = queuedIds.remove(d);
        buf.reset(); total = UNKNOWN; chunk = 0;
        status = "syncing…";
        h.postDelayed(timeout, TIMEOUT_MS);
        active = Build.VERSION.SDK_INT >= 23
                ? d.connectGatt(this, false, clientCb, BluetoothDevice.TRANSPORT_LE)
                : d.connectGatt(this, false, clientCb);
    }

    @SuppressWarnings("MissingPermission")
    private void finish() {
        h.removeCallbacks(timeout);
        if (active != null) { try { active.disconnect(); active.close(); } catch (Exception ignored) { } }
        active = null; activeId = null;
        if (running && status.equals("syncing…")) status = "looking for friends";
        h.postDelayed(new Runnable() { public void run() { next(); } }, 500);
    }

    private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
    private static final int UNKNOWN = Integer.MIN_VALUE;
    private int total = UNKNOWN, chunk = 0;

    private final BluetoothGattCallback clientCb = new BluetoothGattCallback() {
        @Override @SuppressWarnings("MissingPermission")
        public void onConnectionStateChange(final BluetoothGatt g, int st, int ns) {
            h.post(new Runnable() { public void run() {
                if (g != active) return;
                if (ns == BluetoothProfile.STATE_CONNECTED) { if (!g.requestMtu(517)) g.discoverServices(); }
                else if (ns == BluetoothProfile.STATE_DISCONNECTED) finish();
            }});
        }

        @Override @SuppressWarnings("MissingPermission")
        public void onMtuChanged(final BluetoothGatt g, int mtu, int st) {
            h.post(new Runnable() { public void run() { if (g == active) g.discoverServices(); } });
        }

        @Override
        public void onServicesDiscovered(final BluetoothGatt g, int st) {
            h.post(new Runnable() { public void run() {
                if (g != active) return;
                if (st != BluetoothGatt.GATT_SUCCESS || g.getService(SERVICE) == null) { finish(); return; }
                write(g, HELLO, PinStore.unhex(store.myId()));
            }});
        }

        @Override
        public void onCharacteristicWrite(final BluetoothGatt g, final BluetoothGattCharacteristic c, final int st) {
            h.post(new Runnable() { public void run() {
                if (g != active) return;
                if (st != BluetoothGatt.GATT_SUCCESS) { finish(); return; }
                if (HELLO.equals(c.getUuid())) read(g, NAME);
                else if (DATA.equals(c.getUuid())) read(g, DATA);
            }});
        }

        @Override
        public void onCharacteristicRead(final BluetoothGatt g, final BluetoothGattCharacteristic c, final int st) {
            final byte[] v = c.getValue();
            h.post(new Runnable() { public void run() {
                if (g != active) return;
                if (st != BluetoothGatt.GATT_SUCCESS || v == null) { finish(); return; }
                if (NAME.equals(c.getUuid())) {
                    if (activeId != null) nearbyNames.put(activeId, new String(v, StandardCharsets.UTF_8));
                    chunk = 0;
                    write(g, DATA, ByteBuffer.allocate(4).putInt(0).array());
                    return;
                }
                buf.write(v, 0, v.length);
                byte[] all = buf.toByteArray();
                if (total == UNKNOWN && all.length >= 4) total = ByteBuffer.wrap(all, 0, 4).getInt();
                String nm = activeId != null && nearbyNames.containsKey(activeId) ? nearbyNames.get(activeId) : "friend";
                if (total == -1) { status = nm + " hasn't added you back yet"; finish(); return; }
                if (total >= 0 && all.length >= 4 + total) {
                    int n = store.merge(Arrays.copyOfRange(all, 4, 4 + total));
                    if (activeId != null) store.markSynced(activeId);
                    status = n > 0 ? "got " + n + " update" + (n == 1 ? "" : "s") + " from " + nm : "in sync with " + nm;
                    finish();
                } else if (v.length == 0 || total > 200_000) {
                    finish(); // peer has nothing for us (not mutual friends) or garbage
                } else {
                    chunk++;
                    write(g, DATA, ByteBuffer.allocate(4).putInt(chunk).array());
                }
            }});
        }
    };

    @SuppressWarnings({"MissingPermission", "deprecation"})
    private void write(BluetoothGatt g, UUID ch, byte[] v) {
        BluetoothGattCharacteristic c = g.getService(SERVICE).getCharacteristic(ch);
        if (c == null) { finish(); return; }
        c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        c.setValue(v);
        if (!g.writeCharacteristic(c)) finish();
    }

    @SuppressWarnings("MissingPermission")
    private void read(BluetoothGatt g, UUID ch) {
        BluetoothGattCharacteristic c = g.getService(SERVICE).getCharacteristic(ch);
        if (c == null || !g.readCharacteristic(c)) finish();
    }
}
