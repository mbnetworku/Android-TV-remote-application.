package com.atvremote.app;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/** Saved TVs (host[:port] + display name) and which one is selected, kept in SharedPreferences. */
final class DeviceStore {

    static final class Device {
        final String host;
        String name;

        Device(String host, String name) {
            this.host = host;
            this.name = name;
        }

        String label() {
            return name != null && !name.isEmpty() ? name : "TV";
        }
    }

    private final SharedPreferences prefs;
    private final List<Device> devices = new ArrayList<>();
    private int current;

    DeviceStore(SharedPreferences prefs) {
        this.prefs = prefs;
        String raw = prefs.getString("devices", null);
        if (raw != null) {
            for (String line : raw.split("\n")) {
                if (line.isEmpty()) continue;
                String[] p = line.split("\t", 2);
                if (indexOf(strip(p[0])) < 0) devices.add(new Device(strip(p[0]), p.length > 1 ? p[1] : null));
            }
        } else {
            String legacy = prefs.getString("host", null); // from version 1.0
            if (legacy != null) devices.add(new Device(strip(legacy), null));
        }
        current = Math.max(0, Math.min(prefs.getInt("current", 0), devices.size() - 1));
    }

    /** "1.2.3.4:5555" and "1.2.3.4" are the same TV. */
    private static String strip(String host) {
        return host.endsWith(":5555") ? host.substring(0, host.length() - 5) : host;
    }

    List<Device> all() {
        return devices;
    }

    int size() {
        return devices.size();
    }

    int currentIndex() {
        return current;
    }

    Device current() {
        return devices.isEmpty() ? null : devices.get(current);
    }

    int indexOf(String host) {
        for (int i = 0; i < devices.size(); i++) if (devices.get(i).host.equals(host)) return i;
        return -1;
    }

    /** Adds the host if it is new; returns its index either way. */
    int add(String host) {
        int i = indexOf(host);
        if (i >= 0) return i;
        devices.add(new Device(host, null));
        save();
        return devices.size() - 1;
    }

    void select(int index) {
        if (index < 0 || index >= devices.size()) return;
        current = index;
        save();
    }

    void setName(String host, String name) {
        int i = indexOf(host);
        if (i < 0 || name == null || name.isEmpty() || name.equals(host)) return;
        if (name.equals(devices.get(i).name)) return;
        devices.get(i).name = name;
        save();
    }

    void remove(int index) {
        if (index < 0 || index >= devices.size()) return;
        devices.remove(index);
        if (current >= devices.size()) current = Math.max(0, devices.size() - 1);
        else if (index < current) current--;
        save();
    }

    private void save() {
        StringBuilder sb = new StringBuilder();
        for (Device d : devices) {
            sb.append(d.host);
            if (d.name != null) sb.append('\t').append(d.name.replace('\n', ' ').replace('\t', ' '));
            sb.append('\n');
        }
        prefs.edit().putString("devices", sb.toString()).putInt("current", current).remove("host").apply();
    }
}
