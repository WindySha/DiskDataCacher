package com.wind.cache.diskdatacacher.cachetool;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


/**
 * 基于LRU算法的磁盘缓存工具
 * 支持缓存有效期的设置，当缓存文件达到最大阈值时，先删除过期数据，再删除最近最少使用的数据
 * added by Windy
 */

public class DiskDataCacher implements DataCache {

    public static final String TAG = DiskDataCacher.class.getSimpleName();

    public static final boolean DEBUG = true;

    private static final int DEFAULT_DISK_USAGE_BYTES = 5 * 1024 * 1024;

    private final File mRootDirectory;

    private final int mMaxCacheSizeInBytes;

    private final float DEFAULT_LOAD_FACTOR = 0.9f;

    private SafeKeyGenerator mSafeKeyGenerator;

    //保存cache信息的map
    //使用LinkHashMap的目的是为了实现LRU算法，最后一个参数accessOrder一定要为true
    private final Map<String, CacheInfo> mCacheInfoMap =
            new LinkedHashMap<String, CacheInfo>(16, .75f, true);

    //缓存总共占用的空间大小，单位是bytes
    private long mTotalSize;

    private final Object mLock = new Object();
    private boolean mInitialized = false;


    public DiskDataCacher(File rootDirectory, int maxCacheSizeInBytes) {
        mRootDirectory = rootDirectory;
        mMaxCacheSizeInBytes = maxCacheSizeInBytes;
        mSafeKeyGenerator = new SafeKeyGenerator();
    }

    public DiskDataCacher(File rootDirectory) {
        this(rootDirectory, DEFAULT_DISK_USAGE_BYTES);
    }

    public DiskDataCacher(Context context, String cacheFolderName) {
        this(new File(context.getCacheDir(), cacheFolderName));
    }

    public DiskDataCacher(Context context, String cacheFolderName, int maxSize) {
        this(new File(context.getCacheDir(), cacheFolderName), maxSize);
    }

    //初始化保存cacheInfoMap
    @Override
    public void initialize() {
        new Thread("DiskDataCacher-init") {
            public void run() {
                initDataFromDisk();
            }
        }.start();
    }

    private void initDataFromDisk() {
        if (!mRootDirectory.exists()) {
            if (!mRootDirectory.mkdirs()) {
                throw new IllegalArgumentException("Unable to create cache dir" + mRootDirectory.getAbsolutePath());
            }
        }
        synchronized (mLock) {
            if (mInitialized) {
                return;
            }
        }

        //先存到list中进行排序，然后再存到mCacheInfoMap中
        List<CacheInfoWithModifiedTime> cacheInfoSortList = null;

        try {
            File[] fileList = mRootDirectory.listFiles();
            if (fileList == null || fileList.length == 0) {
                return;
            }

            cacheInfoSortList = new ArrayList<>(fileList.length);

            for (File file : fileList) {
                if (file == null || !file.exists()) {
                    continue;
                }
                BufferedInputStream fis = null;
                try {
                    fis = new BufferedInputStream(new FileInputStream(file));
                    CacheInfo info = CacheInfo.readCacheInfo(fis);
                    info.size = file.length();
                    //初始化时，遇到过期的数据，需要清除掉
                    if (info.isExpiredCache()) {
                        file.delete();
                        continue;
                    }
                    long fileLastModifiedTime = file.lastModified();
                    CacheInfoWithModifiedTime infoWithModifiedTime = new CacheInfoWithModifiedTime(info, fileLastModifiedTime);
                    cacheInfoSortList.add(infoWithModifiedTime);
                } catch (Exception e) {
                    Log.e(TAG, " initialize exception " + e.getMessage());
                    file.delete();
                    e.printStackTrace();
                } finally {
                    if (fis != null) {
                        try {
                            fis.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        } finally {
            if (cacheInfoSortList != null && cacheInfoSortList.size() != 0) {
                //对文件中取到的CacheInfo按照时间排序，用以实现最近最少原则
                Collections.sort(cacheInfoSortList, new FileModifiedTimeComparator());
                for (CacheInfoWithModifiedTime infoWithModifiedTime : cacheInfoSortList) {
                    putCacheInfo(infoWithModifiedTime.info.key, infoWithModifiedTime.info);
                }
            }
            Log("DiskCache initialize finish !!!!");
            synchronized (mLock) {
                mInitialized = true;
                mLock.notifyAll();
            }
        }

    }

    //等待初始化完成再进行其他操作
    private void awaitInitializeLocked() {
        while (!mInitialized) {
            try {
                mLock.wait();
            } catch (InterruptedException unused) {
            }
        }
    }

    private class FileModifiedTimeComparator implements Comparator<CacheInfoWithModifiedTime> {
        @Override
        public int compare(CacheInfoWithModifiedTime o1, CacheInfoWithModifiedTime o2) {
            if (o1.lastModifiedTime < o2.lastModifiedTime) {
                return -1;
            } else if (o1.lastModifiedTime > o2.lastModifiedTime) {
                return 1;
            } else {
                return 0;
            }
        }
    }

    @Override
    public Entry get(String key) {
        synchronized (mLock) {
            awaitInitializeLocked();
            CacheInfo info = mCacheInfoMap.get(key);
            File cachedFile = getFileForKey(key);
            //缓存文件不存在
            if (!cachedFile.exists()) {
                removeCacheInfo(key);
                return null;
            }
            //缓存的数据已经过期
            if (info != null && info.isExpiredCache()) {
                removeCacheInfo(key);
                cachedFile.delete();
                return null;
            }
            CountingInputStream cis = null;
            try {
                cis = new CountingInputStream(new BufferedInputStream(new FileInputStream(cachedFile)));
                CacheInfo fileInfo = CacheInfo.readCacheInfo(cis);
                fileInfo.size = cachedFile.length();
                cachedFile.setLastModified(System.currentTimeMillis());   //注意：此处的时间精度只能精确到秒，因此get时可能会丢失精度
                Log(" get Entry and set lastModifiedTime = " + System.currentTimeMillis()+" key = "+fileInfo.key);
                if (info == null || !info.equals(fileInfo)) {   //一般不会出现这种情况，也可以不要此处代码
                    info = fileInfo;
                    mCacheInfoMap.put(key, info);
                }
                byte[] data = StreamUtils.streamToBytes(cis, (int) (cachedFile.length() - cis.bytesRead));
                return info.toCacheEntry(data);
            } catch (Exception e) {
                Log.e(TAG, " get Entry Exception e " + e);
                e.printStackTrace();
                remove(key);
            } finally {
                if (cis != null) {
                    try {
                        cis.close();
                    } catch (IOException ioe) {
                        ioe.printStackTrace();
                    }
                }
            }
            return null;
        }
    }

    @Override
    public void put(String key, Entry entry) {
        if (TextUtils.isEmpty(key) || entry == null) {
            throw new NullPointerException("key == null || value == null");
        }
        synchronized (mLock) {
            awaitInitializeLocked();
            trimToMaxSize(entry.data.length);
            File file = getFileForKey(key);
            BufferedOutputStream fos = null;
            try {
                Log("start DiskCache put " + file.getAbsolutePath());
                fos = new BufferedOutputStream(new FileOutputStream(file));
                CacheInfo info = new CacheInfo(key, entry);  //创建CacheInfo
                boolean success = info.writeCacheInfo(fos);   //将CacheInfo信息写入到文件前面
                if (!success) {
                    Log.e(TAG, "Failed to write CacheInfo for " + file.getAbsolutePath());
                    return;
                }
                fos.write(entry.data);   //将data数据写入到文件后面
                file.setLastModified(System.currentTimeMillis());
                Log( " put Entry and set lastModifiedTime = " + System.currentTimeMillis()+" key = "+info.key);
                putCacheInfo(key, info);  //保存CachInfo到map中
            } catch (Exception e) {
                boolean deleted = file.delete();
                e.printStackTrace();
            } finally {
                if (fos != null) {
                    try {
                        fos.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    /**
     * 根据当前保存文件大小，判断是否超过最大值  超过的话 先全部删除过期数据  再根据LRU算法删除最早最不常用的数据
     *
     * @param neededSpace 需要保存的大小
     */
    private void trimToMaxSize(int neededSpace) {
        if (mTotalSize + neededSpace < mMaxCacheSizeInBytes) {
            return;
        }
        //先删除全部过期数据  再根据Lru算法删除数据
        Iterator<Map.Entry<String, CacheInfo>> iterator = mCacheInfoMap.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, CacheInfo> entry = iterator.next();
            String key = entry.getKey();
            CacheInfo info = entry.getValue();
            if (info.isExpiredCache()) {
                File file = getFileForKey(key);
                boolean deleted = file.delete();
                Log("trimToSize expired file key= " + key + "  file name=" + file.getName());
                if (deleted) {
                    mTotalSize -= info.size;
                } else {
                    Log.e(TAG, " trimToSize, deleted expired file failed file path is " + file.getAbsolutePath() + " key is " + key);
                }
                iterator.remove();
            }
        }
        if (mTotalSize + neededSpace <= mMaxCacheSizeInBytes) {
            return;
        }
        Iterator<Map.Entry<String, CacheInfo>> iterator2 = mCacheInfoMap.entrySet().iterator();
        while (iterator2.hasNext()) {
            Map.Entry<String, CacheInfo> entry = iterator2.next();
            String key = entry.getKey();
            CacheInfo info = entry.getValue();
            File file = getFileForKey(key);
            boolean deleted = file.delete();
            Log("trimToSize delete lru file key= " + key + "  file name" + file.getName() + " mTotalSize=" + mTotalSize + " info.size=" + info.size);
            if (deleted) {
                mTotalSize -= info.size;
            } else {
                Log.e(TAG, " trimToSize, deleted file failed file path is " + file.getAbsolutePath() + " key is " + key);
            }
            iterator2.remove();
            if (mTotalSize + neededSpace < mMaxCacheSizeInBytes * DEFAULT_LOAD_FACTOR) {
                break;
            }
        }
    }

    @Override
    public void remove(String key) {
        synchronized (mLock) {
            awaitInitializeLocked();
            removeCacheInfo(key);
            File file = getFileForKey(key);
            if (file != null) {
                boolean deleted = file.delete();
                if (!deleted) {
                    Log("remove key, delete file failed, file is " + file.getName() + " key = " + key);
                }
            }
        }
    }

    @Override
    public synchronized void clear() {
        synchronized (mLock) {
            awaitInitializeLocked();
            mCacheInfoMap.clear();
            mTotalSize = 0;
        }
        File[] fileList = mRootDirectory.listFiles();
        if (fileList != null && fileList.length > 0) {
            for (File file : fileList) {
                if (file != null) {
                    boolean deleted = file.delete();
                    if (!deleted) {
                        Log("do clear, delete file failed, file is " + file.getName());
                    }
                }
            }
        }
    }

    private void putCacheInfo(String key, CacheInfo info) {
        CacheInfo previousInfo = mCacheInfoMap.get(key);
        long previousSize = 0;
        long newSize = info.size;
        if (previousInfo != null) {
            previousSize = previousInfo.size;
        }
        mTotalSize += (newSize - previousSize);
        mCacheInfoMap.put(key, info);
    }

    private void removeCacheInfo(String key) {
        CacheInfo info = mCacheInfoMap.get(key);
        if (info != null) {
            mTotalSize -= info.size;
            mCacheInfoMap.remove(key);
        }
    }

    public File getFileForKey(String key) {
        return new File(mRootDirectory, getFileSafeNameForKey(key));
    }

    /**
     * 直接使用key来做文件名不可行，因为key中可能存在不能命名文件的字符，因此需要转换
     * 另外一种编码方式是使用MD5编码，参考：http://blog.csdn.net/guolin_blog/article/details/28863651
     **/
    private String getFileSafeNameForKey(String key) {
        String safeKey = mSafeKeyGenerator.getSafeKey(key);
        return safeKey;
    }

    static class CacheInfoWithModifiedTime {
        public long lastModifiedTime;
        public CacheInfo info;

        public CacheInfoWithModifiedTime(CacheInfo info, long lastModifiedTime) {
            this.info = info;
            this.lastModifiedTime = lastModifiedTime;
        }
    }

    /**
     * 保存cache信息，包括大小size，有效期validTimestamp和键值key
     * 与Entry不同的是，Entry保存持有的是存到文件中的数据，比较大，因为不能用Map缓存
     */
    static class CacheInfo {

        //缓存的大小
        public long size;

        //有效期的时间戳, 0表示未设置有效时间
        public long validTimestamp;

        //键值
        public String key;

        private CacheInfo() {
        }

        public CacheInfo(String key, Entry entry) {
            this.key = key;
            this.size = entry.data.length;
            this.validTimestamp = entry.validTimestamp;
        }

        //根据CacheInfo创建一个Entry
        public Entry toCacheEntry(byte[] data) {
            Entry e = new Entry();
            e.data = data;
            e.validTimestamp = validTimestamp;
            return e;
        }

        /**
         * 从数据流中读取cache信息
         *
         * @param is
         * @return
         * @throws IOException
         */
        public static CacheInfo readCacheInfo(InputStream is) throws IOException {
            CacheInfo infoEntry = new CacheInfo();
            infoEntry.validTimestamp = StreamUtils.readLong(is);
            infoEntry.key = StreamUtils.readString(is);
            return infoEntry;
        }

        public boolean writeCacheInfo(OutputStream os) {
            try {
                StreamUtils.writeLong(os, validTimestamp);
                StreamUtils.writeString(os, key == null ? "" : key);
                return true;
            } catch (IOException e) {
                Log.e(TAG, e.toString(), e);
                return false;
            } finally {
                try {
                    os.flush();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        /**
         * 判断当前cache文件是否过期
         *
         * @return true 过期
         */
        public boolean isExpiredCache() {
            return validTimestamp < System.currentTimeMillis() && validTimestamp > 0;
        }


        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof CacheInfo)) {
                return false;
            }
            CacheInfo info = (CacheInfo) obj;
            if (size == info.size && validTimestamp == info.validTimestamp && key != null && key.equals(info.key)) {
                return true;
            }
            return false;
        }
    }

    static class StreamUtils {
        private static int read(InputStream is) throws IOException {
            int b = is.read();
            if (b == -1) {
                throw new EOFException();
            }
            return b;
        }

        static void writeInt(OutputStream os, int n) throws IOException {
            os.write((n >> 0) & 0xff);
            os.write((n >> 8) & 0xff);
            os.write((n >> 16) & 0xff);
            os.write((n >> 24) & 0xff);
        }

        static int readInt(InputStream is) throws IOException {
            int n = 0;
            n |= (read(is) << 0);
            n |= (read(is) << 8);
            n |= (read(is) << 16);
            n |= (read(is) << 24);
            return n;
        }

        static void writeLong(OutputStream os, long n) throws IOException {
            os.write((byte) (n >>> 0));
            os.write((byte) (n >>> 8));
            os.write((byte) (n >>> 16));
            os.write((byte) (n >>> 24));
            os.write((byte) (n >>> 32));
            os.write((byte) (n >>> 40));
            os.write((byte) (n >>> 48));
            os.write((byte) (n >>> 56));
        }

        static long readLong(InputStream is) throws IOException {
            long n = 0;
            n |= ((read(is) & 0xFFL) << 0);
            n |= ((read(is) & 0xFFL) << 8);
            n |= ((read(is) & 0xFFL) << 16);
            n |= ((read(is) & 0xFFL) << 24);
            n |= ((read(is) & 0xFFL) << 32);
            n |= ((read(is) & 0xFFL) << 40);
            n |= ((read(is) & 0xFFL) << 48);
            n |= ((read(is) & 0xFFL) << 56);
            return n;
        }

        static void writeString(OutputStream os, String s) throws IOException {
            byte[] b = s.getBytes();
            writeLong(os, b.length);
            os.write(b, 0, b.length);
        }

        static String readString(InputStream is) throws IOException {
            int n = (int) readLong(is);
            byte[] b = streamToBytes(is, n);
            return new String(b);
        }

        public static byte[] streamToBytes(InputStream in, int length) throws IOException {
            byte[] bytes = new byte[length];
            int count;
            int pos = 0;
            while (pos < length && ((count = in.read(bytes, pos, length - pos)) != -1)) {
                pos += count;
            }
            if (pos != length) {
                throw new IOException("Expected " + length + " bytes, read " + pos + " bytes");
            }
            return bytes;
        }
    }

    private static class CountingInputStream extends FilterInputStream {
        private int bytesRead = 0;

        private CountingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int result = super.read();
            if (result != -1) {
                bytesRead++;
            }
            return result;
        }

        @Override
        public int read(byte[] buffer, int offset, int count) throws IOException {
            int result = super.read(buffer, offset, count);
            if (result != -1) {
                bytesRead += result;
            }
            return result;
        }
    }

    private void Log(String msg) {
        if (DEBUG) {
            Log.d(TAG, msg);
        }
    }
}
