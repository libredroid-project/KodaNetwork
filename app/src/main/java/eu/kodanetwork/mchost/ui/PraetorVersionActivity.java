package eu.kodanetwork.mchost.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

import java.io.File;

import eu.kodanetwork.mchost.R;
import eu.kodanetwork.mchost.model.ServerInstance;
import eu.kodanetwork.mchost.model.ServerRepo;
import eu.kodanetwork.mchost.util.BackupManager;
import eu.kodanetwork.mchost.util.MinecraftVersion;
import eu.kodanetwork.mchost.util.PaperMCDownloader;

/**
 * P.R.A.E.T.O.R. screen for changing the Minecraft version of a server.
 *
 * Downgrading is the dangerous direction: worlds and chunk formats are not backwards compatible,
 * so a backup is mandatory and the only way forward is "back up and continue" or cancel.
 * Upgrading is normally fine but plugins can lag behind, so the backup is offered, not forced.
 */
public class PraetorVersionActivity extends AppCompatActivity {

    public static final String EXTRA_SERVER_ID = "SERVER_ID";
    public static final String EXTRA_TARGET_VERSION = "TARGET_VERSION";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ServerInstance server;
    private String targetVersion;
    private boolean downgrade;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_praetor_version);

        String serverId = getIntent().getStringExtra(EXTRA_SERVER_ID);
        targetVersion = getIntent().getStringExtra(EXTRA_TARGET_VERSION);
        server = serverId == null ? null : ServerRepo.get(this).byId(serverId);
        if (server == null || targetVersion == null) {
            finish();
            return;
        }
        downgrade = MinecraftVersion.compare(targetVersion, server.getVersion()) < 0;

        TextView title = findViewById(R.id.praetor_title);
        String praetorHtml = "<font color=\"#555555\">P.R.</font><font color=\"#AAAAAA\">A.E.T.</font>"
                + "<font color=\"#FFFFFF\">O.R.</font>";
        title.setText(android.text.Html.fromHtml(praetorHtml, android.text.Html.FROM_HTML_MODE_LEGACY));

        TextView headline = findViewById(R.id.tv_change_headline);
        headline.setText(downgrade ? R.string.praetor_version_downgrade : R.string.praetor_version_upgrade);

        TextView versions = findViewById(R.id.tv_change_versions);
        versions.setText(server.getVersion() + "  →  " + targetVersion);

        TextView reason = findViewById(R.id.tv_change_reason);
        reason.setText(downgrade ? R.string.praetor_version_downgrade_reason : R.string.praetor_version_upgrade_reason);

        TextView backupNote = findViewById(R.id.tv_change_backup_note);
        backupNote.setText(downgrade
                ? R.string.praetor_version_backup_required : R.string.praetor_version_backup_optional);

        MaterialButton confirm = findViewById(R.id.btn_change_confirm);
        MaterialButton withoutBackup = findViewById(R.id.btn_change_without_backup);
        MaterialButton cancel = findViewById(R.id.btn_change_cancel);

        confirm.setText(downgrade ? R.string.praetor_version_confirm_downgrade : R.string.praetor_version_confirm_upgrade);
        confirm.setOnClickListener(v -> start(true));

        // No way around the backup while downgrading
        withoutBackup.setVisibility(downgrade ? View.GONE : View.VISIBLE);
        withoutBackup.setOnClickListener(v -> start(false));

        cancel.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });
    }

    /** Creates the backup (when asked for) and swaps the server jar for the new version. */
    private void start(final boolean withBackup) {
        final TextView progress = findViewById(R.id.tv_change_progress);
        findViewById(R.id.btn_change_confirm).setEnabled(false);
        findViewById(R.id.btn_change_without_backup).setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        progress.setText(getString(withBackup ? R.string.praetor_version_step_backup : R.string.praetor_version_step_download));

        new Thread(() -> {
            String error = null;
            boolean success = false;
            try {
                if (withBackup) {
                    File backup = BackupManager.createBackup(this, server, "before_" + targetVersion);
                    if (backup == null) {
                        error = getString(R.string.praetor_version_backup_failed);
                    } else {
                        BackupManager.rotate(this, server.getId(), server.getBackupKeep());
                    }
                }
                if (error == null) {
                    handler.post(() -> progress.setText(getString(R.string.praetor_version_step_download)));
                    File jar = PaperMCDownloader.downloadLatestPaperSync(targetVersion,
                            new File(server.getServerDir()), null);
                    success = jar != null && jar.exists();
                    if (!success) error = getString(R.string.version_switch_failed);
                }
            } catch (Exception e) {
                error = e.getMessage();
            }

            final String failure = error;
            final boolean done = success;
            handler.post(() -> {
                if (done) {
                    server.setVersion(targetVersion);
                    ServerRepo.get(this).update(server);
                    Toast.makeText(this, getString(R.string.version_switch_done, targetVersion),
                            Toast.LENGTH_LONG).show();
                    setResult(RESULT_OK);
                    finish();
                } else {
                    progress.setVisibility(View.GONE);
                    findViewById(R.id.btn_change_confirm).setEnabled(true);
                    findViewById(R.id.btn_change_without_backup).setEnabled(true);
                    Toast.makeText(this, failure != null ? failure : getString(R.string.version_switch_failed),
                            Toast.LENGTH_LONG).show();
                }
            });
        }, "KodaVersionChange").start();
    }
}
