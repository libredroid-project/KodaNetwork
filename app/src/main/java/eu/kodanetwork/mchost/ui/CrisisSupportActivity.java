package eu.kodanetwork.mchost.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
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

    private ObjectAnimator breatheAnimator;
    private CountDownTimer unlockTimer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Material3ThemeHelper.applyTheme(this);
        setContentView(R.layout.activity_crisis_support);

        startBreathing();
        wireHelpButtons();
        startUnlockCountdown();
    }

    /** The heart grows while breathing in, holds, shrinks while breathing out - slow and calm. */
    private void startBreathing() {
        final android.widget.ImageView heart = findViewById(R.id.crisis_heart);
        final TextView label = findViewById(R.id.breathing_label);
        if (heart == null) return;

        final String in = getString(R.string.crisis_breathe_in);
        final String hold = getString(R.string.crisis_breathe_hold);
        final String out = getString(R.string.crisis_breathe_out);
        label.setText(in);

        final Runnable[] breath = new Runnable[1];
        breath[0] = new Runnable() {
            @Override
            public void run() {
                label.setText(in);
                ObjectAnimator grow = ObjectAnimator.ofFloat(heart, View.SCALE_X, 1f, 1.5f);
                grow.setDuration(4000);
                grow.setInterpolator(new DecelerateInterpolator());
                ObjectAnimator growY = ObjectAnimator.ofFloat(heart, View.SCALE_Y, 1f, 1.5f);
                growY.setDuration(4000);
                growY.setInterpolator(new DecelerateInterpolator());
                grow.start();
                growY.start();

                heart.postDelayed(() -> label.setText(hold), 4000);
                heart.postDelayed(() -> {
                    label.setText(out);
                    ObjectAnimator shrink = ObjectAnimator.ofFloat(heart, View.SCALE_X, 1.5f, 1f);
                    shrink.setDuration(4000);
                    shrink.setInterpolator(new DecelerateInterpolator());
                    ObjectAnimator shrinkY = ObjectAnimator.ofFloat(heart, View.SCALE_Y, 1.5f, 1f);
                    shrinkY.setDuration(4000);
                    shrinkY.setInterpolator(new DecelerateInterpolator());
                    shrink.start();
                    shrinkY.start();
                    shrink.addListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            heart.postDelayed(breath[0], 2000);
                        }
                    });
                }, 8000);
            }
        };
        breath[0].run();
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
        findViewById(R.id.btn_crisis_support).setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            startActivity(new Intent(this, CreateSupportTicketActivity.class));
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
        android.widget.ImageView heart = findViewById(R.id.crisis_heart);
        if (heart != null) heart.getHandler().removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
