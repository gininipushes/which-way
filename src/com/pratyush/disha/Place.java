package com.pratyush.disha;

final class Place {
    final double lat, lon;
    final String name, kind;
    String pinId;   // non-null for user / friend pins
    String owner;   // who pinned it
    double dist, bearing; // filled relative to the user's current position

    Place(double lat, double lon, String name, String kind) {
        this.lat = lat; this.lon = lon; this.name = name; this.kind = kind;
    }

    String key() { return pinId != null ? pinId : (name + "@" + lat + "," + lon); }

    void measureFrom(double la, double lo) {
        double R = 6371000.0;
        double p1 = Math.toRadians(la), p2 = Math.toRadians(lat);
        double dp = p2 - p1, dl = Math.toRadians(lon - lo);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        dist = 2 * R * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        bearing = (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    static String fmtDist(double m) {
        if (m < 1000) return Math.round(m / 10.0) * 10 + " m";
        return String.format(java.util.Locale.US, "%.1f km", m / 1000.0);
    }
}
