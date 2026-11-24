package com.globaltv.app;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/** Owns the unique periodic and manual AutoPilot work requests. */
public final class AutoPilotScheduler {
    private static final String PERIODIC_WORK = "mtv_autopilot_source_monitor";
    private static final String MANUAL_WORK = "mtv_autopilot_manual_source_check";

    private AutoPilotScheduler() { }

    public static void sync(Context context) {
        Context app = context.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences("MainActivity", Context.MODE_PRIVATE);
        boolean enabled = prefs.getBoolean("autopilot_enabled", false);
        String mode = prefs.getString("autopilot_mode", "assist");
        WorkManager manager = WorkManager.getInstance(app);
        if (!enabled || "manual".equals(mode)) {
            manager.cancelUniqueWork(PERIODIC_WORK);
            return;
        }

        Constraints constraints = new Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build();
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
            AutoPilotWorker.class, 24, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build();
        manager.enqueueUniquePeriodicWork(
            PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request);
    }

    public static void runNow(Context context) {
        Constraints constraints = new Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(AutoPilotWorker.class)
            .setInputData(new androidx.work.Data.Builder().putBoolean("manual_run", true).build())
            .setConstraints(constraints)
            .build();
        WorkManager.getInstance(context.getApplicationContext())
            .enqueueUniqueWork(MANUAL_WORK, ExistingWorkPolicy.KEEP, request);
    }

    public static void disable(Context context) {
        WorkManager.getInstance(context.getApplicationContext()).cancelUniqueWork(PERIODIC_WORK);
    }
}
