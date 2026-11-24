package com.globaltv.app;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;

/**
 * Lightweight AutoPilot source monitor.
 *
 * It checks playlist endpoints only; it does not probe every channel or modify
 * playlists. Results are stored for the in-app Control Center.
 */
public final class AutoPilotWorker extends Worker {
    private static final int MAX_SOURCES_PER_RUN = 8;
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 5000;

    public AutoPilotWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        SharedPreferences prefs = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("autopilot_enabled", false)) return Result.success();

        Set<String> configured = prefs.getStringSet("m3u_sources", Collections.emptySet());
        ArrayList<String> sources = new ArrayList<>();
        if (configured != null) {
            for (String source : configured) {
                if (source != null && (source.startsWith("https://") || source.startsWith("http://"))) {
                    sources.add(source);
                    if (sources.size() >= MAX_SOURCES_PER_RUN) break;
                }
            }
        }

        int checked = 0;
        int available = 0;
        ArrayList<String> details = new ArrayList<>();
        for (String address : sources) {
            if (isStopped()) break;
            HttpURLConnection connection = null;
            checked++;
            try {
                connection = (HttpURLConnection) new URL(address).openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("Range", "bytes=0-0");
                connection.setRequestProperty("User-Agent", "MTv-AutoPilot/1.0");
                int code = connection.getResponseCode();
                String host = connection.getURL().getHost();
                if (code >= 200 && code < 300) {
                    available++;
                    details.add(host + " : HTTP " + code);
                } else {
                    details.add(host + " : HTTP " + code);
                }
            } catch (Exception error) {
                String host;
                try { host = new URL(address).getHost(); }
                catch (Exception ignored) { host = "Source"; }
                String reason = error.getMessage();
                if (reason == null || reason.trim().isEmpty()) reason = error.getClass().getSimpleName();
                if (reason.length() > 90) reason = reason.substring(0, 90);
                details.add(host + " : " + reason);
            } finally {
                if (connection != null) connection.disconnect();
            }
        }

        String report;
        if (checked == 0) {
            report = "Aucune source M3U HTTP/HTTPS configurée.";
        } else {
            report = String.format(Locale.getDefault(),
                "%d source(s) contrôlée(s) • %d endpoint(s) HTTP disponible(s) • %d réponse(s) en erreur",
                checked, available, checked - available);
        }
        StringBuilder detailText = new StringBuilder();
        for (String line : details) {
            if (detailText.length() > 0) detailText.append('\n');
            detailText.append(line);
        }
        prefs.edit()
            .putLong("autopilot_last_check", System.currentTimeMillis())
            .putString("autopilot_last_report", report)
            .putString("autopilot_last_details", detailText.toString())
            .putInt("autopilot_last_checked", checked)
            .putInt("autopilot_last_available", available)
            .apply();
        return Result.success();
    }
}
