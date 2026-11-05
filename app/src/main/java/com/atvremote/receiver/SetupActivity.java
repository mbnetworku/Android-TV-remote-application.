package com.atvremote.receiver;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/** Setup screen on the TV: shows whether the receiver is on and how to switch it on. */
public final class SetupActivity extends Activity {

    private TextView status;
    private Button open;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xFF000000);
        root.setPadding(80, 60, 80, 60);

        TextView title = text("TV Remote Receiver", 34, 0xFFFFFFFF);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(title);

        status = text("", 22, 0xFF3DD68C);
        status.setPadding(0, 30, 0, 30);
        root.addView(status);

        root.addView(text("Control this TV from your phone with the TV Remote app.\n"
                + "No developer options needed. Free, no ads.\n\n"
                + "1. Press the button below.\n"
                + "2. Find \"TV Remote Receiver\" and switch it ON.\n"
                + "3. Open TV Remote on your phone (same Wi-Fi). Enter the PIN shown on this TV.", 18, 0xFFC9CED8));

        open = new Button(this);
        open.setText("Open Accessibility settings");
        open.setTextSize(18);
        open.setAllCaps(false);
        open.setTextColor(0xFFFFFFFF);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x33FFFFFF);
        bg.setCornerRadius(40);
        bg.setStroke(2, 0x66FFFFFF);
        open.setBackground(bg);
        open.setPadding(60, 20, 60, 20);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 40;
        root.addView(open, lp);
        open.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        });
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        String ip = ip();
        if (ReceiverService.running) {
            status.setText("✓ Receiver is ON" + (ip != null ? "  ·  " + ip : ""));
            status.setTextColor(0xFF3DD68C);
            open.setText("Accessibility settings");
        } else {
            status.setText("Receiver is OFF. Switch it on in Accessibility");
            status.setTextColor(0xFFF5A524);
            open.requestFocus();
        }
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        return t;
    }

    private static String ip() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
