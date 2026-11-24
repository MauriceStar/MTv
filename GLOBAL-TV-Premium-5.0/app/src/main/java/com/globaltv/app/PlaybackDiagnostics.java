package com.globaltv.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * Small, privacy-conscious playback diagnostic history.
 * Stores only error category, numeric code, host and exception type; never a full URL.
 */
public final class PlaybackDiagnostics {
    private static final String PREFS = "MainActivity";
    private static final String EVENTS = "smart_repair_recent_events";
    private static final int MAX_EVENTS = 12;

    private PlaybackDiagnostics() { }

    public static void record(Context context, int code, String host, String exceptionType) {
        if (context == null) return;
        SharedPreferences prefs = context.getApplicationContext()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String category = categoryFor(code);
        String safeHost = clean(host, 80);
        String safeType = clean(exceptionType, 80);
        String timestamp = new SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault())
            .format(new Date());
        String event = timestamp + " • " + category + " • code " + code
            + " • " + (safeHost.isEmpty() ? "hôte inconnu" : safeHost)
            + " • " + (safeType.isEmpty() ? "erreur inconnue" : safeType);

        ArrayList<String> events = new ArrayList<>();
        String previous = prefs.getString(EVENTS, "");
        if (previous != null && !previous.isEmpty()) {
            for (String line : previous.split("\\n")) {
                if (!line.trim().isEmpty()) events.add(line);
            }
        }
        events.add(0, event);
        while (events.size() > MAX_EVENTS) events.remove(events.size() - 1);

        StringBuilder joined = new StringBuilder();
        for (String line : events) {
            if (joined.length() > 0) joined.append('\n');
            joined.append(line);
        }
        SharedPreferences.Editor edit = prefs.edit()
            .putString(EVENTS, joined.toString())
            .putString("smart_repair_last_category", category)
            .putString("smart_repair_last_event", event)
            .putLong("smart_repair_last_at", System.currentTimeMillis());
        String countKey = "smart_repair_count_" + category.toLowerCase(Locale.ROOT)
            .replace(' ', '_').replace('/', '_');
        edit.putInt(countKey, prefs.getInt(countKey, 0) + 1).apply();
    }

    public static String categoryFor(int code) {
        if (code >= 2000 && code < 3000) return "RÉSEAU";
        if (code >= 3000 && code < 4000) return "FORMAT / CONTENEUR";
        if (code >= 4000 && code < 5000) return "DÉCODEUR";
        if (code < 0) return "INITIALISATION";
        return "LECTURE";
    }

    public static String buildReport(Context context) {
        SharedPreferences prefs = context.getApplicationContext()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long lastAt = prefs.getLong("smart_repair_last_at", 0L);
        if (lastAt == 0L) return "Aucun incident de lecture enregistré pour le moment.\n\n"
            + "Smart Repair observe les erreurs sans modifier les playlists, favoris, profils, "
            + "historiques ou reprises.";
        String when = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
            .format(new Date(lastAt));
        StringBuilder report = new StringBuilder();
        report.append("Dernier incident : ").append(when).append('\n');
        report.append("Catégorie : ").append(prefs.getString("smart_repair_last_category", "Inconnue")).append('\n');
        report.append("Événements réseau : ").append(count(prefs, "réseau")).append('\n');
        report.append("Événements format/conteneur : ").append(count(prefs, "format___conteneur")).append('\n');
        report.append("Événements décodeur : ").append(count(prefs, "décodeur")).append('\n');
        report.append("Événements lecture : ").append(count(prefs, "lecture")).append('\n');
        report.append("Événements initialisation : ").append(count(prefs, "initialisation")).append('\n\n");
        report.append("Incidents récents (12 maximum) :\n");
        report.append(prefs.getString(EVENTS, "Aucun détail disponible."));
        report.append("\n\nDiagnostic uniquement : aucune réparation destructive ni modification des données.");
        return report.toString();
    }

    private static int count(SharedPreferences prefs, String suffix) {
        String key = "smart_repair_count_" + suffix.replace(' ', '_').replace('/', '_');
        return prefs.getInt(key, 0);
    }

    private static String clean(String value, int max) {
        if (value == null) return "";
        String safe = value.replaceAll("[\\r\\n\\t]", " ").trim();
        return safe.length() > max ? safe.substring(0, max) : safe;
    }
}
