package com.usingyourtime.stickyprobe;

import android.app.ForegroundServiceStartNotAllowedException;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * PROTOTYPE. Question: after the system kills this process and restarts the START_STICKY service
 * (intent == null), can startForeground() succeed on Android 12+ while the app is in background?
 */
public class ProbeService extends Service {
    static final String CH = "probe";
    static final int NOTIF_ID = 1;

    @Override
    public void onCreate() {
        super.onCreate();
        ProbeLog.e(this, "service onCreate " + ProbeLog.header());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        boolean restart = intent == null;
        ProbeLog.e(this, "onStartCommand restart=" + restart + " flags=" + flags + " startId=" + startId);
        String result;
        try {
            Notification n = notif("startForeground OK (restart=" + restart + ")");
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIF_ID, n);
            }
            result = "startForeground OK";
        } catch (Exception ex) {
            boolean notAllowed = Build.VERSION.SDK_INT >= 31
                    && ex instanceof ForegroundServiceStartNotAllowedException;
            result = "startForeground FAILED notAllowed=" + notAllowed + " " + ex;
        }
        ProbeLog.e(this, result);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        ProbeLog.e(this, "service onDestroy");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification notif(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH, "Probe", NotificationManager.IMPORTANCE_LOW));
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CH)
                .setSmallIcon(android.R.drawable.ic_menu_recent_history)
                .setContentTitle("StickyProbe pid=" + android.os.Process.myPid())
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }
}
