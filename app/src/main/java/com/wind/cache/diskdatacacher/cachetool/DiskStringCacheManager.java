package com.wind.cache.diskdatacacher.cachetool;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;


/**
 * 磁盘缓存工具，单例模式
 */
public class DiskStringCacheManager {

    private DataCache mDiskCache;

    private static DiskStringCacheManager sCacheManager;

    private int mMaxCacheSize;

    private File mCacheFileDir;

    public static final String DEFAULT_CACHE_FILE_NAME = "my_data_cache";
    public static final int MAX_CACHE_SIZE = 5 * 1024 * 1024;  //默认缓存5M

    private static final String DEFAULT_FILE_PATH = "data/data/com.wind.cache.diskdatacacher/cache/"+DEFAULT_CACHE_FILE_NAME;

    private Handler handler = new Handler(Looper.getMainLooper());

    private final ThreadPoolExecutor executorService = new ThreadPoolExecutor(1, 1,
            0L, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(), sThreadFactory);

    private static final ThreadFactory sThreadFactory = new ThreadFactory() {
        public Thread newThread(Runnable r) {
            return new Thread(r, "DiskCache Task ");
        }
    };

    /**
     * 在Application的onCreate中初始化此单例，多次调用传入不同的参数，也只有第一个有效
     * @param cacheFile 缓存目录
     * @param maxSize 最大缓存带下
     */
    public static void init(File cacheFile, int maxSize) {
        if (sCacheManager == null) {
            synchronized (DiskStringCacheManager.class) {
                if (sCacheManager == null) {
                    sCacheManager = new DiskStringCacheManager(cacheFile, maxSize);
                    sCacheManager.init();
                }
            }
        }
    }

    public static DiskStringCacheManager get() {
        if (sCacheManager == null) {
            init(new File(DEFAULT_FILE_PATH), MAX_CACHE_SIZE);
        }
        return sCacheManager;
    }

    private DiskStringCacheManager(File cacheFile, int maxSize) {
        mCacheFileDir = cacheFile;
        mMaxCacheSize = maxSize;
    }

    private DataCache getDiskCacher() {
        if (mDiskCache == null) {
            synchronized (DiskDataCacher.class) {
                if (mDiskCache == null) {
                    mDiskCache = new DiskDataCacher(mCacheFileDir, mMaxCacheSize);
                }
            }
        }
        return mDiskCache;
    }

    public void init() {
        getDiskCacher().initialize();
    }

    public void putAsync(String key, String value) {
        putAsync(key, value, 0, null);
    }

    public void putAsync(String key, String value, WeakReference<Callback> weakRefCallback) {
        putAsync(key, value, 0, weakRefCallback);
    }

    public void putAsync(String key, String value, long maxValidTime) {
        putAsync(key, value, maxValidTime, null);
    }

    //使用若引用的回调防止Activity的内存泄漏
    public void putAsync(final String key, final String value, final long maxValidTime, final WeakReference<Callback> weakRefCallback) {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                put(key, value, maxValidTime);
                if (weakRefCallback != null) {
                    Callback callback = weakRefCallback.get();
                    if (callback != null) {
                        callback.actionDone(value);
                    }
                }
            }
        });
    }

    public void getAsync(final String key, final WeakReference<Callback> weakRefCallback) {
        getAsync(key, weakRefCallback, true);
    }

    public void getAsync(final String key, final WeakReference<Callback> weakRefCallback, final boolean postToMainThread) {
        executorService.submit(new Runnable() {
            @Override
            public void run() {
                final String value = get(key);
                if (weakRefCallback != null) {
                    final Callback callback = weakRefCallback.get();
                    if (callback != null) {
                        if (postToMainThread) {
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    callback.actionDone(value);
                                }
                            });
                        } else {
                            callback.actionDone(value);
                        }
                    }
                }
            }
        });

    }

    public void put(String key, String value) {
        put(key, value, 0);
    }

    /**
     * @param key          键
     * @param value        值
     * @param maxValidTime 有效期时间，单位是毫秒，比如：一天内有效，maxValidTime = 24 * 60 * 60 * 1000;
     */
    public void put(String key, String value, long maxValidTime) {
        if (TextUtils.isEmpty(key) || TextUtils.isEmpty(value)) {
            return;
        }
        byte[] data = value.getBytes();
        long validTimestamp = 0;
        if (maxValidTime > 0) {
            validTimestamp = System.currentTimeMillis() + maxValidTime;
        }
        DataCache.Entry entry = new DataCache.Entry();
        entry.data = data;
        entry.validTimestamp = validTimestamp;
        getDiskCacher().put(key, entry);
    }

    public String get(String key) {
        DataCache.Entry entry = getDiskCacher().get(key);
        if (entry == null) {
            return null;
        }
        byte[] data = entry.data;
        return new String(data);
    }

    public void delete(String key) {
        getDiskCacher().remove(key);
    }

    public void clear() {
        getDiskCacher().clear();
    }

    public interface Callback {
        void actionDone(String result);
    }
}
