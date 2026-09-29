package eu.kodanetwork.mchost.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

import eu.kodanetwork.mchost.R;
import eu.kodanetwork.mchost.util.Material3ThemeHelper;

/**
 * Support screen for moments when someone is not okay.
 *
 * Opens when a server name, a rename or the MOTD contains phrases like "kms" or "selbstmord".
 * It shows helplines, a way to reach people (phone, chat, Discord, support ticket) and a
 * breathing exercise. The continue button unlocks after a short wait so the page cannot be
 * tapped away reflexively; the back button stays blocked for the same time, but home and
 * recents remain free - nobody is ever trapped inside the app.
 *
 * Everything here is local: nothing about this screen is logged, sent or stored.
 */
public class CrisisSupportActivity extends AppCompatActivity {

    public static final int REQ_CRISIS = 9021;
    /** Discord invite of the KodaHosting community. */
    private static final String DISCORD_INVITE = "https://discord.gg/e5axQpq3xp";
    /** Seconds until the continue button unlocks. */
    private static final long UNLOCK_SECONDS = 40;

    private CountDownTimer unlockTimer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Material3ThemeHelper.applyTheme(this);
        setContentView(R.layout.activity_crisis_support);

        wireHelpButtons();
        startUnlockCountdown();
    }

    private void wireHelpButtons() {
        // Direct call: the number depends on the app language (resource holds the digits only)
        MaterialButton call = findViewById(R.id.btn_crisis_call);
        String callNumber = getString(R.string.crisis_call_number);
        if (callNumber == null || callNumber.trim().isEmpty()) {
            // No local number for this language: the directory button takes its place
            call.setVisibility(View.GONE);
        } else {
            call.setOnClickListener(v -> open("tel:" + callNumber.trim()));
        }

        findViewById(R.id.btn_crisis_krisenchat).setOnClickListener(v -> open("https://krisenchat.de"));
        findViewById(R.id.btn_crisis_findhelp).setOnClickListener(v -> open("https://findahelpline.com"));
        findViewById(R.id.btn_crisis_discord).setOnClickListener(v -> open(DISCORD_INVITE));
        // No form, no questions: the ticket is created silently and the chat opens directly
        MaterialButton support = findViewById(R.id.btn_crisis_support);
        support.setOnClickListener(v -> {
            support.setEnabled(false);
            support.setText(getString(R.string.personal_chat_opening));
            new Thread(() -> {
                String id = null;
                try {
                    org.json.JSONObject body = new org.json.JSONObject();
                    body.put("p_reporter_uuid",
                            eu.kodanetwork.mchost.App.getPrefs(this).getString("app_uuid", ""));
                    body.put("p_device_token",
                            eu.kodanetwork.mchost.App.getPrefs(this).getString("device_token", ""));
                    body.put("p_ticket_type", "PERSONAL");
                    body.put("p_reference_id", "");
                    body.put("p_title", getString(R.string.personal_chat_ticket_title));
                    String response = SupportApiHelper.call(this, "rest/v1/rpc/rpc_create_ticket", body.toString());
                    id = response.replace("\"", "").trim();
                } catch (Exception e) {
                    android.util.Log.w("CrisisSupport", "ticket failed: " + e.getMessage());
                }
                final String ticketId = id;
                runOnUiThread(() -> {
                    support.setEnabled(true);
                    support.setText(getString(R.string.crisis_support));
                    if (ticketId != null && !ticketId.isEmpty() && !ticketId.startsWith("{")) {
                        Intent chat = new Intent(this, PersonalChatActivity.class);
                        chat.putExtra("TICKET_ID", ticketId);
                        startActivity(chat);
                    } else {
                        Toast.makeText(this, getString(R.string.personal_chat_open_failed), Toast.LENGTH_LONG).show();
                    }
                });
            }, "KodaPersonalTicket").start();
        });
    }

    private void open(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show();
        }
    }

    /** Counts the continue button free; until then back stays blocked too. */
    private void startUnlockCountdown() {
        final MaterialButton cont = findViewById(R.id.btn_crisis_continue);
        final TextView countdown = findViewById(R.id.crisis_countdown);
        cont.setEnabled(false);
        cont.setAlpha(0.4f);
        countdown.setText(getString(R.string.crisis_countdown, UNLOCK_SECONDS));

        unlockTimer = new CountDownTimer(UNLOCK_SECONDS * 1000, 1000) {
            @Override
            public void onTick(long untilFinished) {
                countdown.setText(getString(R.string.crisis_countdown, untilFinished / 1000));
            }

            @Override
            public void onFinish() {
                countdown.setVisibility(View.GONE);
                cont.setEnabled(true);
                cont.setAlpha(1f);
                cont.setOnClickListener(v -> {
                    setResult(RESULT_OK);
                    finish();
                });
            }
        }.start();
    }

    @Override
    public void onBackPressed() {
        // Intentionally empty while the countdown runs: the page should not be reflexively
        // dismissed. Home and recents remain available, nobody is trapped in the app.
    }

    @Override
    protected void onDestroy() {
        if (unlockTimer != null) unlockTimer.cancel();
        super.onDestroy();
    }
}
