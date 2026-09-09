package com.openvscode.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
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
    private WifiManager.WifiLock wifiLock;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        acquireLocks();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            Log.i(TAG, "Stop action received. Terminating service...");
            stopSelf();
            return START_NOT_STICKY;
        }

        Notification notification = buildNotification();
        startForeground(NOTIFICATION_ID, notification);
        Log.i(TAG, "OpenVScode foreground service started with WakeLock active.");

        return START_STICKY;
    }

    private void acquireLocks() {
        try {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (powerManager != null) {
                wakeLock = powerManager.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "OpenVScode::CpuCompilationLock"
                );
                // Hold wake lock with a 30-minute safety timeout to prevent permanent battery drain if orphaned
                wakeLock.acquire(30 * 60 * 1000L);
            }

            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null) {
                wifiLock = wifiManager.createWifiLock(
                        WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                        "OpenVScode::WifiLock"
                );
                wifiLock.acquire();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to acquire power/wifi locks", e);
        }
    }

    private Notification buildNotification() {
        Intent openAppIntent = new Intent(this, MainActivity.class);
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
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(pendingOpen)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.action_stop), pendingStop)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "OpenVScode Background Server",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Keeps the local IDE and compilers running in the background");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
        }
        Log.i(TAG, "OpenVScode service destroyed. Locks released.");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
