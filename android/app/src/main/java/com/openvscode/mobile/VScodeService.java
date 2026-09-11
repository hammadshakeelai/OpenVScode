package com.openvscode.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

public class VScodeService extends Service {
    private static final String TAG = "VScodeService";
    private static final String CHANNEL_ID = "openvscode_foreground_channel";
    private static final int NOTIFICATION_ID = 1001;
    public static final String ACTION_STOP = "com.openvscode.mobile.ACTION_STOP";

    private PowerManager.WakeLock wakeLock;
    private static final long WAKE_LOCK_TIMEOUT_MS = 30 * 60 * 1000L;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            endSessionSupport();
            return START_NOT_STICKY;
        }

        try {
            Notification notification = buildNotification();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            acquireWakeLock();
        } catch (RuntimeException e) {
            // The user can leave the Activity between dispatch and promotion.
            // A refused foreground start must not crash an otherwise usable IDE.
            Log.w(TAG, "Android declined background session support", e);
            endSessionSupport();
        }

        // Termux owns the server process. Recreating this service after a kill
        // would imply a live session and consume power without a connected UI.
        return START_NOT_STICKY;
    }

    private void acquireWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) return;
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (powerManager != null) {
                wakeLock = powerManager.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "OpenVScode::SessionSupport"
                );
                wakeLock.setReferenceCounted(false);
                // This is a bounded convenience. Termux's own notification and
                // wake lock manage long compilations after the app is closed.
                wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
            }
        } catch (Exception e) {
            Log.w(TAG, "Device could not acquire the temporary wake lock", e);
        }
    }

    private Notification buildNotification() {
        Intent openAppIntent = new Intent(this, MainActivity.class);
        openAppIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingOpen = PendingIntent.getActivity(
                this, 0, openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent stopIntent = new Intent(this, VScodeService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent pendingStop = PendingIntent.getService(
                this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.service_running_title))
                .setContentText(getString(R.string.service_running_desc))
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText(getString(R.string.service_running_desc)))
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(pendingOpen)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.action_stop), pendingStop)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "OpenVScode session support",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Temporary device wake lock while working in the IDE");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public void onDestroy() {
        releaseWakeLock();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    private void endSessionSupport() {
        releaseWakeLock();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
