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

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import eu.kodanetwork.mchost.R;
import eu.kodanetwork.mchost.network.supabase.SupportApi;
import eu.kodanetwork.mchost.util.Material3ThemeHelper;

/**
 * the private chat with libredroid, opened from the crisis support screen.
 *
 * no forms, no categories, the ticket already exists and the person lands straight in the
 * conversation. messenger look with a soft pink accent, own messages on the right in pink,
 * answers on the left. messages go through the same device-token RPCs as the support chat,
 * but nothing diagnostic is ever attached here.
 */
public class PersonalChatActivity extends AppCompatActivity {

    private final Handler pollHandler = new Handler(Looper.getMainLooper());
    private final List<JSONObject> messages = new ArrayList<>();

    private String ticketId;
    private String myUuid;
    private String deviceToken;
    private ChatAdapter adapter;
    private RecyclerView rvChat;
    private TextView tvEmpty;
    /** shared E2EE key with the peer (admin); null while not established. */
    private byte[] peerSharedKey;

    /** pulls new messages every few seconds while the chat is open. */
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            loadMessages();
            pollHandler.postDelayed(this, 5000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Material3ThemeHelper.applyTheme(this);
        setContentView(R.layout.activity_personal_chat);

        ticketId = getIntent().getStringExtra("TICKET_ID");
        myUuid = eu.kodanetwork.mchost.App.getPrefs(this).getString("app_uuid", null);
        deviceToken = eu.kodanetwork.mchost.App.getPrefs(this).getString("device_token", "");
        if (ticketId == null || myUuid == null) {
            finish();
            return;
        }

        findViewById(R.id.btn_personal_back).setOnClickListener(v -> finish());

        rvChat = findViewById(R.id.rv_personal_chat);
        tvEmpty = findViewById(R.id.tv_personal_empty);
        rvChat.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ChatAdapter();
        rvChat.setAdapter(adapter);

        EditText input = findViewById(R.id.et_personal_message);
        ImageButton send = findViewById(R.id.btn_personal_send);
        send.setOnClickListener(v -> {
            eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 20);
            send(input.getText().toString().trim(), input);
        });
        input.setOnEditorActionListener((v, actionId, event) -> {
            eu.kodanetwork.mchost.util.HapticUtil.forceVibrate(this, 20);
            send(input.getText().toString().trim(), input);
            return true;
        });

        setupEncryption();
        loadMessages();
    }

    /**
     * Publishes our own public key once and derives the shared key with the peer. Until the
     * peer has published a key too, messages stay readable plaintext (old devices).
     */
    private void setupEncryption() {
        new Thread(() -> {
            String pub = eu.kodanetwork.mchost.util.E2EE.ensureKeyPair(this);
            try {
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
                        peerSharedKey = shared;
                        adapter.notifyDataSetChanged();
                    });
                }
            } catch (Exception ignored) {
                // without a peer key the chat still works, just unencrypted
            }
        }, "KodaE2EE").start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // App.java registers its own insets listener after onCreate and would overwrite this,
        // so the keyboard padding is (re)applied here, after the global one
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(R.id.personal_chat_root), (v, insets) -> {
                    androidx.core.graphics.Insets ime =
                            insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime());
                    androidx.core.graphics.Insets bars =
                            insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
                    int bottom = Math.max(ime.bottom, bars.bottom);
                    v.setPadding(0, bars.top, 0, bottom);
                    return androidx.core.view.WindowInsetsCompat.CONSUMED;
                });
        findViewById(R.id.personal_chat_root).requestApplyInsets();
        pollHandler.postDelayed(poll, 5000);
    }

    @Override
    protected void onPause() {
        pollHandler.removeCallbacks(poll);
        super.onPause();
    }

    private void send(String text, EditText input) {
        if (text.isEmpty()) return;
        input.setText("");
        // network call has to run off the ui thread, otherwise android throws
        // NetworkOnMainThreadException and the message looks "failed"
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("p_ticket_id", ticketId);
                body.put("p_sender_uuid", myUuid);
                body.put("p_device_token", deviceToken);
                String wire = text;
                if (peerSharedKey != null) {
                    String encrypted = eu.kodanetwork.mchost.util.E2EE.encrypt(peerSharedKey, text);
                    if (encrypted != null) wire = encrypted;
                }
                body.put("p_message", wire);
                SupportApi.makeSupabaseRequest("rest/v1/rpc/rpc_create_ticket_message",
                        "POST", body.toString(), null);
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        getString(R.string.personal_chat_send_failed), Toast.LENGTH_SHORT).show());
            }
            loadMessages();
        }).start();
    }

    private void loadMessages() {
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("p_ticket_id", ticketId);
                body.put("p_reporter_uuid", myUuid);
                body.put("p_device_token", deviceToken);
                String response = SupportApi.makeSupabaseRequest(
                        "rest/v1/rpc/rpc_get_ticket_messages", "POST", body.toString(), null);
                JSONArray arr = new JSONArray(response);
                List<JSONObject> fresh = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) fresh.add(arr.getJSONObject(i));
                runOnUiThread(() -> {
                    messages.clear();
                    messages.addAll(fresh);
                    adapter.notifyDataSetChanged();
                    tvEmpty.setVisibility(messages.isEmpty() ? View.VISIBLE : View.GONE);
                    if (!messages.isEmpty()) rvChat.scrollToPosition(messages.size() - 1);
                });
            } catch (Exception ignored) {
                // the next poll tries again
            }
        }).start();
    }

    /** own messages right in pink, libredroid's answers left in dark. */
    private class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.ViewHolder> {

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_personal_message, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            JSONObject msg = messages.get(position);
            String text = msg.optString("message", "");
            String time = msg.optString("created_at", "");
            if (time.contains("T")) time = time.split("T")[1].split("\\.")[0].substring(0, 5);

            String shown = text;
            if (eu.kodanetwork.mchost.util.E2EE.isEncrypted(text)) {
                String plain = eu.kodanetwork.mchost.util.E2EE.decrypt(peerSharedKey, text);
                shown = plain != null ? plain : getString(R.string.personal_chat_locked); // wrong key
            }
            boolean mine = msg.optString("sender_uuid", "").equals(myUuid);
            LinearLayout bubble = holder.bubble;
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) bubble.getLayoutParams();
            params.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            params.gravity = mine ? Gravity.END : Gravity.START;
            bubble.setLayoutParams(params);
            bubble.setBackgroundResource(mine ? R.drawable.bg_personal_bubble_me : R.drawable.bg_personal_bubble_other);
            holder.message.setMaxWidth((int) (280 * getResources().getDisplayMetrics().density));
            holder.message.setText(shown);
            holder.message.setTextColor(mine ? 0xFF1B1613 : 0xFFE8E2D6);
            holder.time.setTextColor(mine ? 0xFF6B5E56 : 0xFF8A8A9A);
            holder.time.setText(time);
        }

        @Override
        public int getItemCount() {
            return messages.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            final LinearLayout bubble;
            final TextView message;
            final TextView time;

            ViewHolder(View view) {
                super(view);
                bubble = view.findViewById(R.id.ll_personal_bubble);
                message = view.findViewById(R.id.tv_personal_message);
                time = view.findViewById(R.id.tv_personal_time);
            }
        }
    }
}
