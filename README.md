This is a tool to cache data in disk.

# DiskDataCacher磁盘缓存工具用法以及原理  
by **Windy**
标签（空格分隔）： 磁盘缓存 工具 Android DiskCache

`DiskDataCacher`是一个轻量级的Android磁盘缓存工具
##工具用途

 - 用于缓存网络请求返回的数据，并且可以设置缓存数据的有效期，比如，缓存时间假设为1个小时，超时1小时后再次获取缓存会自动失效，让客户端重新请求新的数据，这样可以减少客户端流量，同时减少服务器并发量。
 - 用于代替`SharePreference`当做配置文件，缓存一些较大的配置数据，效率更高，可以减少内存消耗。
 - 支持扩展，扩展后可以缓存`JsonObject`、`Bitmap`、`Drawable`和序列化的java对象等等。
 
##对比**[ASimpleCache][1]**和**[DiskLruCache][2]**
&emsp;跟`ASimpleCache`比较，优点主要有：

 - 两者都是给予LRU（最近最少使用）算法，但`ASimpleCache`是使用HashMap实现lru，而`DiskDataCacher`是使用排序好的`LinkedHashMap`实现lru算法，查询过期数据的效率更高；
 - `DiskDataCacher`对线程同步的支持更好；
 - `DiskDataCacher`封装了线程池，支持异步存取。
 

&emsp;跟`DiskLruCache`比较，优点主要有：

 - `DiskDataCacher`支持设置缓存数据的有效期，再次获取超期数据会自动清除
 - `DiskDataCacher`实现方式更简单，使用更轻量，并不需要一个journal文件记录数据操作情况

 
##用法简介
`DiskStringCacheManager`是专门用来缓存字符串的工具，是单例模式，一般在Application的onCreate中进行初始化：
```
    @Override
    public void onCreate() {
        super.onCreate();
        DiskStringCacheManager.init(new File(getCacheDir(), DiskStringCacheManager.DEFAULT_CACHE_FILE_NAME),
                DiskStringCacheManager.MAX_CACHE_SIZE);
    }
```
保存数据：
```

    String cacheStringValue = "多线程比多任务更加有挑战。多线程是在同一个程序内部并行执行，\n";
    long maxTime = 3 * 60 * 1000;   //缓存有效期3分钟
    DiskStringCacheManager.get().putAsync(cacheKey, cacheStringValue);   //异步方式缓存, 缓存数据一直有效
    DiskStringCacheManager.get().putAsync(cacheKey, cacheStringValue, maxTime);  //异步方式缓存, 缓存数据一直有效期为3分钟
    DiskStringCacheManager.get().put(cacheKey, cacheStringValue, maxTime);   //同步方式缓存
```
获取数据：
```
    //异步方式获取
    DiskStringCacheManager.get().getAsync(cacheKey, new WeakReference<DiskStringCacheManager.Callback>(new DiskStringCacheManager.Callback() {
                @Override
                public void actionDone(final String result) {
                    if (!TextUtils.isEmpty(result)) {
                        textView.setText(result);
                    }
                }
            }));
    //同步方式获取
    String result = DiskStringCacheManager.get().get(cacheKey);
```
##源码剖析
###初始化方法实现思路：
初始化时，遍历缓存目录下的所有缓存文件，并读取出文件起始段的信息，此信息包含缓存文件大小，缓存有效期，缓存的键值，并将这些信息和缓存文件上次修改时间(LastModifiedTime)存到一个List中，然后将此list根据文件上次修改时间进行排序，排序好后，存到全局变量LinkedHashMap mCacheInfoMap中，这个map用于LRU算法获取缓存，具体的初始化实现如下：
```
        ...
        ...
      //先存到list中进行排序，然后再存到mCacheInfoMap中
        List<CacheInfoWithModifiedTime> cacheInfoSortList = null;
        try {
            File[] fileList = mRootDirectory.listFiles();
            cacheInfoSortList = new ArrayList<>(fileList.length); //设置初始化大小，避免扩容

            for (File file : fileList) {
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
                    ...
                } finally {
                    ...
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
            synchronized (mLock) {
                mInitialized = true;
                mLock.notifyAll();
            }
        }
```
###get方法实现思路：
先根据key从mCacheInfoMap中取缓存信息（mCacheInfoMap是一个LinkedHashMap，调用其get方法后，这个键值对就会添加到链表尾部成为最新的元素，以此实现LRU），然后根据key获取缓存文件名，从缓存文件中读取缓存内容，并将内容返回，以此实现get方法：
```
    @Override
    public Entry get(String key) {
        synchronized (mLock) {
            awaitInitializeLocked();
            //LinkedHashMap get之后，会将此键值对移到链表尾部，以实现LRU
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
                //设置时间为了初始化时排序
                cachedFile.setLastModified(System.currentTimeMillis());   
                if (info == null || !info.equals(fileInfo)) {   //一般不会出现这种情况
                    info = fileInfo;
                    mCacheInfoMap.put(key, info);
                }
                byte[] data = StreamUtils.streamToBytes(cis, (int) (cachedFile.length() - cis.bytesRead));
                return info.toCacheEntry(data);
            } catch (Exception e) {
                e.printStackTrace();
                remove(key);
            } finally {
                ...
            }
            return null;
        }
    }
```

###put方法实现思路：
存储数据之前，需要先判断存储数据到本地磁盘后，是否会超出允许的最大存阈值，即mMaxCacheSizeInBytes，超出的话，就先遍历mCacheInfoMap一遍，删除所有的过期数据，再次判断是否超出最大阈值mMaxCacheSizeInBytes，超出的话，删除mCacheInfoMap中最老的数据，直到不再超出阈值，具体代码如下：
```
    private void trimToMaxSize(int neededSpace) {
        if (mTotalSize + neededSpace < mMaxCacheSizeInBytes) {
            return;
        }
        //先删除所有的过期数据
        Iterator<Map.Entry<String, CacheInfo>> iterator = mCacheInfoMap.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, CacheInfo> entry = iterator.next();
            String key = entry.getKey();
            CacheInfo info = entry.getValue();
            if (info.isExpiredCache()) {
                File file = getFileForKey(key);
                boolean deleted = file.delete();
                if (deleted) {
                    mTotalSize -= info.size;
                } else {
                }
                iterator.remove();
            }
        }
        if (mTotalSize + neededSpace <= mMaxCacheSizeInBytes) {
            return;
        }
        //再根据Lru算法删除最老的数据，直到不超过阈值
        Iterator<Map.Entry<String, CacheInfo>> iterator2 = mCacheInfoMap.entrySet().iterator();
        while (iterator2.hasNext()) {
            Map.Entry<String, CacheInfo> entry = iterator2.next();
            String key = entry.getKey();
            CacheInfo info = entry.getValue();
            File file = getFileForKey(key);
            boolean deleted = file.delete();
            if (deleted) {
                mTotalSize -= info.size;
            } else { 
            }
            iterator2.remove();
            if (mTotalSize + neededSpace < mMaxCacheSizeInBytes * DEFAULT_LOAD_FACTOR) {
                break;
            }
        }
    }
```
判断完成之后，就将需要存储的数据信息(CacheInfo)和数据详细内容(entry.data)依次存储到文件中：
```
@Override
    public void put(String key, Entry entry) {
        synchronized (mLock) {
            awaitInitializeLocked();
            trimToMaxSize(entry.data.length);
            File file = getFileForKey(key);
            BufferedOutputStream fos = null;
            try {
                fos = new BufferedOutputStream(new FileOutputStream(file));
                CacheInfo info = new CacheInfo(key, entry);  //创建CacheInfo
                boolean success = info.writeCacheInfo(fos);   //将CacheInfo信息写入到文件前面
                if (!success) {
                    return;
                }
                fos.write(entry.data);   //将data数据写入到文件后面
                file.setLastModified(System.currentTimeMillis());//设置时间为了初始化时缓存排序
                putCacheInfo(key, info);  //保存CachInfo到map中
            } catch (Exception e) {
                boolean deleted = file.delete();
                e.printStackTrace();
            } finally {
                ...
            }
        }
    }
```
以上就是DiskDataCacher主要的实现思路

##总结

 1. 通过以上源码分析，容易知道，在get put方法一定要在初始化方法(`initialize()`)完成之后进行，因此，代码中使用了mLock.wait()和mLock.notifyAll()方法对此进行控制，`initialize()`方法最好在Application的onCreate中调用。
 2. 因为是磁盘缓存，当存储较大数据时，磁盘读写会比较耗时，因此需要在工作线程中执行，代码中已经封装好了一个工具`DiskStringCacheManager`，实现了对字符串的缓存以及线程池的封装。
 3. 需要缓存Bitmap或者JsonObject的话，只需要实现一个类似于`DiskStringCacheManager`的类，将String与byte[]的转换更改为Bitmap与byte[]的转换即可。当然，此处也有进一步的优化空间，可以将`DiskStringCacheManager`中String换成泛型，这样可以更容易扩展对其他类型数据的缓存。




  [1]: https://github.com/yangfuhai/ASimpleCache/tree/7935b04751aa57299cfb8b89e5b1e12a2d96e7cb
  [2]: https://github.com/JakeWharton/DiskLruCache
