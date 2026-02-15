package com.lattice.events;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class EventPayload {
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    public String event_id;
    public long event_time;
    public String server_id;
    public String event_type;
    public String player_uuid;
    public String player_name;
    public String item_id;
    public int count;
    public String nbt_hash;
    public String origin_id;
    public String origin_type;
    public String origin_ref;
    public String source_type;
    public String source_ref;
    public String storage_mod;
    public String storage_id;
    public String actor_type;
    public String trace_id;
    public String item_fingerprint;
    public String dim;
    public Integer x;
    public Integer y;
    public Integer z;

    public String toJson() {
        return GSON.toJson(this);
    }
}
