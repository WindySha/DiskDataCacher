package com.wind.cache.diskdatacacher;

import android.app.Application;

import com.wind.cache.diskdatacacher.cachetool.DiskStringCacheManager;

import java.io.File;

public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        DiskStringCacheManager.init(new File(getCacheDir(), DiskStringCacheManager.DEFAULT_CACHE_FILE_NAME),
                DiskStringCacheManager.MAX_CACHE_SIZE);
    }
}
