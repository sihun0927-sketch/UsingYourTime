package com.usingyourtime.stickyprobe;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.nio.file.Files;

/** PROTOTYPE UI: start FGS, schedule a process kill so the user can lock the screen first, show log. */
public class MainActivity extends Activity {
    private TextView log;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(btn("1. Start FGS", () -> {
            ProbeLog.e(this, "user: startForegroundService");
            startForegroundService(new Intent(this, ProbeService.class));
        }));
        root.addView(btn("2. Kill process in 15s (lock screen NOW)", () -> {
            ProbeLog.e(this, "user: kill scheduled in 15s");
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                ProbeLog.e(this, "killing self now");
                Process.killProcess(Process.myPid());
            }, 15_000);
        }));
        root.addView(btn("Stop service", () -> {
            ProbeLog.e(this, "user: stopService");
            stopService(new Intent(this, ProbeService.class));
        }));
        root.addView(btn("Refresh log", this::refresh));
        root.addView(btn("Clear log", () -> {
            ProbeLog.file(this).delete();
            refresh();
        }));
        log = new TextView(this);
        log.setTextSize(11);
        ScrollView sv = new ScrollView(this);
        sv.addView(log);
        root.addView(sv);
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        try {
            log.setText(ProbeLog.header() + "\n"
                    + new String(Files.readAllBytes(ProbeLog.file(this).toPath())));
        } catch (Exception e) {
            log.setText(ProbeLog.header() + "\n(no log yet)");
        }
    }

    private Button btn(String label, Runnable r) {
        Button bt = new Button(this);
        bt.setText(label);
        bt.setOnClickListener(v -> {
            r.run();
            refresh();
        });
        return bt;
    }
}
