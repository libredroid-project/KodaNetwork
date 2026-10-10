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

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import eu.kodanetwork.mchost.R;
import eu.kodanetwork.mchost.model.ServerInstance;
import eu.kodanetwork.mchost.util.SleekTouch;

/**
 * server list adapter for the sleek design system.
 * cards get SleekTouch press feedback and open ServerDetail on tap.
 */
public class SleekServerAdapter extends RecyclerView.Adapter<SleekServerAdapter.VH> {

    public interface OnServerClick {
        void onOpen(ServerInstance server);
    }

    private final List<ServerInstance> servers;
    private final OnServerClick listener;

    public SleekServerAdapter(List<ServerInstance> servers, OnServerClick listener) {
        this.servers = servers;
        this.listener = listener;
    }

    /** swap in a fresh snapshot from the repo (repo.all() returns new instances). */
    public void update(List<ServerInstance> fresh) {
        servers.clear();
        servers.addAll(fresh);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_server_sleek, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        ServerInstance s = servers.get(position);
        holder.tvName.setText(s.getName());
        holder.tvAddress.setText(s.getJoinAddress());
        holder.tvType.setText(s.getType().name());
        holder.tvPlayers.setText(s.onlinePlayers + "/" + s.getMaxPlayers());
        holder.tvRam.setText((s.getRamMB() / 1024) + "GB");

        // dot color, one per server state
        int dotColor;
        switch (s.state) {
            case ONLINE: dotColor = 0xFF69781D; break;
            case STARTING: case RESTARTING: dotColor = 0xFFF5A623; break;
            case CRASHED: dotColor = 0xFFE8442E; break;
            default: dotColor = 0xFF5C5852; break;
        }
        holder.dotStatus.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(dotColor));

        // sleektouch press feedback, with a 60 ms buzz
        SleekTouch.apply(holder.itemView, () -> {
            if (listener != null) listener.onOpen(s);
        }, 60);
    }

    @Override
    public int getItemCount() {
        return servers.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView tvName, tvAddress, tvType, tvPlayers, tvRam;
        final View dotStatus;

        VH(View itemView) {
            super(itemView);
            tvName = itemView.findViewById(R.id.tv_server_name);
            tvAddress = itemView.findViewById(R.id.tv_join_address);
            tvType = itemView.findViewById(R.id.tv_type);
            tvPlayers = itemView.findViewById(R.id.tv_players);
            tvRam = itemView.findViewById(R.id.tv_ram);
            dotStatus = itemView.findViewById(R.id.dot_status);
        }
    }
}
