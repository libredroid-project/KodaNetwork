/*
 * Copyright (c) 2026 KodaHosting
 *
 * This file is part of KodaHosting (KodaNetwork).
 * KodaHosting is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation, version 3 of the License.
 *
 * KodaHosting is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY, without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * KodaHosting. If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-FileCopyrightText: 2026 KodaHosting
 * SPDX-License-Identifier: GPL-3.0-only
 */
package eu.kodanetwork.mchost.ui;

import android.content.ContentValues;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.res.ResourcesCompat;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import eu.kodanetwork.mchost.R;
import eu.kodanetwork.mchost.util.DataRequests;
import eu.kodanetwork.mchost.util.HapticUtil;
import eu.kodanetwork.mchost.util.KodaSheet;
import eu.kodanetwork.mchost.util.ThemeHelper;

/**
 * my data requests: what is pending, what was decided, and the download for a
 * finished export. the request itself always needs an admin decision.
 */
public class DataRequestActivity extends AppCompatActivity {

    private LinearLayout list;
    private View empty;
    private float d;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeHelper.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_data_requests);

        d = getResources().getDisplayMetrics().density;
        list = findViewById(R.id.layout_requests);
        empty = findViewById(R.id.tv_empty);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_request_export).setOnClickListener(v -> createRequest(DataRequests.EXPORT));
        findViewById(R.id.btn_request_delete).setOnClickListener(v -> confirmDeleteRequest());

        empty.setVisibility(View.GONE);
        reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // a decision may have happened while the screen sat in the background
        reload();
    }

    private void reload() {
        new Thread(() -> {
            final List<DataRequests.Request> rows = DataRequests.list(this);
            runOnUiThread(() -> renderRows(rows));
        }, "KodaDataRequests").start();
    }

    private void renderRows(List<DataRequests.Request> rows) {
        list.removeAllViews();
        empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) list.addView(divider());
            list.addView(row(rows.get(i)));
        }
    }

    private View divider() {
        View line = new View(this);
        line.setBackgroundColor(0xFF2A2226);
        line.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, (int) d)));
        return line;
    }

    private View row(final DataRequests.Request request) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, (int) (14 * d), 0, (int) (14 * d));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText(DataRequests.EXPORT.equals(request.kind)
                ? getString(R.string.data_request_kind_export)
                : getString(R.string.data_request_kind_delete));
        title.setTextColor(0xFFF5F5F7);
        title.setTextSize(15);
        title.setTypeface(ResourcesCompat.getFont(this, R.font.font_koda), Typeface.BOLD);
        title.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(title);

        TextView status = new TextView(this);
        status.setText(DataRequests.statusLabel(this, request.status));
        status.setTextColor(DataRequests.statusColor(request.status));
        status.setTextSize(11);
        status.setTypeface(ResourcesCompat.getFont(this, R.font.font_koda));
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(10 * d);
        pill.setColor(0xFF1C1C22);
        status.setBackground(pill);
        status.setPadding((int) (10 * d), (int) (4 * d), (int) (10 * d), (int) (4 * d));
        head.addView(status);

        box.addView(head);

        TextView meta = new TextView(this);
        meta.setText(dateLabel(request.requestedAt));
        meta.setTextColor(0xFF8A8A9A);
        meta.setTextSize(11.5f);
        meta.setTypeface(ResourcesCompat.getFont(this, R.font.font_koda));
        meta.setPadding(0, (int) (3 * d), 0, 0);
        box.addView(meta);

        if (request.adminNote != null && !request.adminNote.trim().isEmpty()) {
            TextView note = new TextView(this);
            note.setText(request.adminNote);
            note.setTextColor(0xFFBCBCC6);
            note.setTextSize(12.5f);
            note.setTypeface(ResourcesCompat.getFont(this, R.font.font_koda));
            note.setPadding(0, (int) (6 * d), 0, 0);
            box.addView(note);
        }

        boolean pending = "PENDING".equalsIgnoreCase(request.status);
        boolean ready = request.hasExport && "FULFILLED".equalsIgnoreCase(request.status)
                && (request.exportExpiresAt == 0 || request.exportExpiresAt > System.currentTimeMillis());

        if (pending || ready) {
            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setPadding(0, (int) (10 * d), 0, 0);

            if (ready) {
                TextView download = action(getString(R.string.data_request_download), 0xFFFF8C38);
                download.setOnClickListener(v -> download(request));
                actions.addView(download);
            }
            if (pending) {
                TextView cancel = action(getString(R.string.data_request_cancel), 0xFF8A8A9A);
                cancel.setOnClickListener(v -> {
                    HapticUtil.forceVibrate(this, 40);
                    new Thread(() -> {
                        boolean ok = DataRequests.cancel(this, request.id);
                        runOnUiThread(() -> {
                            if (ok) reload();
                            else Toast.makeText(this, R.string.data_request_failed, Toast.LENGTH_SHORT).show();
                        });
                    }, "KodaDataCancel").start();
                });
                actions.addView(cancel);
            }
            box.addView(actions);
        }

        return box;
    }

    private TextView action(String label, int color) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(color);
        tv.setTextSize(12.5f);
        tv.setTypeface(ResourcesCompat.getFont(this, R.font.font_koda));
        tv.setPadding(0, (int) (6 * d), (int) (20 * d), (int) (6 * d));
        tv.setClickable(true);
        tv.setFocusable(true);
        return tv;
    }

    private String dateLabel(long timestamp) {
        if (timestamp <= 0) return "";
        return new SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(new Date(timestamp));
    }

    private void createRequest(final String kind) {
        HapticUtil.forceVibrate(this, 60);
        new Thread(() -> {
            DataRequests.CreateResult result = DataRequests.create(this, kind, "");
            runOnUiThread(() -> {
                if (result.ok) {
                    Toast.makeText(this, R.string.data_request_created, Toast.LENGTH_LONG).show();
                    reload();
                } else {
                    Toast.makeText(this, DataRequests.errorMessage(result.error),
                            Toast.LENGTH_LONG).show();
                }
            });
        }, "KodaDataCreate").start();
    }

    /** the full delete is the only request with its own confirmation. */
    private void confirmDeleteRequest() {
        HapticUtil.forceVibrate(this, 80);
        final KodaSheet sheet = KodaSheet.create(this,
                getString(R.string.data_request_delete_confirm_title),
                getString(R.string.data_request_delete_confirm_body));
        sheet.primaryWithCountdown(getString(R.string.data_request_delete), 5, v -> {
            sheet.sheet.dismiss();
            createRequest(DataRequests.DELETE);
        });
        sheet.secondary(getString(R.string.praetor_action_cancel), v -> sheet.sheet.dismiss());
        sheet.show();
    }

    private void download(final DataRequests.Request request) {
        Toast.makeText(this, R.string.data_request_downloading, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String link = DataRequests.exportUrl(this, request.id);
            byte[] bytes = link == null ? null : DataRequests.download(link);
            if (bytes != null) saveToDownloads(bytes, request.id);
            runOnUiThread(() -> Toast.makeText(this,
                    bytes != null ? R.string.data_request_saved : R.string.data_request_download_failed,
                    Toast.LENGTH_LONG).show());
        }, "KodaDataDownload").start();
    }

    private void saveToDownloads(byte[] data, String requestId) {
        try {
            String name = "kodahosting-export-" + requestId + ".zip";
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
            if (Build.VERSION.SDK_INT >= 29) {
                values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_DOWNLOADS);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new java.io.IOException("downloads uri missing");
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    if (os == null) throw new java.io.IOException("stream missing");
                    os.write(data);
                }
            } else {
                java.io.File dir = android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS);
                java.io.File file = new java.io.File(dir, name);
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(file)) {
                    fos.write(data);
                }
            }
        } catch (Exception ignored) {
        }
    }
}
