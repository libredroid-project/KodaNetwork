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
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

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
 * the support ticket chat. works exactly like the personal (crisis) chat: device token
 * login instead of the (expiring) supabase session, so it works for every device without
 * an account, and personal tickets keep their end to end encryption.
 *
 * the list is built incrementally, new messages arrive with a soft scroll, own messages
 * show up immediately with a sending state and can be retried, attachments render as
 * image previews or file chips and open inside the app instead of the browser.
 */
public class SupportChatActivity extends AppCompatActivity {

    private static final int TYPE_INFO = 0;
    private static final int TYPE_MESSAGE = 1;
    private static final int TYPE_EMPTY = 2;
    private static final int TYPE_CONNECTION = 3;

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

    /** E2EE key for personal tickets, null while not established or not needed. */
    private byte[] e2eeKey;
    /** true for personal (mental support) tickets: outgoing text gets encrypted. */
    private boolean e2eeTicket;

    private final List<JSONObject> messages = new ArrayList<>();
    private final List<JSONObject> pending = new ArrayList<>();
    /** text of the last failed send, offered for retry. */
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

    private ActivityResultLauncher<Intent> filePickerLauncher;

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

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_export).setOnClickListener(v -> exportChat());

        rvChat = findViewById(R.id.rv_chat);
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true);
        rvChat.setLayoutManager(layoutManager);
        adapter = new ChatAdapter();
        rvChat.setAdapter(adapter);
        rvChat.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                // only stick to the newest message while the user is already at the bottom,
                // otherwise reading older messages would be yanked away on every poll
                View last = recyclerView.getChildAt(recyclerView.getChildCount() - 1);
                userAtBottom = last == null
                        || last.getBottom() - recyclerView.getHeight() + recyclerView.getPaddingBottom() <= 24;
            }
        });

        etMessage = findViewById(R.id.et_message);
        llAttachmentPreview = findViewById(R.id.ll_attachment_preview);
        tvAttachmentName = findViewById(R.id.tv_attachment_name);

        findViewById(R.id.btn_remove_attachment).setOnClickListener(v -> {
            selectedAttachmentUri = null;
            selectedAttachmentName = null;
            llAttachmentPreview.setVisibility(View.GONE);
        });

        filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        selectedAttachmentUri = uri;
                        selectedAttachmentName = getFileName(uri);
                        tvAttachmentName.setText(selectedAttachmentName);
                        llAttachmentPreview.setVisibility(View.VISIBLE);
                    }
                }
            }
        );

        findViewById(R.id.btn_attach).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            filePickerLauncher.launch(intent);
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

        setupE2EE();
        loadMessages(true);
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

    private void sendCurrentInput() {
        String text = etMessage.getText().toString().trim();
        if (text.isEmpty() && selectedAttachmentUri == null) return;
        etMessage.setText("");
        if (selectedAttachmentUri != null) {
            Uri uri = selectedAttachmentUri;
            String name = selectedAttachmentName;
            selectedAttachmentUri = null;
            selectedAttachmentName = null;
            llAttachmentPreview.setVisibility(View.GONE);
            uploadAndSend(text, uri, name);
        } else {
            sendText(text, null);
        }
    }

    /** uploads the picked file, then sends the message with the attachment url. */
    private void uploadAndSend(String text, Uri uri, String name) {
        addPending(text.isEmpty() ? getString(R.string.chat_attachment) : text, null);
        new Thread(() -> {
            try {
                String safeName = String.valueOf(name).replaceAll("[^a-zA-Z0-9._-]", "_");
                String fileName = System.currentTimeMillis() + "_" + safeName;
                HttpURLConnection conn = (HttpURLConnection) new URL(
                        PraetorSecurity.getSupabaseUrl() + "/storage/v1/object/support_attachments/" + fileName)
                        .openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Authorization", "Bearer " + PraetorSecurity.getSupabaseKey());
                conn.setRequestProperty("apikey", PraetorSecurity.getSupabaseKey());
                conn.setRequestProperty("Content-Type", guessContentType(safeName));
                conn.setDoOutput(true);
                try (InputStream is = getContentResolver().openInputStream(uri);
                     OutputStream os = conn.getOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while (is != null && (read = is.read(buffer)) != -1) {
                        os.write(buffer, 0, read);
                    }
                    os.flush();
                }
                int code = conn.getResponseCode();
                if (code == 200 || code == 201) {
                    String publicUrl = PraetorSecurity.getSupabaseUrl()
                            + "/storage/v1/object/public/support_attachments/" + fileName;
                    sendText(text, publicUrl);
                } else {
                    failPending(getString(R.string.chat_upload_failed) + " (" + code + ")");
                }
            } catch (Exception e) {
                failPending(e.getMessage() == null ? getString(R.string.chat_upload_failed) : e.getMessage());
            }
        }, "KodaSupportUpload").start();
    }

    private static String guessContentType(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".txt") || lower.endsWith(".log")) return "text/plain";
        if (lower.endsWith(".zip")) return "application/zip";
        return "application/octet-stream";
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
                    // the send RPC has no attachment column yet, so the url rides in the
                    // message and the adapter splits it back out for the preview
                    wire = wire.isEmpty() ? "Attachment: " + attachmentUrl
                            : wire + "\nAttachment: " + attachmentUrl;
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
            render(true);
        });
    }

    private void deliveredPending() {
        runOnUiThread(() -> {
            pending.clear();
            render(true);
        });
    }

    private void failPending(String reason) {
        runOnUiThread(() -> {
            if (!pending.isEmpty()) {
                JSONObject last = pending.get(pending.size() - 1);
                failedText = last.optString("message", "");
                failedAttachmentUrl = last.optString("local_attachment", null);
            }
            pending.clear();
            Toast.makeText(this, reason == null ? getString(R.string.chat_send_failed) : reason,
                    Toast.LENGTH_SHORT).show();
            render(true);
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
                String stamp = fresh.isEmpty() ? "" :
                        fresh.get(fresh.size() - 1).optString("created_at", "") + "#" + fresh.size();
                boolean changed = !stamp.equals(lastSeenStamp);
                lastSeenStamp = stamp;
                runOnUiThread(() -> {
                    firstLoadDone = true;
                    connectionProblem = false;
                    messages.clear();
                    messages.addAll(fresh);
                    if (changed || firstCall) render(false);
                });
            } catch (Exception e) {
                AppLogger.log("KodaSupport", "load failed: " + e.getMessage());
                runOnUiThread(() -> {
                    firstLoadDone = true;
                    connectionProblem = true;
                    render(false);
                });
            }
        }, "KodaSupportLoad").start();
    }

    /** rebuilds the visible list; animated=true when an own message was just added. */
    private void render(boolean jump) {
        adapter.notifyDataSetChanged();
        if ((userAtBottom || jump) && adapter.getItemCount() > 0) {
            rvChat.smoothScrollToPosition(adapter.getItemCount() - 1);
        }
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

    /** the attachment url of a message: proper column if it ever exists, else the suffix. */
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

    private String getFileName(Uri uri) {
        String result = null;
        if (uri.getScheme() != null && uri.getScheme().equals("content")) {
            try (android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) result = cursor.getString(idx);
                }
            }
        }
        if (result == null) {
            String path = uri.getPath();
            if (path != null) {
                int cut = path.lastIndexOf('/');
                result = cut != -1 ? path.substring(cut + 1) : path;
            }
        }
        return result == null ? "file" : result;
    }

    /** fullscreen in-app viewer, no browser hop and no external app sees the ticket image. */
    private void showImage(String url) {
        Dialog dialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        ImageView image = new ImageView(this);
        image.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setBackgroundColor(Color.BLACK);
        dialog.setContentView(image);
        Window window = dialog.getWindow();
        if (window != null) window.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        image.setOnClickListener(v -> dialog.dismiss());
        com.bumptech.glide.Glide.with(this).load(url).into(image);
        dialog.show();
    }

    private class ChatAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        @Override
        public int getItemViewType(int position) {
            int messageCount = messages.size() + pending.size();
            if (position == 0) return TYPE_INFO;
            if (messageCount == 0) return TYPE_EMPTY;
            if (position == messageCount + 1 && connectionProblem) return TYPE_CONNECTION;
            return TYPE_MESSAGE;
        }

        @Override
        public int getItemCount() {
            int count = messages.size() + pending.size();
            int total = 1; // the info card is always there
            total += count == 0 ? 1 : count;
            if (connectionProblem) total += 1;
            return total;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_INFO) {
                return new InfoHolder(inflater.inflate(R.layout.item_support_chat_info, parent, false));
            }
            if (viewType == TYPE_EMPTY) {
                return new EmptyHolder(inflater.inflate(R.layout.item_support_chat_empty, parent, false));
            }
            if (viewType == TYPE_CONNECTION) {
                return new ConnectionHolder(inflater.inflate(R.layout.item_support_chat_connection, parent, false));
            }
            return new MessageHolder(inflater.inflate(R.layout.item_chat_message, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder instanceof InfoHolder) {
                bindInfo((InfoHolder) holder);
            } else if (holder instanceof ConnectionHolder) {
                holder.itemView.setOnClickListener(v -> {
                    connectionProblem = false;
                    loadMessages(true);
                });
            } else if (holder instanceof MessageHolder) {
                bindMessage((MessageHolder) holder, position);
            }
        }

        private void bindInfo(InfoHolder holder) {
            holder.tvType.setText(typeLabel());
            holder.tvStatus.setText(ticketStatus == null || ticketStatus.isEmpty()
                    ? "OPEN" : ticketStatus.toUpperCase(java.util.Locale.ROOT));
            boolean resolved = "RESOLVED".equalsIgnoreCase(ticketStatus);
            holder.tvStatus.setTextColor(resolved ? 0xFF4CAF50 : 0xFFFF8C00);
            String created = "";
            for (JSONObject msg : messages) {
                created = msg.optString("created_at", "");
                if (!created.isEmpty()) break;
            }
            holder.tvCreated.setText(created.isEmpty() ? ""
                    : created.split("T")[0]);
            holder.tvCreated.setVisibility(created.isEmpty() ? View.GONE : View.VISIBLE);
            holder.tvHint.setText(e2eeTicket ? getString(R.string.chat_info_encrypted)
                    : getString(R.string.chat_info_plain));
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

        private void bindMessage(MessageHolder holder, int position) {
            int index = position - 1;
            JSONObject msg;
            boolean isPending = index >= messages.size();
            if (isPending) {
                msg = pending.get(index - messages.size());
            } else {
                msg = messages.get(index);
            }

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
                holder.tvSenderName.setTextColor(light ? 0xFF555555 : 0xFF888888);
                holder.llBubble.setBackgroundResource(light ? R.drawable.bg_chat_bubble_me_light : R.drawable.bg_chat_bubble_me);
                holder.tvMessage.setTextColor(light ? 0xFF111111 : 0xFFFFFFFF);
            } else if (isAdmin) {
                holder.tvSenderName.setText(getString(R.string.chat_admin));
                holder.tvSenderName.setTextColor(0xFFFF8C00);
                holder.llBubble.setBackgroundResource(light ? R.drawable.bg_chat_bubble_admin_light : R.drawable.bg_chat_bubble_admin);
                holder.tvMessage.setTextColor(light ? 0xFF111111 : 0xFFFFFFFF);
            } else {
                holder.tvSenderName.setText(getString(R.string.chat_system));
                holder.tvSenderName.setTextColor(light ? 0xFF555555 : 0xFF888888);
                holder.llBubble.setBackgroundResource(light ? R.drawable.bg_chat_bubble_system_light : R.drawable.bg_chat_bubble_system);
                holder.tvMessage.setTextColor(light ? 0xFF111111 : 0xFFFFFFFF);
            }

            if (text == null || text.isEmpty()) {
                holder.tvMessage.setVisibility(View.GONE);
            } else {
                holder.tvMessage.setVisibility(View.VISIBLE);
                holder.tvMessage.setText(text);
            }

            // attachment: image preview when it is a picture, else a file chip
            if (url != null && !url.isEmpty()) {
                holder.llAttachment.setVisibility(View.VISIBLE);
                boolean image = isImageUrl(url);
                holder.ivAttachment.setVisibility(image ? View.VISIBLE : View.GONE);
                holder.tvAttachmentLink.setVisibility(image ? View.GONE : View.VISIBLE);
                if (image) {
                    com.bumptech.glide.Glide.with(SupportChatActivity.this)
                            .load(url)
                            .placeholder(R.drawable.bg_input)
                            .into(holder.ivAttachment);
                    holder.ivAttachment.setOnClickListener(v -> showImage(url));
                } else {
                    holder.tvAttachmentLink.setText(getString(R.string.chat_attachment));
                    holder.tvAttachmentLink.setOnClickListener(v -> showImage(url));
                }
            } else {
                holder.llAttachment.setVisibility(View.GONE);
            }

            String date = msg.optString("created_at", "");
            if (date.contains("T")) date = date.split("T")[1].substring(0, Math.min(5, date.split("T")[1].length()));
            holder.tvTime.setText(date);
            if (isPending) {
                holder.tvState.setVisibility(View.VISIBLE);
                holder.tvState.setText(getString(R.string.chat_sending));
                holder.tvState.setTextColor(0xFF8A8A9A);
            } else if (!isPending && failedText != null && !messages.isEmpty()
                    && index == messages.size() - 1) {
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

        private JSONObject lastMessage() {
            if (!messages.isEmpty()) return messages.get(messages.size() - 1);
            return null;
        }

        private boolean isImageUrl(String url) {
            String lower = url.toLowerCase(java.util.Locale.ROOT);
            return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                    || lower.endsWith(".gif") || lower.endsWith(".webp");
        }
    }

    private static class InfoHolder extends RecyclerView.ViewHolder {
        final TextView tvType, tvStatus, tvCreated, tvHint;
        InfoHolder(View view) {
            super(view);
            tvType = view.findViewById(R.id.tv_info_type);
            tvStatus = view.findViewById(R.id.tv_info_status);
            tvCreated = view.findViewById(R.id.tv_info_created);
            tvHint = view.findViewById(R.id.tv_info_hint);
        }
    }

    private static class EmptyHolder extends RecyclerView.ViewHolder {
        EmptyHolder(View view) {
            super(view);
        }
    }

    private static class ConnectionHolder extends RecyclerView.ViewHolder {
        ConnectionHolder(View view) {
            super(view);
        }
    }

    private static class MessageHolder extends RecyclerView.ViewHolder {
        final LinearLayout llMessageContainer, llBubble, llAttachment;
        final TextView tvSenderName, tvMessage, tvTime, tvAttachmentLink, tvState;
        final ImageView ivAttachment;

        MessageHolder(View view) {
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
