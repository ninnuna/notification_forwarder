package com.example.notificationforwarder;

import java.util.LinkedHashMap;
import java.util.Map;

class LRUCache extends LinkedHashMap<Integer, Long> {
    private final int maxSize;
    public LRUCache(int capacity) {
        super(capacity, 0.75f, true);
        this.maxSize = capacity;
    }

    //return -1 if miss
    public long get(int key) {
        Long v = super.get(key);
        return v == null ? -1 : v;
    }

    public void put(int key, long value) {
        super.put(key, value);
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<Integer, Long> eldest) {
        return this.size() > maxSize; //must override it if used in a fixed cache
    }
}