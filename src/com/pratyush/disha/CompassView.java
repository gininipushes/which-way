package com.pratyush.disha;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** One custom view: swipeable tabs on top, a compass needle to the nearest place below. */
public class CompassView extends View implements ValueAnimator.AnimatorUpdateListener {

    static final class ByDist implements Comparator<Place> {
        public int compare(Place a, Place b) { return Double.compare(a.dist, b.dist); }
    }
    static final ByDist BY_DIST = new ByDist();

    static final double CAMPUS_LAT = 13.3525, CAMPUS_LON = 74.7926, ZONE_M = 8000;
    static final String[] TABS = {"SUTTA", "DAARU", "MANDIR"};
    static final int[] ACCENT = {0xFFFF6B3D, 0xFFF2C14E, 0xFF8FD3C9};
    static final int SHOW_TOP = 5;

    interface Listener {
        void onLongPress(int cat, Place selected, boolean hasFix, double lat, double lon);
        void onList(int cat);
        void onFriends();
    }
    private Listener listener;
    private boolean longFired;
    private final Runnable longPress = new Runnable() {
        public void run() {
            if (dragging || listener == null) return;
            longFired = true;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            int cat = Math.round(page);
            listener.onLongPress(cat, selected(cat), hasFix, lat, lon);
        }
    };

    private List<List<Place>> places;
    private final List<List<Place>> sorted = new ArrayList<>();
    private final String[] chosen = new String[3]; // key of the place the needle is locked to, null = nearest
    private boolean syncOn, friendNear;

    private float page = 0f;          // animated page position 0..2
    private float drag = 0f;          // live finger offset in px
    private float heading = 0f, target = 0f;
    private boolean hasHeading, hasFix, noCompass, lowAcc;
    private double lat, lon;
    private float acc;
    private String status = "waiting for gps…";
    private boolean aligned;

    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint needle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tab = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float dp;

    private float downX, downY;
    private boolean dragging;
    private VelocityTracker vt;
    private ValueAnimator anim;

    public CompassView(Context c, List<List<Place>> places) {
        super(c);
        this.places = places;
        for (int i = 0; i < 3; i++) sorted.add(new ArrayList<Place>());
        dp = getResources().getDisplayMetrics().density;
        Typeface light = Typeface.create("sans-serif-light", Typeface.NORMAL);
        Typeface thin = Typeface.create("sans-serif-thin", Typeface.NORMAL);
        Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        ring.setStyle(Paint.Style.STROKE); ring.setStrokeWidth(1.2f * dp); ring.setColor(0xFF2A2A2A);
        tick.setStrokeCap(Paint.Cap.ROUND);
        needle.setStyle(Paint.Style.FILL);
        tab.setTypeface(medium); tab.setTextSize(13 * dp); tab.setLetterSpacing(0.25f); tab.setTextAlign(Paint.Align.CENTER);
        big.setTypeface(thin); big.setTextSize(56 * dp); big.setColor(0xFFEDEDED); big.setTextAlign(Paint.Align.CENTER);
        mid.setTypeface(light); mid.setTextSize(17 * dp); mid.setColor(0xFFBDBDBD); mid.setTextAlign(Paint.Align.CENTER);
        small.setTypeface(light); small.setTextSize(11.5f * dp); small.setColor(0xFF5E5E5E); small.setTextAlign(Paint.Align.CENTER);
        small.setLetterSpacing(0.12f);
        setBackgroundColor(0xFF0A0A0A);
        setHapticFeedbackEnabled(true);
    }

    // ---------------------------------------------------------------- state from activity
    void setHeading(float h) {
        target = h;
        if (!hasHeading) { heading = h; hasHeading = true; }
        postInvalidateOnAnimation();
    }

    void setLocation(double la, double lo, float accuracy) {
        lat = la; lon = lo; acc = accuracy; hasFix = true;
        for (int c = 0; c < 3; c++) {
            List<Place> s = sorted.get(c);
            s.clear();
            for (Place p : places.get(c)) { p.measureFrom(la, lo); s.add(p); }
            Collections.sort(s, BY_DIST);
        }
        invalidate();
    }

    void setListener(Listener l) { listener = l; }

    Place selected(int cat) {
        List<Place> list = sorted.get(cat);
        if (!hasFix || list.isEmpty()) return null;
        if (chosen[cat] != null) for (Place p : list) if (p.key().equals(chosen[cat])) return p;
        return list.get(0);
    }

    int indexOf(int cat, Place p) { return sorted.get(cat).indexOf(p); }
    List<Place> sortedList(int cat) { return sorted.get(cat); }
    boolean hasFix() { return hasFix; }
    int currentTab() { return Math.round(page); }

    void choose(int cat, Place p) { chosen[cat] = p == null ? null : p.key(); aligned = false; invalidate(); }

    void setPlaces(List<List<Place>> lists) {
        places = lists;
        if (hasFix) setLocation(lat, lon, acc);
        else invalidate();
    }

    void setSyncState(boolean on, boolean near) { syncOn = on; friendNear = near; invalidate(); }

    void setStatus(String s) { status = s; invalidate(); }
    void setNoCompass(boolean b) { noCompass = b; invalidate(); }
    void setLowAccuracy(boolean b) { lowAcc = b; invalidate(); }

    // ---------------------------------------------------------------- drawing
    @Override
    protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();

        // smooth heading along the shortest arc
        float d = ((target - heading + 540f) % 360f) - 180f;
        heading = (heading + d * 0.18f + 360f) % 360f;
        boolean moving = Math.abs(d) > 0.3f;

        float pos = page - drag / w;
        pos = Math.max(-0.25f, Math.min(2.25f, pos));

        drawTabs(cv, w, pos);
        for (int i = 0; i < 3; i++) {
            float off = (i - pos) * w;
            if (Math.abs(off) >= w) continue;
            cv.save();
            cv.translate(off, 0);
            drawPage(cv, w, h, i, 1f - Math.min(1f, Math.abs(i - pos)));
            cv.restore();
        }
        drawBar(cv, w, h, ACCENT[Math.round(Math.max(0, Math.min(2, pos)))]);
        if (moving) postInvalidateOnAnimation();
    }

    private float tabY() { return 64 * dp; }

    private void drawTabs(Canvas cv, int w, float pos) {
        float y = tabY();
        for (int i = 0; i < 3; i++) {
            float x = w * (i + 0.5f) / 3f;
            float closeness = 1f - Math.min(1f, Math.abs(i - pos));
            tab.setColor(blend(0xFF4A4A4A, 0xFFF0F0F0, closeness));
            cv.drawText(TABS[i], x, y, tab);
        }
        // indicator slides with the finger
        float ix = w * (pos + 0.5f) / 3f;
        int ci = Math.round(Math.max(0, Math.min(2, pos)));
        tick.setColor(ACCENT[ci]); tick.setStrokeWidth(2 * dp);
        cv.drawLine(ix - 12 * dp, y + 14 * dp, ix + 12 * dp, y + 14 * dp, tick);
    }

    private void drawPage(Canvas cv, int w, int h, int cat, float vis) {
        float cx = w / 2f, cy = h * 0.44f;
        float r = Math.min(w, h) * 0.34f;
        int accent = ACCENT[cat];

        // ring + ticks rotate so that N stays true north
        cv.drawCircle(cx, cy, r, ring);
        cv.save();
        cv.rotate(-heading, cx, cy);
        for (int a = 0; a < 360; a += 10) {
            boolean major = a % 90 == 0;
            tick.setColor(a == 0 ? 0xFFEDEDED : (major ? 0xFF6A6A6A : 0xFF333333));
            tick.setStrokeWidth((major ? 2f : 1f) * dp);
            float len = (major ? 14 : 7) * dp;
            cv.save(); cv.rotate(a, cx, cy);
            cv.drawLine(cx, cy - r + 4 * dp, cx, cy - r + 4 * dp + len, tick);
            cv.restore();
        }
        small.setColor(0xFFEDEDED);
        cv.save(); cv.rotate(0, cx, cy);
        cv.drawText("N", cx, cy - r - 10 * dp, small);
        cv.restore();
        cv.restore();
        small.setColor(0xFF5E5E5E);

        List<Place> list = sorted.get(cat);
        Place p = selected(cat);

        float textY = cy + r + 72 * dp;
        if (p == null) {
            // centre dot only, plus a status line
            needle.setColor(0xFF2E2E2E);
            cv.drawCircle(cx, cy, 4 * dp, needle);
            String msg = !hasFix ? status : (places.get(cat).isEmpty() ? "no places in data" : status);
            mid.setColor(0xFF8A8A8A);
            cv.drawText(msg, cx, textY, mid);
            mid.setColor(0xFFBDBDBD);
            return;
        }

        float rel = (float) p.bearing - heading;
        float relN = ((rel % 360f) + 540f) % 360f - 180f;
        boolean nowAligned = Math.abs(relN) < 6f && hasHeading;
        if (nowAligned && !aligned && vis > 0.9f) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        if (vis > 0.9f) aligned = nowAligned;

        // needle
        cv.save();
        cv.rotate(noCompass ? (float) p.bearing : rel, cx, cy);
        path.reset();
        path.moveTo(cx, cy - r * 0.78f);
        path.lineTo(cx + 13 * dp, cy + 6 * dp);
        path.lineTo(cx, cy - 2 * dp);
        path.lineTo(cx - 13 * dp, cy + 6 * dp);
        path.close();
        needle.setColor(nowAligned ? 0xFFFFFFFF : accent);
        cv.drawPath(path, needle);
        cv.restore();
        needle.setColor(0xFF0A0A0A);
        cv.drawCircle(cx, cy, 3.5f * dp, needle);

        // texts
        cv.drawText(Place.fmtDist(p.dist), cx, textY, big);
        String name = p.name.isEmpty() ? "unnamed" : p.name;
        cv.drawText(ellipsize(name, w - 48 * dp, mid), cx, textY + 34 * dp, mid);
        String meta = p.kind.toLowerCase();
        if (p.owner != null && !p.owner.isEmpty() && "friend pin".equals(p.kind)) meta = "pinned by " + p.owner.toLowerCase();
        int n = list.size();
        if (n > 1) meta += "   ·   " + (list.indexOf(p) + 1) + " of " + n;
        cv.drawText(meta, cx, textY + 58 * dp, small);

        String foot = null;
        if (distance(lat, lon, CAMPUS_LAT, CAMPUS_LON) > ZONE_M + 1000) foot = "outside the manipal 8 km zone";
        else if (noCompass) foot = "no compass sensor · arrow is relative to north";
        else if (lowAcc) foot = "compass weak · wave phone in a figure 8";
        else if (acc > 50) foot = "gps ±" + Math.round(acc) + " m";
        if (foot == null) { foot = "tap for next   ·   hold to pin this spot"; small.setColor(0xFF3A3A3A); }
        cv.drawText(foot, cx, h - 84 * dp, small);
        small.setColor(0xFF5E5E5E);
    }

    private float barY() { return getHeight() - 40 * dp; }

    private void drawBar(Canvas cv, int w, int h, int accent) {
        float y = barY();
        tab.setColor(0xFF6A6A6A);
        cv.drawText("LIST", w * 0.25f, y, tab);
        cv.drawText("FRIENDS", w * 0.75f, y, tab);
        if (syncOn) {
            needle.setColor(friendNear ? accent : 0xFF3A3A3A);
            cv.drawCircle(w * 0.75f + tab.measureText("FRIENDS") / 2f + 8 * dp, y - 4.5f * dp, 3 * dp, needle);
        }
    }

    private static String ellipsize(String s, float max, Paint p) {
        if (p.measureText(s) <= max) return s;
        while (s.length() > 1 && p.measureText(s + "…") > max) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    private static double distance(double a1, double o1, double a2, double o2) {
        Place t = new Place(a2, o2, "", "");
        t.measureFrom(a1, o1);
        return t.dist;
    }

    private static int blend(int a, int b, float t) {
        return Color.argb(255,
                (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    // ---------------------------------------------------------------- touch: swipe tabs, tap to cycle
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int w = getWidth();
        if (vt == null) vt = VelocityTracker.obtain();
        vt.addMovement(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (anim != null) { anim.cancel(); }
                downX = e.getX(); downY = e.getY(); dragging = false; drag = 0; longFired = false;
                if (downY > tabY() + 30 * dp && downY < barY() - 28 * dp) postDelayed(longPress, 550);
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = e.getX() - downX;
                if (!dragging && Math.abs(dx) > 12 * dp) { dragging = true; removeCallbacks(longPress); }
                if (dragging) { drag = dx; invalidate(); }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPress);
                if (longFired) { if (vt != null) { vt.recycle(); vt = null; } return true; }
                vt.computeCurrentVelocity(1000);
                float vx = vt.getXVelocity();
                vt.recycle(); vt = null;
                if (dragging) {
                    float pos = page - drag / w;
                    int dest = Math.round(pos);
                    if (Math.abs(vx) > 600 * dp / 1.0f) dest = vx < 0 ? (int) Math.floor(page) + 1 : (int) Math.ceil(page) - 1;
                    dest = Math.max(0, Math.min(2, dest));
                    page = pos; drag = 0;
                    animateTo(dest);
                } else if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                    int cat = Math.round(page);
                    if (downY < tabY() + 30 * dp) {
                        animateTo(Math.max(0, Math.min(2, (int) (downX / (w / 3f)))));
                    } else if (downY > barY() - 28 * dp) {
                        if (listener != null) { if (downX < w / 2f) listener.onList(cat); else listener.onFriends(); }
                    } else {
                        List<Place> l = sorted.get(cat);
                        Place cur = selected(cat);
                        if (cur != null && l.size() > 1) choose(cat, l.get((l.indexOf(cur) + 1) % l.size()));
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        invalidate();
                    }
                }
                return true;
        }
        return super.onTouchEvent(e);
    }

    private void animateTo(int dest) {
        anim = ValueAnimator.ofFloat(page, dest);
        anim.setDuration(260);
        anim.setInterpolator(new DecelerateInterpolator(1.6f));
        anim.addUpdateListener(this);
        anim.start();
        aligned = false;
    }

    @Override
    public void onAnimationUpdate(ValueAnimator a) { page = (Float) a.getAnimatedValue(); invalidate(); }
}
