package com.pratyush.disha;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Process-wide store for user pins, friends and this device's identity.
 * Pins are last-writer-wins by timestamp; deletes are kept as tombstones so they spread too.
 */
final class PinStore {

    static final class Pin {
        String id; int cat; double lat, lon; String name, owner, ownerId = ""; long ts; boolean deleted;
        String line() {
            return id + "\t" + cat + "\t" + lat + "\t" + lon + "\t" + clean(name) + "\t" + clean(owner) + "\t" + ts + "\t" + (deleted ? 1 : 0) + "\t" + ownerId;
        }
        static Pin parse(String l) {
            String[] p = l.split("\t", -1);
            if (p.length < 8) return null;
            try {
                Pin x = new Pin();
                x.id = p[0]; x.cat = Integer.parseInt(p[1]);
                x.lat = Double.parseDouble(p[2]); x.lon = Double.parseDouble(p[3]);
                x.name = p[4]; x.owner = p[5]; x.ts = Long.parseLong(p[6]); x.deleted = "1".equals(p[7]);
                if (p.length > 8) x.ownerId = p[8];
                if (x.cat < 0 || x.cat > 2 || x.id.isEmpty()) return null;
                return x;
            } catch (Exception e) { return null; }
        }
    }

    static final class Friend {
        String id, name; long lastSync;
    }

    interface Listener { void onPinsChanged(); }

    private static PinStore inst;
    static synchronized PinStore get(Context c) {
        if (inst == null) inst = new PinStore(c.getApplicationContext());
        return inst;
    }

    private final Context ctx;
    private final SharedPreferences prefs;
    private final Map<String, Pin> pins = new LinkedHashMap<>();
    private final Map<String, Friend> friends = new LinkedHashMap<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private PinStore(Context c) {
        ctx = c;
        prefs = c.getSharedPreferences("disha", Context.MODE_PRIVATE);
        if (!prefs.contains("id")) {
            byte[] b = new byte[6];
            new SecureRandom().nextBytes(b);
            prefs.edit().putString("id", hex(b)).apply();
        }
        load();
    }

    // ------------------------------------------------------------ identity
    String myId() { return prefs.getString("id", ""); }
    String myName() { return prefs.getString("name", "WhichWay " + myId().substring(0, 4)); }
    void setMyName(String n) { prefs.edit().putString("name", clean(n)).apply(); }
    boolean syncEnabled() { return prefs.getBoolean("sync", false); }
    void setSyncEnabled(boolean b) { prefs.edit().putBoolean("sync", b).apply(); }
    static String pretty(String id) {
        return id.length() == 12 ? (id.substring(0, 4) + "-" + id.substring(4, 8) + "-" + id.substring(8)).toUpperCase() : id.toUpperCase();
    }
    static String normalize(String code) { return code.replaceAll("[^0-9a-fA-F]", "").toLowerCase(); }

    // ------------------------------------------------------------ pins
    synchronized List<Pin> livePins() {
        List<Pin> out = new ArrayList<>();
        for (Pin p : pins.values()) if (!p.deleted) out.add(p);
        return out;
    }

    synchronized Pin add(int cat, double lat, double lon, String name) {
        Pin p = new Pin();
        byte[] b = new byte[8]; new SecureRandom().nextBytes(b);
        p.id = hex(b); p.cat = cat; p.lat = lat; p.lon = lon; p.name = clean(name);
        p.owner = myName(); p.ownerId = myId(); p.ts = System.currentTimeMillis();
        pins.put(p.id, p);
        save(); fire();
        return p;
    }

    synchronized void delete(String id) {
        Pin p = pins.get(id);
        if (p == null) return;
        p.deleted = true; p.ts = Math.max(System.currentTimeMillis(), p.ts + 1);
        save(); fire();
    }

    synchronized byte[] snapshot() {
        StringBuilder sb = new StringBuilder();
        for (Pin p : pins.values()) sb.append(p.line()).append('\n');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Merge pins received from a friend. Returns how many changed. */
    synchronized int merge(byte[] data) {
        int changed = 0;
        for (String l : new String(data, StandardCharsets.UTF_8).split("\n")) {
            Pin in = Pin.parse(l);
            if (in == null) continue;
            Pin cur = pins.get(in.id);
            if (cur == null || in.ts > cur.ts) { pins.put(in.id, in); changed++; }
        }
        if (changed > 0) { save(); fire(); }
        return changed;
    }

    // ------------------------------------------------------------ friends
    synchronized List<Friend> friends() { return new ArrayList<>(friends.values()); }
    synchronized boolean isFriend(String id) { return friends.containsKey(id); }
    synchronized void addFriend(String id, String name) {
        Friend f = friends.get(id);
        if (f == null) { f = new Friend(); f.id = id; friends.put(id, f); }
        f.name = clean(name.isEmpty() ? pretty(id) : name);
        save(); fire();
    }
    synchronized void removeFriend(String id) { friends.remove(id); save(); fire(); }
    synchronized void markSynced(String id) {
        Friend f = friends.get(id);
        if (f != null) { f.lastSync = System.currentTimeMillis(); save(); fire(); }
    }

    void addListener(Listener l) { listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }
    private void fire() { for (Listener l : listeners) l.onPinsChanged(); }

    // ------------------------------------------------------------ persistence
    private File pinFile() { return new File(ctx.getFilesDir(), "pins2.tsv"); }
    private File friendFile() { return new File(ctx.getFilesDir(), "friends.tsv"); }

    private void load() {
        for (String l : readLines(pinFile())) { Pin p = Pin.parse(l); if (p != null) pins.put(p.id, p); }
        for (String l : readLines(friendFile())) {
            String[] p = l.split("\t", -1);
            if (p.length < 3) continue;
            Friend f = new Friend(); f.id = p[0]; f.name = p[1];
            try { f.lastSync = Long.parseLong(p[2]); } catch (Exception ignored) { }
            friends.put(f.id, f);
        }
        // migrate v1 pins (cat, lat, lon, name)
        File old = new File(ctx.getFilesDir(), "pins.tsv");
        if (old.exists()) {
            for (String l : readLines(old)) {
                String[] p = l.split("\t", 4);
                if (p.length < 4) continue;
                try {
                    Pin x = new Pin();
                    byte[] b = new byte[8]; new SecureRandom().nextBytes(b);
                    x.id = hex(b); x.cat = Integer.parseInt(p[0]);
                    x.lat = Double.parseDouble(p[1]); x.lon = Double.parseDouble(p[2]);
                    x.name = clean(p[3]); x.owner = myName(); x.ownerId = myId(); x.ts = System.currentTimeMillis();
                    pins.put(x.id, x);
                } catch (Exception ignored) { }
            }
            old.delete();
            save();
        }
    }

    private void save() {
        StringBuilder sb = new StringBuilder();
        for (Pin p : pins.values()) sb.append(p.line()).append('\n');
        write(pinFile(), sb.toString());
        sb.setLength(0);
        for (Friend f : friends.values()) sb.append(f.id).append('\t').append(clean(f.name)).append('\t').append(f.lastSync).append('\n');
        write(friendFile(), sb.toString());
    }

    private static List<String> readLines(File f) {
        List<String> out = new ArrayList<>();
        if (!f.exists()) return out;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String l; while ((l = r.readLine()) != null) if (!l.isEmpty()) out.add(l);
        } catch (Exception ignored) { }
        return out;
    }

    private static void write(File f, String s) {
        File tmp = new File(f.getPath() + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) { w.write(s); }
        catch (Exception e) { return; }
        tmp.renameTo(f);
    }

    static String clean(String s) {
        if (s == null) return "";
        s = s.replace('\t', ' ').replace('\n', ' ').trim();
        return s.length() > 60 ? s.substring(0, 60) : s;
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    static byte[] unhex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }
}
