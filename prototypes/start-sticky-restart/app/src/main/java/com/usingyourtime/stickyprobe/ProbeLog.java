package com.usingyourtime.stickyprobe;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.content.Context;
import android.os.Build;
import android.os.PowerManager;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** PROTOTYPE: appends probe events to logcat (tag StickyProbe) and files/probe.log. */
final class ProbeLog {
    static final String TAG = "StickyProbe";

    static void e(Context c, String msg) {
        String line = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date())
                + " pid=" + Process.myPid() + " " + state(c) + " | " + msg;
        Log.i(TAG, line);
        try (FileWriter w = new FileWriter(file(c), true)) {
            w.write(line + "\n");
        } catch (Exception ignored) {
        }
    }

    static String state(Context c) {
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        KeyguardManager km = (KeyguardManager) c.getSystemService(Context.KEYGUARD_SERVICE);
        ActivityManager.RunningAppProcessInfo info = new ActivityManager.RunningAppProcessInfo();
        ActivityManager.getMyMemoryState(info);
        return "screenOn=" + pm.isInteractive() + " locked=" + km.isKeyguardLocked()
                + " importance=" + info.importance;
    }

    static File file(Context c) {
        return new File(c.getFilesDir(), "probe.log");
    }

    static String header() {
        return "SDK=" + Build.VERSION.SDK_INT + " " + Build.MANUFACTURER + " " + Build.MODEL;
    }
}
