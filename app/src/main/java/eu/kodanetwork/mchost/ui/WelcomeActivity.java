package eu.kodanetwork.mchost.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import eu.kodanetwork.mchost.R;
import eu.kodanetwork.mchost.util.Material3ThemeHelper;

/**
 * First setup, text-based: no cinematic animation, just honest words.
 *
 * Steps: welcome -> legal (ToS + privacy) -> background permissions -> old server recovery ->
 * nickname -> optional account (login finds servers from previous installations) -> done.
 *
 * The recovery check runs right after the legal step, in the same pass: the device token is
 * bootstrapped here and the cloud servers are fetched immediately, so nobody has to open and
 * close the app three times to be asked about their old servers.
 */
public class WelcomeActivity extends AppCompatActivity {

    private static final int REQ_LOGIN = 9201;
    private static final int REQ_REGISTER = 9202;
    private static final int REQ_RECOVERY_ZIP = 9203;

    private List<JSONObject> cloudServers = new ArrayList<>();
    private boolean recoveryChecked = false;
    private JSONObject pendingZipServer;
    private List<JSONObject> imported = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Material3ThemeHelper.applyTheme(this);
        setContentView(R.layout.activity_welcome);

        setupWelcomeStep();
        setupLegalStep();
        setupBackgroundStep();
        setupRecoveryStep();
        setupNicknameStep();
        setupAccountStep();
        setupDoneStep();
    }

    // ---------------------------------------------------------------- helpers

    private void showStep(int id) {
        int[] steps = {R.id.step_welcome, R.id.step_legal, R.id.step_background,
                R.id.step_recovery, R.id.step_nickname, R.id.step_account, R.id.step_done};
        for (int step : steps) {
            View v = findViewById(step);
            if (v != null) v.setVisibility(step == id ? View.VISIBLE : View.GONE);
        }
        findViewById(R.id.welcome_root).scrollTo(0, 0);
        // The old-server check starts the moment the step appears - onResume alone
        // would only catch returns from other apps, not the step change itself
        if (id == R.id.step_recovery && !recoveryChecked) {
            recoveryChecked = true;
            runRecoveryCheck();
        }
    }

    private String prefs() {
        return eu.kodanetwork.mchost.App.getPrefs(this).getString("app_uuid", "");
    }

    private String baseUrl() {
        return eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseUrl();
    }

    private String apiKey() {
        return eu.kodanetwork.mchost.security.PraetorSecurity.getSupabaseKey();
    }

    private String rpc(String path, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(baseUrl() + path).openConnection();
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("apikey", apiKey());
        c.setRequestProperty("Authorization", "Bearer " + apiKey());
        c.setDoOutput(true);
        c.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        int code = c.getResponseCode();
        java.util.Scanner sc = new java.util.Scanner(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream()).useDelimiter("\\A");
        return sc.hasNext() ? sc.next() : "";
    }

    /** Bootstraps the device token (the first rpc_get_is_banned creates it server-side). */
    private boolean bootstrapDeviceToken() {
        // Token may already exist from a previous pass or from MainActivity's check
        if (!eu.kodanetwork.mchost.App.getPrefs(this).getString("device_token", "").isEmpty()) {
            return true;
        }
        try {
            String uuid = prefs();
            String resp = rpc("/rest/v1/rpc/rpc_get_is_banned",
                    "{\"p_app_uuid\":\"" + uuid + "\"}");
            JSONObject obj = new JSONObject(resp);
            if (obj.has("device_token")) {
                String token = obj.getString("device_token");
                if (!token.isEmpty()) {
                    eu.kodanetwork.mchost.App.getPrefs(this).edit().putString("device_token", token).apply();
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    // ---------------------------------------------------------------- step 1

    private void setupWelcomeStep() {
        findViewById(R.id.btn_welcome_next).setOnClickListener(v -> {
            eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 20);
            showStep(R.id.step_legal);
        });
    }

    // ---------------------------------------------------------------- step 2

    private void setupLegalStep() {
        TextView legal = findViewById(R.id.tv_legal_text);
        // Short summary with links, nobody reads a wall of legal text in a setup wizard
        legal.setText(android.text.Html.fromHtml(getString(R.string.welcome_legal_summary),
                android.text.Html.FROM_HTML_MODE_LEGACY));
        legal.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());

        MaterialCheckBox cb = findViewById(R.id.cb_legal_accept);
        MaterialButton accept = findViewById(R.id.btn_legal_accept);
        accept.setEnabled(false);
        accept.setAlpha(0.4f);
        cb.setOnCheckedChangeListener((b, checked) -> {
            accept.setEnabled(checked);
            accept.setAlpha(checked ? 1f : 0.4f);
        });
        accept.setOnClickListener(v -> {
            eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 30);
            eu.kodanetwork.mchost.App.getPrefs(this).edit()
                    .putBoolean("tos_accepted_v3", true)
                    .putLong("accepted_tos_version_ts", System.currentTimeMillis())
                    .apply();
            showStep(R.id.step_background);
        });
    }

    // ---------------------------------------------------------------- step 3

    private void setupBackgroundStep() {
        // Notifications first (system dialog), battery opens the settings page
        findViewById(R.id.btn_battery).setOnClickListener(v -> {
            eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 20);
            try {
                if (android.os.Build.VERSION.SDK_INT >= 33
                        && ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 91);
                }
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
                Toast.makeText(this, getString(R.string.welcome_battery_toast), Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, getString(R.string.welcome_battery_failed), Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.btn_background_skip).setOnClickListener(v -> showStep(R.id.step_recovery));
        // returning from battery settings continues the flow via onResume
    }

    // ---------------------------------------------------------------- step 4

    private void setupRecoveryStep() {
        findViewById(R.id.btn_recovery_import).setOnClickListener(v -> {
            eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 30);
            importCloudServers();
        });
        findViewById(R.id.btn_recovery_delete).setOnClickListener(v -> {
            eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 30);
            deleteCloudServers();
        });
        findViewById(R.id.btn_recovery_next).setOnClickListener(v -> showStep(R.id.step_nickname));
    }

    /** Runs as soon as the recovery step becomes visible (called from onResume first time). */
    private void runRecoveryCheck() {
        TextView status = findViewById(R.id.tv_recovery_status);
        status.setText(getString(R.string.welcome_recovery_checking));
        new Thread(() -> {
            boolean tokenOk = bootstrapDeviceToken();
            List<JSONObject> found = tokenOk ? fetchCloudServerCandidates() : new ArrayList<>();
            runOnUiThread(() -> {
                cloudServers = found;
                if (!found.isEmpty()) {
                    StringBuilder names = new StringBuilder();
                    for (int i = 0; i < found.size() && i < 5; i++) {
                        if (i > 0) names.append(", ");
                        names.append(found.get(i).optString("host", "?"));
                    }
                    status.setText(getString(R.string.welcome_recovery_found, found.size(), names.toString()));
                    findViewById(R.id.ll_recovery_actions).setVisibility(View.VISIBLE);
                } else if (!tokenOk) {
                    status.setText(getString(R.string.welcome_recovery_offline));
                    findViewById(R.id.btn_recovery_next).setVisibility(View.VISIBLE);
                } else {
                    status.setText(getString(R.string.welcome_recovery_none));
                    findViewById(R.id.btn_recovery_next).setVisibility(View.VISIBLE);
                }
            });
        }, "KodaWelcomeRecovery").start();
    }

    private List<JSONObject> fetchCloudServerCandidates() {
        List<JSONObject> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String appUuid = prefs();
        String deviceToken = eu.kodanetwork.mchost.App.getPrefs(this).getString("device_token", "");
        if (appUuid.isEmpty() || deviceToken.isEmpty()) return out;

        String[][] calls = {
                {"/rest/v1/rpc/rpc_get_my_servers", "{\"p_app_uuid\":\"" + appUuid + "\",\"p_device_token\":\"" + deviceToken + "\"}"},
                {"/rest/v1/rpc/rpc_get_servers_by_auth_id", "{\"p_app_uuid\":\"" + appUuid + "\",\"p_device_token\":\"" + deviceToken + "\"}"},
        };
        for (String[] call : calls) {
            try {
                String resp = rpc(call[0], call[1]);
                JSONArray arr = new JSONArray(resp);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject row = arr.getJSONObject(i);
                    String host = row.optString("host", "");
                    String ver = row.optString("server_version", "");
                    if (host.isEmpty() || host.startsWith("deleted_") || "DELETED".equals(ver)) continue;
                    if (seen.add(host)) out.add(row);
                }
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** Adopts the cloud rows and asks for a world ZIP per server (like the old recovery flow). */
    private void importCloudServers() {
        TextView status = findViewById(R.id.tv_recovery_status);
        status.setText(getString(R.string.welcome_recovery_importing));
        findViewById(R.id.ll_recovery_actions).setVisibility(View.GONE);
        new Thread(() -> {
            boolean adopted = adoptServers();
            // Create local placeholder instances so the ZIP import has a target
            for (JSONObject row : cloudServers) {
                createPlaceholder(row);
            }
            imported = new ArrayList<>(cloudServers);
            runOnUiThread(() -> {
                eu.kodanetwork.mchost.App.getPrefs(this).edit()
                        .putBoolean("recovery_flow_done_v1", true).apply();
                Toast.makeText(this, getString(R.string.welcome_recovery_adopted, cloudServers.size()), Toast.LENGTH_LONG).show();
                promptNextZip();
            });
        }, "KodaWelcomeImport").start();
    }

    private boolean adoptServers() {
        try {
            String appUuid = prefs();
            String token = eu.kodanetwork.mchost.App.getPrefs(this).getString("device_token", "");
            rpc("/rest/v1/rpc/rpc_adopt_servers",
                    "{\"p_app_uuid\":\"" + appUuid + "\",\"p_device_token\":\"" + token + "\"}");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void createPlaceholder(JSONObject row) {
        try {
            String host = row.optString("host", "server");
            String id = UUID.randomUUID().toString();
            java.io.File dir = new java.io.File(getFilesDir(), "servers/" + id);
            if (!dir.exists()) dir.mkdirs();

            JSONObject s = new JSONObject();
            s.put("id", id);
            s.put("name", host);
            s.put("type", "PAPER");
            s.put("version", row.optString("server_version", "").replace("OFFLINE|", ""));
            s.put("ramMB", 2048);
            s.put("port", 30000 + new java.util.Random().nextInt(9999));
            s.put("serverDir", dir.getAbsolutePath());
            s.put("state", "HIBERNATED");

            // append to the server list (same storage the MainActivity reads)
            String raw = getSharedPreferences("koda_v3", MODE_PRIVATE).getString("servers", "[]");
            JSONArray arr = new JSONArray(raw);
            arr.put(s);
            getSharedPreferences("koda_v3", MODE_PRIVATE).edit().putString("servers", arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /** One SAF picker per imported server, until every server got its ZIP (or was skipped). */
    private void promptNextZip() {
        JSONObject next = null;
        for (JSONObject row : imported) {
            if (row != null) {
                next = row;
                break;
            }
        }
        if (next == null) {
            finishRecovery(getString(R.string.welcome_recovery_done));
            return;
        }
        pendingZipServer = next;
        imported.remove(next);
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        String host = next.optString("host", "server");
        Toast.makeText(this, getString(R.string.welcome_recovery_zip_hint, host), Toast.LENGTH_LONG).show();
        try {
            startActivityForResult(intent, REQ_RECOVERY_ZIP);
        } catch (Exception e) {
            finishRecovery(getString(R.string.welcome_recovery_done));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_RECOVERY_ZIP) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null && pendingZipServer != null) {
                // Actually extract the picked ZIP into the server directory
                extractZipToServer(data.getData(), pendingZipServer);
            }
            promptNextZip();
            return;
        }
        if ((requestCode == REQ_LOGIN || requestCode == REQ_REGISTER) && resultCode == RESULT_OK) {
            // Login done: sync auth_id and check the family for more old servers
            syncAuthAndCheckFamily();
        }
    }

    private void deleteCloudServers() {
        TextView status = findViewById(R.id.tv_recovery_status);
        status.setText(getString(R.string.welcome_recovery_deleting));
        findViewById(R.id.ll_recovery_actions).setVisibility(View.GONE);
        new Thread(() -> {
            String token = eu.kodanetwork.mchost.App.getPrefs(this).getString("device_token", "");
            for (JSONObject row : cloudServers) {
                String host = row.optString("host", "");
                if (host.isEmpty()) continue;
                try {
                    rpc("/rest/v1/rpc/rpc_patch_server_by_id",
                            "{\"p_app_uuid\":\"" + prefs() + "\",\"p_device_token\":\"" + token
                                    + "\",\"p_host\":\"" + host + "\",\"p_payload\":{\"host\":\"deleted_" + host + "\",\"server_version\":\"DELETED\"}}");
                } catch (Exception ignored) {
                }
            }
            runOnUiThread(() -> {
                eu.kodanetwork.mchost.App.getPrefs(this).edit()
                        .putBoolean("recovery_flow_done_v1", true).apply();
                finishRecovery(getString(R.string.welcome_recovery_deleted, cloudServers.size()));
            });
        }, "KodaWelcomeDelete").start();
    }

    /**
     * Extracts the picked ZIP into the server directory and flips the placeholder to OFFLINE,
     * so the server is actually playable after the setup (not a dead HIBERNATED stub).
     */
    private void extractZipToServer(android.net.Uri uri, JSONObject serverRow) {
        final String host = serverRow.optString("host", "server");
        final String targetId = findOrCreateLocalId(host);
        if (targetId == null) return;
        final java.io.File targetDir = new java.io.File(new java.io.File(getFilesDir(), "servers"), targetId);

        new Thread(() -> {
            boolean ok = false;
            try {
                targetDir.mkdirs();
                String canonicalBase = targetDir.getCanonicalPath() + java.io.File.separator;
                java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
                        getContentResolver().openInputStream(uri));

                // Detect the common first directory (the ZIP contains "uuid/server.properties",
                // because zipFolder prepends the source directory name). Strip it so files
                // land directly in the server directory, not in a nested UUID folder.
                String firstDir = null;
                java.util.zip.ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    String name = entry.getName();
                    int slash = name.indexOf('/');
                    if (slash > 0) {
                        String candidate = name.substring(0, slash);
                        if (firstDir == null) {
                            firstDir = candidate;
                        } else if (!firstDir.equals(candidate)) {
                            firstDir = null; // mixed roots -> no stripping
                            break;
                        }
                    } else {
                        firstDir = null; // file at root -> no wrapping directory
                        break;
                    }
                    zis.closeEntry();
                }

                // Reopen: the stream was consumed for detection
                zis.close();
                zis = new java.util.zip.ZipInputStream(getContentResolver().openInputStream(uri));
                String stripPrefix = firstDir != null ? firstDir + "/" : "";

                byte[] buf = new byte[8192];
                while ((entry = zis.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (name.startsWith(stripPrefix)) {
                        name = name.substring(stripPrefix.length());
                    }
                    if (name.isEmpty()) {
                        zis.closeEntry();
                        continue;
                    }
                    java.io.File out = new java.io.File(targetDir, name);
                    String canonical = out.getCanonicalPath();
                    if (!canonical.startsWith(canonicalBase) && !canonical.equals(targetDir.getCanonicalPath())) {
                        continue; // Zip-Slip
                    }
                    if (entry.isDirectory()) {
                        out.mkdirs();
                    } else {
                        out.getParentFile().mkdirs();
                        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                            int n;
                            while ((n = zis.read(buf)) > 0) fos.write(buf, 0, n);
                        }
                    }
                    zis.closeEntry();
                }
                zis.close();
                ok = true;
            } catch (Exception e) {
                android.util.Log.e("Welcome", "zip extract failed", e);
            }

            // Server-Zustand auf OFFLINE setzen (nicht HIBERNATED) damit er startbar ist
            final boolean success = ok;
            runOnUiThread(() -> {
                updateLocalServerState(targetId, success ? "OFFLINE" : "HIBERNATED");
                Toast.makeText(this, success
                        ? getString(R.string.welcome_recovery_zip_ok, host)
                        : getString(R.string.welcome_recovery_zip_fail, host),
                        Toast.LENGTH_SHORT).show();
            });
        }, "KodaWelcomeZip").start();
    }

    /** Finds the local server ID for a host name, or returns null. */
    private String findOrCreateLocalId(String host) {
        try {
            String raw = getSharedPreferences("koda_v3", MODE_PRIVATE).getString("servers", "[]");
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.getJSONObject(i);
                if (host.equals(s.optString("name", "")) || host.equals(s.optString("subdomain", ""))) {
                    return s.optString("id", null);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Sets the state of a local server entry by its ID. */
    private void updateLocalServerState(String id, String state) {
        try {
            String raw = getSharedPreferences("koda_v3", MODE_PRIVATE).getString("servers", "[]");
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.getJSONObject(i);
                if (id.equals(s.optString("id", ""))) {
                    s.put("state", state);
                    break;
                }
            }
            getSharedPreferences("koda_v3", MODE_PRIVATE).edit().putString("servers", arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private void finishRecovery(String message) {
        TextView status = findViewById(R.id.tv_recovery_status);
        status.setText(message);
        findViewById(R.id.btn_recovery_next).setVisibility(View.VISIBLE);
    }

    // ---------------------------------------------------------------- step 5

    private void setupNicknameStep() {
        findViewById(R.id.btn_nickname_next).setOnClickListener(v -> {
            eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 20);
            String nickname = ((android.widget.EditText) findViewById(R.id.et_nickname)).getText().toString().trim();
            eu.kodanetwork.mchost.App.getPrefs(this).edit().putString("nickname", nickname).apply();
            if (!nickname.isEmpty()) {
                new Thread(() -> {
                    try {
                        String token = eu.kodanetwork.mchost.App.getPrefs(this).getString("device_token", "");
                        if (token.isEmpty()) return;
                        JSONObject payload = new JSONObject().put("nickname", nickname);
                        rpc("/rest/v1/rpc/rpc_patch_user",
                                "{\"p_app_uuid\":\"" + prefs() + "\",\"p_device_token\":\"" + token
                                        + "\",\"p_payload\":" + payload + "}");
                    } catch (Exception ignored) {
                    }
                }, "KodaWelcomeNick").start();
            }
            showStep(R.id.step_account);
        });
    }

    // ---------------------------------------------------------------- step 6

    private void setupAccountStep() {
        findViewById(R.id.btn_account_login).setOnClickListener(v -> {
            eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 20);
            Intent intent = new Intent(this, eu.kodanetwork.mchost.ui.design.LoginPageActivity.class);
            intent.putExtra("RETURN_TO_WELCOME", true);
            startActivityForResult(intent, REQ_LOGIN);
        });
        findViewById(R.id.btn_account_register).setOnClickListener(v -> {
            eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 20);
            Intent intent = new Intent(this, eu.kodanetwork.mchost.ui.design.RegisterPageActivity.class);
            intent.putExtra("RETURN_TO_WELCOME", true);
            startActivityForResult(intent, REQ_REGISTER);
        });
        findViewById(R.id.btn_account_skip).setOnClickListener(v -> finishSetup());
    }

    /** After login: link auth_id, then look for servers that hang on the old account. */
    private void syncAuthAndCheckFamily() {
        Toast.makeText(this, getString(R.string.welcome_account_linked), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                String token = eu.kodanetwork.mchost.App.getPrefs(this).getString("device_token", "");
                String session = eu.kodanetwork.mchost.App.getPrefs(this).getString("koda_session_token", "");
                if (token.isEmpty() || session.isEmpty()) return;
                // sync_auth_id links the login to this device row
                HttpURLConnection c = (HttpURLConnection) new URL(baseUrl() + "/rest/v1/rpc/rpc_sync_auth_id").openConnection();
                c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type", "application/json");
                c.setRequestProperty("apikey", apiKey());
                c.setRequestProperty("Authorization", "Bearer " + session);
                c.setDoOutput(true);
                String body = "{\"p_app_uuid\":\"" + prefs() + "\",\"p_device_token\":\"" + token + "\"}";
                c.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
                c.getResponseCode();
            } catch (Exception ignored) {
            }
        }, "KodaWelcomeSync").start();
        finishSetup();
    }

    // ---------------------------------------------------------------- step 7

    private void setupDoneStep() {
        findViewById(R.id.btn_done).setOnClickListener(v -> {
            eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 40);
            finishSetup();
        });
    }

    private void finishSetup() {
        eu.kodanetwork.mchost.App.getPrefs(this).edit()
                .putBoolean("onboarding_v2_done", true)
                .putBoolean("tutorial_completed_v2", true)
                .putBoolean("recovery_flow_done_v1", true)
                .putBoolean("onboarding_bg_done", true)
                .apply();
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    // ---------------------------------------------------------------- onResume bridge

    @Override
    protected void onResume() {
        super.onResume();
        // Coming back from battery settings: if the exception is now active, move on
        // automatically instead of forcing the user to press "Skip"
        View step = findViewById(R.id.step_background);
        if (step != null && step.getVisibility() == View.VISIBLE && isIgnoringBatteryOptimizations()) {
            showStep(R.id.step_recovery);
        }
    }

    private boolean isIgnoringBatteryOptimizations() {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- legal assets (same logic as MainActivity)

    private String legalDocToHtml(String assetName) {
        String raw = readAssetText("licenses/" + assetName);
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder();
        String[] lines = raw.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            boolean followedByBlank = i + 1 >= lines.length || lines[i + 1].trim().isEmpty();
            boolean heading = i == 0
                    || line.matches("\\d+\\.\\s.+")
                    || (line.length() < 64 && !line.endsWith(".") && !line.startsWith("-") && followedByBlank);
            sb.append(heading ? "<b>" : "")
                    .append(android.text.TextUtils.htmlEncode(line))
                    .append(heading ? "</b>" : "")
                    .append("<br>");
            if (followedByBlank) sb.append("<br>");
        }
        return sb.toString();
    }

    private String readAssetText(String path) {
        if (path.startsWith("licenses/") && path.endsWith(".txt") && !path.endsWith("_de.txt")) {
            java.util.Locale loc = getResources().getConfiguration().locale;
            if (loc != null && loc.getLanguage().startsWith("de")) {
                String de = readAssetTextDirect(path.replace(".txt", "_de.txt"));
                if (de != null) return de;
            }
        }
        return readAssetTextDirect(path);
    }

    private String readAssetTextDirect(String path) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(getAssets().open(path)))) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append("\n");
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
