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

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import eu.kodanetwork.mchost.R;
import eu.kodanetwork.mchost.network.supabase.SupportApi;
import eu.kodanetwork.mchost.security.PraetorSecurity;
import eu.kodanetwork.mchost.util.AppLogger;
import eu.kodanetwork.mchost.util.Material3ThemeHelper;
import eu.kodanetwork.mchost.util.ThemeHelper;

/**
 * the support ticket chat: whatsapp style, bubbles floating over the mountain wallpaper
 * like the console panels float over the log.
 *
 * photos only on purpose, and every photo is scaled down to 1440px and re-encoded as
 * jpeg before it leaves the phone, so identifying a screenshot stays easy while the
 * bucket barely eats storage. viewing a photo runs through our own loader inside the
 * app, no browser hop and no external app touching the ticket image.
 *
 * tickets run on the device token like the personal chat, personal ones keep the
 * end to end encryption.
 */
public class SupportChatActivity extends AppCompatActivity {

    private static final int MAX_PHOTO_DIM = 1440;
    private static final int PHOTO_QUALITY = 82;

    private String ticketId;
    private String ticketTitle;
    private String ticketStatus;
    private String ticketType;
    private String myUuid;
    private String deviceToken;

    private RecyclerView rvChat;
    private ChatAdapter adapter;
    private EditText etMessage;
    private LinearLayout llAttachmentPreview;
    private TextView tvAttachmentName;
    private ImageView ivAttachmentPreview;
    private LinearLayout llChatEmpty;
    private com.airbnb.lottie.LottieAnimationView lottieWait;
    private LinearLayout llConnectionBar;
    private TextView tvChatSubtitle;

    /** E2EE key for personal tickets, null while not established or not needed. */
    private byte[] e2eeKey;
    /** true for personal (mental support) tickets: outgoing text gets encrypted. */
    private boolean e2eeTicket;

    private final List<JSONObject> messages = new ArrayList<>();
    private final List<JSONObject> pending = new ArrayList<>();
    private String failedText;
    private String failedAttachmentUrl;
    private boolean connectionProblem;
    private boolean firstLoadDone;
    private boolean userAtBottom = true;
    private String lastSeenStamp = "";

    private Uri selectedAttachmentUri;
    private String selectedAttachmentName;

    private final Handler pollHandler = new Handler(Looper.getMainLooper());
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            loadMessages(false);
            pollHandler.postDelayed(this, 4000);
        }
    };

    private ActivityResultLauncher<Intent> photoPickerLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Material3ThemeHelper.applyTheme(this);
        super.onCreate(savedInstanceState);
        ThemeHelper.apply(this);
        setContentView(Material3ThemeHelper.isM3Enabled(this)
                ? R.layout.activity_support_chat_m3 : R.layout.activity_support_chat);

        ticketId = getIntent().getStringExtra("TICKET_ID");
        ticketTitle = getIntent().getStringExtra("TICKET_TITLE");
        ticketStatus = getIntent().getStringExtra("TICKET_STATUS");
        ticketType = getIntent().getStringExtra("TICKET_TYPE");
        myUuid = eu.kodanetwork.mchost.App.getPrefs(this).getString("app_uuid", null);
        deviceToken = eu.kodanetwork.mchost.App.getPrefs(this).getString("device_token", "");
        if (ticketId == null || myUuid == null) {
            finish();
            return;
        }
        e2eeTicket = "PERSONAL".equalsIgnoreCase(ticketType == null ? "" : ticketType);

        TextView tvTitle = findViewById(R.id.tv_chat_title);
        tvTitle.setText(ticketTitle != null && !ticketTitle.isEmpty()
                ? ticketTitle : getString(R.string.support_ticket_default_title));
        tvChatSubtitle = findViewById(R.id.tv_chat_subtitle);
        refreshSubtitle();

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_export).setOnClickListener(v -> exportChat());

        android.view.View closeRequest = findViewById(R.id.btn_close_request);
        if (closeRequest != null) {
            // schon erledigte Tickets brauchen keinen Schliessen-Wunsch mehr
            boolean resolved = "RESOLVED".equalsIgnoreCase(ticketStatus == null ? "" : ticketStatus);
            closeRequest.setVisibility(resolved ? View.GONE : View.VISIBLE);
            closeRequest.setOnClickListener(v -> requestClose());
        }

        rvChat = findViewById(R.id.rv_chat);
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        rvChat.setLayoutManager(layoutManager);
        rvChat.setItemAnimator(new DefaultItemAnimator());
        adapter = new ChatAdapter();
        rvChat.setAdapter(adapter);
        rvChat.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                // stick to the newest message only while the user is already at the bottom,
                // otherwise reading older messages would be yanked away by every poll
                userAtBottom = !recyclerView.canScrollVertically(1);
            }
        });

        etMessage = findViewById(R.id.et_message);
        llAttachmentPreview = findViewById(R.id.ll_attachment_preview);
        tvAttachmentName = findViewById(R.id.tv_attachment_name);
        ivAttachmentPreview = findViewById(R.id.iv_attachment_preview);
        llChatEmpty = findViewById(R.id.ll_chat_empty);
        lottieWait = findViewById(R.id.lottie_chat_wait);
        llConnectionBar = findViewById(R.id.ll_connection_bar);
        tvChatSubtitle = findViewById(R.id.tv_chat_subtitle);
        llConnectionBar.setOnClickListener(v -> {
            connectionProblem = false;
            llConnectionBar.setVisibility(View.GONE);
            loadMessages(true);
        });

        findViewById(R.id.btn_remove_attachment).setOnClickListener(v -> {
            clearPickedPhoto();
        });

        photoPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        selectedAttachmentUri = uri;
                        selectedAttachmentName = "photo.jpg";
                        tvAttachmentName.setText(selectedAttachmentName);
                        llAttachmentPreview.setVisibility(View.VISIBLE);
                        showPickedPhotoPreview(uri);
                    }
                }
            }
        );

        findViewById(R.id.btn_attach).setOnClickListener(v -> {
            // photos only, everything else would just be storage waste in a support ticket
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            photoPickerLauncher.launch(intent);
        });

        ImageButton send = findViewById(R.id.btn_send);
        send.setOnClickListener(v -> {
            eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 20);
            sendCurrentInput();
        });
        etMessage.setOnEditorActionListener((v, actionId, event) -> {
            eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 20);
            sendCurrentInput();
            return true;
        });

        updateEmptyState();
        setupE2EE();
        loadMessages(true);
    }

    private void refreshSubtitle() {
        if (tvChatSubtitle == null) return;
        String state = ticketStatus == null || ticketStatus.isEmpty()
                ? "OPEN" : ticketStatus.toUpperCase(java.util.Locale.ROOT);
        String type = typeLabel();
        tvChatSubtitle.setText(type + " · " + state);
    }

    private String typeLabel() {
        String type = ticketType == null ? "" : ticketType.toUpperCase(java.util.Locale.ROOT);
        switch (type) {
            case "BUG":
                return getString(R.string.chat_type_bug);
            case "SERVER":
                return getString(R.string.chat_type_server);
            case "PERSONAL":
                return getString(R.string.chat_type_personal);
            default:
                return getString(R.string.chat_type_support);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // the global insets listener from App runs after onCreate, so this one is applied
        // here on purpose: keyboard padding on the whole screen, like the personal chat
        View root = findViewById(R.id.support_chat_root);
        if (root != null) {
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
                androidx.core.graphics.Insets ime =
                        insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime());
                androidx.core.graphics.Insets bars =
                        insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
                int bottom = Math.max(ime.bottom, bars.bottom);
                v.setPadding(0, bars.top, 0, bottom);
                return androidx.core.view.WindowInsetsCompat.CONSUMED;
            });
            root.requestApplyInsets();
        }
        pollHandler.postDelayed(poll, 4000);
    }

    @Override
    protected void onPause() {
        pollHandler.removeCallbacks(poll);
        super.onPause();
    }

    /**
     * Personal tickets stay end to end encrypted (same mechanics as the personal chat):
     * our public key goes up once, the admin key comes back and both sides derive the
     * same shared secret. Regular support tickets stay readable plaintext so the web
     * panel can show them.
     */
    private void setupE2EE() {
        new Thread(() -> {
            try {
                String pub = eu.kodanetwork.mchost.util.E2EE.ensureKeyPair(this);
                if (pub != null && !eu.kodanetwork.mchost.util.E2EE.isUploaded(this)) {
                    JSONObject body = new JSONObject();
                    body.put("p_app_uuid", myUuid);
                    body.put("p_device_token", deviceToken);
                    body.put("p_public_key", pub);
                    SupportApi.makeSupabaseRequest("rest/v1/rpc/rpc_set_my_public_key",
                            "POST", body.toString(), null);
                    eu.kodanetwork.mchost.util.E2EE.markUploaded(this);
                }
                JSONObject body = new JSONObject();
                body.put("p_ticket_id", ticketId);
                body.put("p_reporter_uuid", myUuid);
                body.put("p_device_token", deviceToken);
                String response = SupportApi.makeSupabaseRequest(
                        "rest/v1/rpc/rpc_get_chat_peer_key", "POST", body.toString(), null);
                String peerKey = response.replace("\"", "").trim();
                if (!peerKey.isEmpty() && !peerKey.startsWith("{")) {
                    byte[] shared = eu.kodanetwork.mchost.util.E2EE.sharedKey(this, peerKey);
                    if (shared != null) runOnUiThread(() -> {
                        e2eeKey = shared;
                        adapter.notifyDataSetChanged();
                    });
                }
            } catch (Exception e) {
                // without the peer key the chat still works, personal ones just stay locked
                AppLogger.log("KodaSupport", "peer key failed: " + e.getMessage());
            }
        }, "KodaE2EEAdmin").start();
    }

    private void clearPickedPhoto() {
        selectedAttachmentUri = null;
        selectedAttachmentName = null;
        llAttachmentPreview.setVisibility(View.GONE);
        ivAttachmentPreview.setImageDrawable(null);
    }

    /** small local preview so the picked photo is visible before sending. */
    private void showPickedPhotoPreview(Uri uri) {
        new Thread(() -> {
            Bitmap preview = decodePhoto(uri, 200);
            if (preview == null) return;
            runOnUiThread(() -> ivAttachmentPreview.setImageBitmap(preview));
        }, "KodaSupportPreview").start();
    }

    /**
     * decodes the picked photo at a sane size and applies the exif rotation (ImageDecoder
     * does that for us from android 9 on). returns null when it is no real image.
     */
    private Bitmap decodePhoto(Uri uri, int maxDim) {
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                android.graphics.ImageDecoder.Source source =
                        android.graphics.ImageDecoder.createSource(getContentResolver(), uri);
                return android.graphics.ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
                    int longest = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
                    int sample = 1;
                    while (longest / sample > maxDim) sample *= 2;
                    decoder.setTargetSampleSize(sample);
                    decoder.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE);
                });
            }
            android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                android.graphics.BitmapFactory.decodeStream(is, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int longest = Math.max(bounds.outWidth, bounds.outHeight);
            int sample = 1;
            while (longest / sample > maxDim) sample *= 2;
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inSampleSize = sample;
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                return android.graphics.BitmapFactory.decodeStream(is, null, opts);
            }
        } catch (Exception | OutOfMemoryError e) {
            AppLogger.log("KodaSupport", "photo decode failed: " + e);
            return null;
        }
    }

    private void sendCurrentInput() {
        String text = etMessage.getText().toString().trim();
        if (text.isEmpty() && selectedAttachmentUri == null) return;
        etMessage.setText("");
        if (selectedAttachmentUri != null) {
            Uri uri = selectedAttachmentUri;
            clearPickedPhoto();
            uploadAndSend(text, uri);
        } else {
            sendText(text, null);
        }
    }

    /** uploads the downscaled photo, then sends the message with the link. */
    private void uploadAndSend(String text, Uri uri) {
        addPending(text.isEmpty() ? getString(R.string.chat_attachment) : text, null);
        new Thread(() -> {
            try {
                Bitmap bitmap = decodePhoto(uri, MAX_PHOTO_DIM);
                if (bitmap == null) {
                    failPending(getString(R.string.chat_photo_failed));
                    return;
                }
                byte[] jpeg = toJpeg(bitmap, PHOTO_QUALITY);
                bitmap.recycle();
                if (jpeg == null || jpeg.length == 0) {
                    failPending(getString(R.string.chat_photo_failed));
                    return;
                }
                String fileName = System.currentTimeMillis() + "_photo.jpg";
                HttpURLConnection conn = (HttpURLConnection) new URL(
                        PraetorSecurity.getSupabaseUrl() + "/storage/v1/object/support_attachments/" + fileName)
                        .openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Authorization", "Bearer " + PraetorSecurity.getSupabaseKey());
                conn.setRequestProperty("apikey", PraetorSecurity.getSupabaseKey());
                conn.setRequestProperty("Content-Type", "image/jpeg");
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(jpeg.length);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(jpeg);
                    os.flush();
                }
                int code = conn.getResponseCode();
                if (code == 200 || code == 201) {
                    String url = PraetorSecurity.getSupabaseUrl()
                            + "/storage/v1/object/public/support_attachments/" + fileName;
                    sendText(text, url);
                } else {
                    failPending(getString(R.string.chat_upload_failed) + " (" + code + ")");
                }
            } catch (Exception e) {
                AppLogger.log("KodaSupport", "upload failed: " + e.getMessage());
                failPending(getString(R.string.chat_upload_failed));
            }
        }, "KodaSupportUpload").start();
    }

    /** jpeg bytes, with a second, harder pass when the first one is still huge. */
    private byte[] toJpeg(Bitmap bitmap, int quality) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out);
            byte[] data = out.toByteArray();
            if (data.length > 4 * 1024 * 1024) {
                out.reset();
                bitmap.compress(Bitmap.CompressFormat.JPEG, 60, out);
                data = out.toByteArray();
            }
            return data;
        } catch (Exception e) {
            return null;
        }
    }

    /** own message shows up right away, the send runs in the background. */
    private void sendText(String text, String attachmentUrl) {
        addPending(text, attachmentUrl);
        new Thread(() -> {
            try {
                JSONObject msgObj = new JSONObject();
                msgObj.put("p_ticket_id", ticketId);
                msgObj.put("p_sender_uuid", myUuid);
                msgObj.put("p_device_token", deviceToken);
                String wire = text == null ? "" : text;
                if (e2eeTicket && e2eeKey != null && !wire.isEmpty()) {
                    String encrypted = eu.kodanetwork.mchost.util.E2EE.encrypt(e2eeKey, wire);
                    if (encrypted != null) wire = encrypted;
                }
                if (attachmentUrl != null) {
                    // the photo has its own column since 2026-10-12, the message stays clean
                    msgObj.put("p_attachment_url", attachmentUrl);
                }
                msgObj.put("p_message", wire);
                SupportApi.makeSupabaseRequest(
                        "rest/v1/rpc/rpc_create_ticket_message", "POST", msgObj.toString(), null);
                deliveredPending();
                loadMessages(false);
            } catch (Exception e) {
                AppLogger.log("KodaSupport", "send failed: " + e.getMessage());
                failPending(e.getMessage());
            }
        }, "KodaSupportSend").start();
    }

    private void addPending(String text, String attachmentUrl) {
        JSONObject local = new JSONObject();
        try {
            local.put("message", text == null ? "" : text);
            local.put("sender_uuid", myUuid);
            local.put("created_at", "");
            local.put("pending", true);
            if (attachmentUrl != null) local.put("local_attachment", attachmentUrl);
        } catch (Exception ignored) {
        }
        runOnUiThread(() -> {
            pending.add(local);
            failedText = null;
            failedAttachmentUrl = null;
            int position = messages.size() + pending.size() - 1;
            adapter.notifyItemInserted(position);
            updateEmptyState();
            scrollToBottom(false);
        });
    }

    private void deliveredPending() {
        runOnUiThread(() -> {
            int count = pending.size();
            if (count > 0) adapter.notifyItemRangeRemoved(messages.size(), count);
            pending.clear();
            updateEmptyState();
        });
    }

    private void failPending(String reason) {
        runOnUiThread(() -> {
            if (!pending.isEmpty()) {
                JSONObject last = pending.get(pending.size() - 1);
                failedText = last.optString("message", "");
                failedAttachmentUrl = last.optString("local_attachment", null);
            }
            int count = pending.size();
            if (count > 0) adapter.notifyItemRangeRemoved(messages.size(), count);
            pending.clear();
            Toast.makeText(this, reason == null ? getString(R.string.chat_send_failed) : reason,
                    Toast.LENGTH_SHORT).show();
            adapter.notifyDataSetChanged();
        });
    }

    private void updateEmptyState() {
        if (llChatEmpty == null) return;
        boolean nothingYet = messages.isEmpty() && pending.isEmpty();
        llChatEmpty.setVisibility(nothingYet ? View.VISIBLE : View.GONE);
        if (lottieWait != null) {
            // the hopping blocks run while we are still waiting for the first answer
            lottieWait.setVisibility(firstLoadDone ? View.GONE : View.VISIBLE);
        }
    }

    /** keeps the newest message visible; only moves when the user is at the bottom. */
    private void scrollToBottom(boolean force) {
        if (adapter.getItemCount() == 0) return;
        if (!force && !userAtBottom) return;
        rvChat.post(() -> {
            if (adapter.getItemCount() > 0) {
                rvChat.smoothScrollToPosition(adapter.getItemCount() - 1);
            }
        });
    }

    private void loadMessages(boolean firstCall) {
        new Thread(() -> {
            try {
                JSONObject rpcObj = new JSONObject();
                rpcObj.put("p_ticket_id", ticketId);
                rpcObj.put("p_reporter_uuid", myUuid);
                rpcObj.put("p_device_token", deviceToken);
                String response = SupportApi.makeSupabaseRequest(
                        "rest/v1/rpc/rpc_get_ticket_messages", "POST", rpcObj.toString(), null);
                JSONArray arr = new JSONArray(response);
                List<JSONObject> fresh = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject msg = arr.getJSONObject(i);
                    if (eu.kodanetwork.mchost.util.E2EE.isEncrypted(msg.optString("message", ""))) {
                        // encrypted means a personal ticket, that flag decides our sending path
                        e2eeTicket = true;
                    }
                    fresh.add(msg);
                }
                runOnUiThread(() -> applyFresh(fresh, firstCall));
            } catch (Exception e) {
                AppLogger.log("KodaSupport", "load failed: " + e.getMessage());
                runOnUiThread(() -> {
                    boolean wasFirst = !firstLoadDone;
                    firstLoadDone = true;
                    connectionProblem = true;
                    if (llConnectionBar != null) llConnectionBar.setVisibility(View.VISIBLE);
                    updateEmptyState();
                    if (wasFirst) adapter.notifyDataSetChanged();
                });
            }
        }, "KodaSupportLoad").start();
    }

    /**
     * appends what is new instead of rebuilding the whole list: the scroll stays calm and
     * the default item animator gives the new bubble its slide in.
     */
    private void applyFresh(List<JSONObject> fresh, boolean firstCall) {
        firstLoadDone = true;
        connectionProblem = false;
        if (llConnectionBar != null) llConnectionBar.setVisibility(View.GONE);

        boolean appended = false;
        if (!firstCall && fresh.size() > messages.size() && samePrefix(fresh, messages.size())) {
            int start = messages.size();
            for (int i = start; i < fresh.size(); i++) messages.add(fresh.get(i));
            adapter.notifyItemRangeInserted(start, fresh.size() - start);
            appended = true;
        } else if (firstCall || fresh.size() != messages.size() || changedAt(fresh)) {
            messages.clear();
            messages.addAll(fresh);
            adapter.notifyDataSetChanged();
        }

        // im offenen Chat gilt alles als gelesen, sonst meldet der Dienst die eigene Antwort
        eu.kodanetwork.mchost.App.getPrefs(this).edit()
                .putLong("ticket_seen_" + ticketId, System.currentTimeMillis()).apply();

        updateEmptyState();
        if (firstCall) {
            scrollToBottom(true);
        } else if (appended) {
            scrollToBottom(false);
        }
    }

    private boolean samePrefix(List<JSONObject> fresh, int count) {
        if (count > fresh.size()) return false;
        for (int i = 0; i < count; i++) {
            if (!messages.get(i).optString("id").equals(fresh.get(i).optString("id"))) return false;
        }
        return true;
    }

    private boolean changedAt(List<JSONObject> fresh) {
        for (int i = 0; i < fresh.size(); i++) {
            if (!messages.get(i).optString("id").equals(fresh.get(i).optString("id"))) return true;
        }
        return false;
    }

    private void exportChat() {
        if (messages.isEmpty()) {
            Toast.makeText(this, getString(R.string.chat_export_empty), Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.support_ticket_default_title)).append(": ").append(ticketTitle).append("\n");
        sb.append("Date: ").append(new java.util.Date()).append("\n\n");
        for (JSONObject msg : messages) {
            String senderUuid = msg.optString("sender_uuid", "");
            String shown = messageText(msg);
            String date = msg.optString("created_at", "").split("\\.")[0].replace("T", " ");
            boolean isAdmin = msg.optBoolean("is_admin", false);
            String sender = isAdmin ? getString(R.string.chat_admin)
                    : (senderUuid.equals(myUuid) ? getString(R.string.chat_you) : getString(R.string.chat_system));
            sb.append("[").append(date).append("] ").append(sender).append(":\n");
            if (!shown.isEmpty()) sb.append(shown).append("\n");
            String url = attachmentUrl(msg);
            if (url != null) sb.append(getString(R.string.chat_attachment)).append(": ").append(url).append("\n");
            sb.append("\n");
        }
        Intent sendIntent = new Intent();
        sendIntent.setAction(Intent.ACTION_SEND);
        sendIntent.putExtra(Intent.EXTRA_TEXT, sb.toString());
        sendIntent.setType("text/plain");
        startActivity(Intent.createChooser(sendIntent, getString(R.string.chat_export_title)));
    }

    /** plain text of a message, decrypted for personal tickets and without the url suffix. */
    private String messageText(JSONObject msg) {
        String raw = msg.optString("message", "");
        String url = attachmentUrl(msg);
        if (url != null && raw.endsWith(url)) {
            raw = raw.substring(0, raw.length() - url.length());
            if (raw.endsWith("\n")) raw = raw.substring(0, raw.length() - 1);
            if (raw.equals("Attachment:")) raw = "";
            else if (raw.endsWith("\nAttachment:")) raw = raw.substring(0, raw.length() - 12);
        }
        if (eu.kodanetwork.mchost.util.E2EE.isEncrypted(raw)) {
            String plain = eu.kodanetwork.mchost.util.E2EE.decrypt(e2eeKey, raw);
            return plain != null ? plain : getString(R.string.personal_chat_locked);
        }
        return raw;
    }

    /** the photo url of a message: proper column if it ever exists, else the suffix. */
    private String attachmentUrl(JSONObject msg) {
        String local = msg.optString("local_attachment", "");
        if (!local.isEmpty()) return local;
        String column = msg.optString("attachment_url", "");
        if (!column.isEmpty() && !"null".equals(column)) return column;
        String raw = msg.optString("message", "");
        int marker = raw.indexOf("Attachment: http");
        if (marker >= 0) {
            String url = raw.substring(marker + "Attachment: ".length()).trim();
            if (url.startsWith("http")) return url;
        }
        return null;
    }

    /** small in-memory cache, the same photo shows up instantly when scrolling back. */
    private static final java.util.HashMap<String, Bitmap> photoCache = new java.util.HashMap<>();

    /** downloads the photo and decodes it down to maxDim, null when anything goes wrong. */
    private Bitmap decodeFromUrl(String url, int maxDim) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            byte[] data;
            try (InputStream is = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = is.read(buffer)) != -1) out.write(buffer, 0, read);
                data = out.toByteArray();
            }
            android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int longest = Math.max(bounds.outWidth, bounds.outHeight);
            int sample = 1;
            while (longest / sample > maxDim) sample *= 2;
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inSampleSize = sample;
            return android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        } catch (Exception | OutOfMemoryError e) {
            AppLogger.log("KodaSupport", "photo download failed: " + e);
            return null;
        }
    }

    /** thumbnail for the bubble; the tag check keeps recycled rows from showing wrong photos. */
    private void loadPhotoThumb(String url, ImageView target) {
        target.setTag(url);
        Bitmap cached = photoCache.get(url);
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }
        target.setImageBitmap(null);
        new Thread(() -> {
            Bitmap loaded = decodeFromUrl(url, 480);
            if (loaded == null) return;
            synchronized (photoCache) {
                if (photoCache.size() > 24) photoCache.clear();
                photoCache.put(url, loaded);
            }
            runOnUiThread(() -> {
                if (url.equals(target.getTag())) target.setImageBitmap(loaded);
            });
        }, "KodaSupportThumb").start();
    }

    /** raw bytes of a remote photo, null when anything goes wrong. */
    private byte[] downloadBytes(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            try (InputStream is = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = is.read(buffer)) != -1) out.write(buffer, 0, read);
                return out.toByteArray();
            }
        } catch (Exception e) {
            AppLogger.log("KodaSupport", "photo download failed: " + e);
            return null;
        }
    }

    /** decodes photo bytes down to maxDim, null when it is not a usable image. */
    private Bitmap decodeBytes(byte[] data, int maxDim) {
        try {
            android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int longest = Math.max(bounds.outWidth, bounds.outHeight);
            int sample = 1;
            while (longest / sample > maxDim) sample *= 2;
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inSampleSize = sample;
            return android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        } catch (Exception | OutOfMemoryError e) {
            AppLogger.log("KodaSupport", "photo decode failed: " + e);
            return null;
        }
    }

    /** asks the team to close this ticket: the status flips and the marker message goes out. */
    private void requestClose() {
        ticketStatus = "CLOSE_REQUESTED";
        refreshSubtitle();
        android.view.View button = findViewById(R.id.btn_close_request);
        if (button != null) button.setVisibility(View.GONE);
        Toast.makeText(this, getString(R.string.chat_request_close_sent), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("p_ticket_id", ticketId);
                body.put("p_reporter_uuid", myUuid);
                body.put("p_device_token", deviceToken);
                SupportApi.makeSupabaseRequest("rest/v1/rpc/rpc_request_ticket_close",
                        "POST", body.toString(), null);
            } catch (Exception e) {
                AppLogger.log("KodaSupport", "close request failed: " + e.getMessage());
            }
        }, "KodaSupportClose").start();
        sendText("[KODA-CLOSE-REQUEST] " + getString(R.string.chat_request_close), null);
    }

    /**
     * fullscreen photo viewer inside the app: pinch to zoom, double tap to zoom, drag to
     * pan while zoomed, a pull down or up to close while not zoomed, an X in the corner
     * and a download button that drops the photo into the Downloads folder.
     */
    private void showPhoto(String url) {
        new Thread(() -> {
            byte[] data = downloadBytes(url);
            Bitmap bitmap = data == null ? null : decodeBytes(data, 2048);
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (bitmap == null) {
                    Toast.makeText(this, getString(R.string.chat_photo_failed), Toast.LENGTH_SHORT).show();
                    return;
                }
                showPhotoViewer(bitmap, data);
            });
        }, "KodaSupportPhoto").start();
    }

    private void showPhotoViewer(Bitmap bitmap, final byte[] rawData) {
        try {
            final Dialog dialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
            final android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
            frame.setBackgroundColor(Color.BLACK);
            final eu.kodanetwork.mchost.ui.widget.ZoomablePhotoView photo =
                    new eu.kodanetwork.mchost.ui.widget.ZoomablePhotoView(this);
            photo.setImageBitmap(bitmap);
            frame.addView(photo, new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            int size = (int) (44 * getResources().getDisplayMetrics().density);
            int margin = (int) (12 * getResources().getDisplayMetrics().density);

            ImageButton close = new ImageButton(this);
            close.setImageResource(R.drawable.ic_close_custom);
            close.setBackgroundColor(Color.TRANSPARENT);
            close.setColorFilter(Color.WHITE);
            close.setContentDescription(getString(R.string.chat_photo_close));
            android.widget.FrameLayout.LayoutParams closeParams =
                    new android.widget.FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.END);
            closeParams.topMargin = margin;
            closeParams.rightMargin = margin;
            close.setOnClickListener(v -> dialog.dismiss());
            frame.addView(close, closeParams);

            ImageButton save = new ImageButton(this);
            save.setImageResource(android.R.drawable.stat_sys_download);
            save.setBackgroundColor(Color.TRANSPARENT);
            save.setColorFilter(Color.WHITE);
            save.setContentDescription(getString(R.string.chat_photo_save));
            android.widget.FrameLayout.LayoutParams saveParams =
                    new android.widget.FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.START);
            saveParams.topMargin = margin;
            saveParams.leftMargin = margin;
            save.setOnClickListener(v -> savePhotoToDownloads(rawData));
            frame.addView(save, saveParams);

            photo.setListener(new eu.kodanetwork.mchost.ui.widget.ZoomablePhotoView.Listener() {
                @Override
                public void onPull(float offsetY, float progress) {
                    frame.setAlpha(1f - progress * 0.7f);
                }

                @Override
                public void onPullDismiss() {
                    dialog.dismiss();
                }

                @Override
                public void onPullCancel() {
                    photo.resetView();
                    frame.setAlpha(1f);
                }
            });

            dialog.setContentView(frame);
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            }
            dialog.show();
        } catch (Exception e) {
            AppLogger.log("KodaSupport", "photo viewer failed: " + e);
            Toast.makeText(this, getString(R.string.chat_photo_failed), Toast.LENGTH_SHORT).show();
        }
    }

    /** drops the photo into the Downloads folder so it can be kept like a normal file. */
    private void savePhotoToDownloads(byte[] data) {
        try {
            String name = "KodaSupport_" + System.currentTimeMillis() + ".jpg";
            android.content.ContentValues values = new android.content.ContentValues();
            values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
            if (Build.VERSION.SDK_INT >= 29) {
                values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_DOWNLOADS);
                android.net.Uri uri = getContentResolver().insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
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
                android.media.MediaScannerConnection.scanFile(this,
                        new String[]{file.getAbsolutePath()}, new String[]{"image/jpeg"}, null);
            }
            Toast.makeText(this, getString(R.string.chat_photo_saved), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            AppLogger.log("KodaSupport", "photo save failed: " + e);
            Toast.makeText(this, getString(R.string.chat_photo_save_failed), Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isImageUrl(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".webp") || lower.endsWith(".gif");
    }

    private class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.ViewHolder> {

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_chat_message, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            try {
                bind(holder, position);
            } catch (Exception e) {
                // a single broken message must never take the whole chat down
                AppLogger.log("KodaSupport", "bind failed: " + e);
                holder.tvMessage.setText("");
                holder.tvMessage.setVisibility(View.GONE);
                holder.llAttachment.setVisibility(View.GONE);
                holder.tvState.setVisibility(View.GONE);
            }
        }

        private void bind(ViewHolder holder, int position) {
            boolean isPending = position >= messages.size();
            JSONObject msg = isPending ? pending.get(position - messages.size()) : messages.get(position);

            String text = isPending ? msg.optString("message", "") : messageText(msg);
            String url = isPending ? msg.optString("local_attachment", null) : attachmentUrl(msg);
            boolean isMe = myUuid != null && myUuid.equals(msg.optString("sender_uuid", ""));
            boolean isAdmin = msg.optBoolean("is_admin", false);
            boolean light = ThemeHelper.isLightMode(SupportChatActivity.this);

            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) holder.llMessageContainer.getLayoutParams();
            params.gravity = isMe ? Gravity.END : Gravity.START;
            holder.llMessageContainer.setLayoutParams(params);
            holder.tvMessage.setMaxWidth((int) (260 * getResources().getDisplayMetrics().density));

            if (isMe) {
                holder.tvSenderName.setText(getString(R.string.chat_you));
                holder.tvSenderName.setTextColor(light ? 0xFF555555 : 0xFF9A9AA5);
                holder.llBubble.setBackgroundResource(light ? R.drawable.bg_chat_bubble_me_light : R.drawable.bg_chat_bubble_me);
                holder.tvMessage.setTextColor(light ? 0xFF111111 : 0xFFFFFFFF);
            } else if (isAdmin) {
                holder.tvSenderName.setText(getString(R.string.chat_admin));
                holder.tvSenderName.setTextColor(0xFFFF8C38);
                holder.llBubble.setBackgroundResource(light ? R.drawable.bg_chat_bubble_admin_light : R.drawable.bg_chat_bubble_admin);
                holder.tvMessage.setTextColor(light ? 0xFF111111 : 0xFFFFFFFF);
            } else {
                holder.tvSenderName.setText(getString(R.string.chat_system));
                holder.tvSenderName.setTextColor(light ? 0xFF555555 : 0xFF9A9AA5);
                holder.llBubble.setBackgroundResource(light ? R.drawable.bg_chat_bubble_system_light : R.drawable.bg_chat_bubble_system);
                holder.tvMessage.setTextColor(light ? 0xFF111111 : 0xFFFFFFFF);
            }

            if (text == null || text.isEmpty()) {
                holder.tvMessage.setVisibility(View.GONE);
            } else {
                holder.tvMessage.setVisibility(View.VISIBLE);
                holder.tvMessage.setText(text);
            }

            if (url != null && !url.isEmpty() && isImageUrl(url)) {
                holder.llAttachment.setVisibility(View.VISIBLE);
                holder.ivAttachment.setVisibility(View.VISIBLE);
                holder.tvAttachmentLink.setVisibility(View.GONE);
                loadPhotoThumb(url, holder.ivAttachment);
                holder.ivAttachment.setOnClickListener(v -> showPhoto(url));
            } else if (url != null && !url.isEmpty()) {
                holder.llAttachment.setVisibility(View.VISIBLE);
                holder.ivAttachment.setVisibility(View.GONE);
                holder.tvAttachmentLink.setVisibility(View.VISIBLE);
                holder.tvAttachmentLink.setText(getString(R.string.chat_attachment));
                holder.tvAttachmentLink.setOnClickListener(v -> showPhoto(url));
            } else {
                holder.llAttachment.setVisibility(View.GONE);
                holder.ivAttachment.setVisibility(View.GONE);
                holder.tvAttachmentLink.setVisibility(View.GONE);
            }

            String date = msg.optString("created_at", "");
            if (date.contains("T")) {
                String time = date.split("T")[1];
                date = time.substring(0, Math.min(5, time.length()));
            }
            holder.tvTime.setText(date);

            if (isPending) {
                holder.tvState.setVisibility(View.VISIBLE);
                holder.tvState.setText(getString(R.string.chat_sending));
                holder.tvState.setTextColor(0xFF8A8A9A);
                holder.itemView.setOnClickListener(null);
            } else if (failedText != null && !messages.isEmpty()
                    && position == messages.size() - 1) {
                holder.tvState.setVisibility(View.VISIBLE);
                holder.tvState.setText(getString(R.string.chat_send_retry));
                holder.tvState.setTextColor(0xFFFF5555);
                holder.itemView.setOnClickListener(v -> {
                    String retryText = failedText;
                    String retryAttachment = failedAttachmentUrl;
                    failedText = null;
                    failedAttachmentUrl = null;
                    sendText(retryText, retryAttachment);
                });
            } else {
                holder.tvState.setVisibility(View.GONE);
                holder.itemView.setOnClickListener(null);
            }
        }

        @Override
        public int getItemCount() {
            return messages.size() + pending.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            final LinearLayout llMessageContainer, llBubble, llAttachment;
            final TextView tvSenderName, tvMessage, tvTime, tvAttachmentLink, tvState;
            final ImageView ivAttachment;

            ViewHolder(View view) {
                super(view);
                llMessageContainer = view.findViewById(R.id.ll_message_container);
                llBubble = view.findViewById(R.id.ll_bubble);
                llAttachment = view.findViewById(R.id.ll_attachment);
                tvSenderName = view.findViewById(R.id.tv_sender_name);
                tvMessage = view.findViewById(R.id.tv_message);
                tvTime = view.findViewById(R.id.tv_time);
                tvAttachmentLink = view.findViewById(R.id.tv_attachment_link);
                tvState = view.findViewById(R.id.tv_state);
                ivAttachment = view.findViewById(R.id.iv_attachment);
            }
        }
    }
}
