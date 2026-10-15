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
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import eu.kodanetwork.mchost.R;
import eu.kodanetwork.mchost.network.supabase.SupportApi;
import eu.kodanetwork.mchost.util.ThemeHelper;

public class SupportTicketListActivity extends AppCompatActivity {

    private RecyclerView rvTickets;
    private android.widget.LinearLayout llEmpty;
    private TicketAdapter adapter;
    private List<JSONObject> ticketList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        eu.kodanetwork.mchost.util.Material3ThemeHelper.applyTheme(this);
        super.onCreate(savedInstanceState);
        ThemeHelper.apply(this);
        setContentView(eu.kodanetwork.mchost.util.Material3ThemeHelper.isM3Enabled(this)
                ? R.layout.activity_support_tickets_m3 : R.layout.activity_support_tickets);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        rvTickets = findViewById(R.id.rv_tickets);
        rvTickets.setLayoutManager(new LinearLayoutManager(this));
        adapter = new TicketAdapter();
        rvTickets.setAdapter(adapter);
        llEmpty = findViewById(R.id.ll_tickets_empty);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadTickets();
    }

    private void loadTickets() {
        String uuid = eu.kodanetwork.mchost.App.getPrefs(this).getString("app_uuid", null);
        String sessionToken = eu.kodanetwork.mchost.App.getPrefs(this).getString("koda_session_token", null);
        if (uuid == null) return;

        new Thread(() -> {
            try {
                JSONObject rpcObj = new JSONObject();
                rpcObj.put("p_reporter_uuid", uuid);
                rpcObj.put("p_device_token", eu.kodanetwork.mchost.App.getPrefs(SupportTicketListActivity.this).getString("device_token", ""));
                String response = SupportApi.makeSupabaseRequest(
                        "rest/v1/rpc/rpc_get_tickets", "POST", rpcObj.toString(), sessionToken);
                JSONArray arr = new JSONArray(response);
                
                List<JSONObject> newTickets = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    newTickets.add(arr.getJSONObject(i));
                }

                runOnUiThread(() -> {
                    ticketList.clear();
                    ticketList.addAll(newTickets);
                    adapter.notifyDataSetChanged();
                    if (llEmpty != null) {
                        llEmpty.setVisibility(ticketList.isEmpty() ? View.VISIBLE : View.GONE);
                    }
                });
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> Toast.makeText(this, "Failed to load tickets", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    /** readable name for the raw ticket type from the database. */
    private String typeLabel(String rawType) {
        String type = rawType == null ? "" : rawType.toUpperCase(java.util.Locale.ROOT);
        switch (type) {
            case "BUG":
                return getString(R.string.chat_type_bug);
            case "SERVER":
                return getString(R.string.chat_type_server);
            case "PERSONAL":
                return getString(R.string.chat_type_personal);
            case "SUPPORT":
                return getString(R.string.chat_type_support);
            default:
                return rawType == null ? "" : rawType;
        }
    }

    private class TicketAdapter extends RecyclerView.Adapter<TicketAdapter.ViewHolder> {

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_support_ticket, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            JSONObject ticket = ticketList.get(position);
            try {
                String title = ticket.getString("title");
                String status = ticket.optString("status", "OPEN");
                String date = ticket.optString("created_at", "").split("T")[0];
                String id = ticket.getString("id");
                String type = typeLabel(ticket.optString("ticket_type", ""));

                holder.tvTitle.setText(title);
                holder.tvStatus.setText(status);
                holder.tvDate.setText(type.isEmpty() ? date : type + " · " + date);

                if ("RESOLVED".equals(status)) {
                    holder.tvStatus.setTextColor(0xFF00E676); // koda green
                } else {
                    holder.tvStatus.setTextColor(0xFFFF8C38); // koda orange
                }

                holder.itemView.setOnClickListener(v -> {
                    Intent intent = new Intent(SupportTicketListActivity.this, SupportChatActivity.class);
                    intent.putExtra("TICKET_ID", id);
                    intent.putExtra("TICKET_TITLE", title);
                    intent.putExtra("TICKET_STATUS", status);
                    intent.putExtra("TICKET_TYPE", ticket.optString("ticket_type", ""));
                    startActivity(intent);
                });
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        @Override
        public int getItemCount() {
            return ticketList.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvTitle, tvStatus, tvDate;
            ViewHolder(View itemView) {
                super(itemView);
                tvTitle = itemView.findViewById(R.id.tv_ticket_title);
                tvStatus = itemView.findViewById(R.id.tv_ticket_status);
                tvDate = itemView.findViewById(R.id.tv_ticket_date);
            }
        }
    }
}
