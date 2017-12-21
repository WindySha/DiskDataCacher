package com.wind.cache.diskdatacacher.cachetool;

public interface DataCache {

    void initialize();

    Entry get(String key);

    void put(String key, Entry entry);

    void remove(String key);

    void clear();

    class Entry {

        public byte[] data;

        public long validTimestamp;

    }
}
