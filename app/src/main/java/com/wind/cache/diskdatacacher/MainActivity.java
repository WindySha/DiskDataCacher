package com.wind.cache.diskdatacacher;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import com.wind.cache.diskdatacacher.cachetool.DiskStringCacheManager;

import org.w3c.dom.Text;

import java.lang.ref.WeakReference;

public class MainActivity extends Activity {

    private TextView textView;
    String cacheKey = "http://ifeve.com/java-concurrency-thread/";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        textView = findViewById(R.id.textView);
    }

    public void onClick(View view) {
        if (view.getId() == R.id.putBtn) {
            final String cacheStringValue = "多线程比多任务更加有挑战。多线程是在同一个程序内部并行执行，\n" +
                    "因此会对相同的内存空间进行并发读写操作。这可能是在单线程程序中从来不会遇到的问题。\n" +
                    "其中的一些错误也未必会在单CPU机器上出现，因为两个线程从来不会得到真正的并行执行。\n" +
                    "然而，更现代的计算机伴随着多核CPU的出现，也就意味着不同的线程能被不同的CPU核得到真正意义的并行执行。";
            long maxTime = 3 * 60 * 1000;   //缓存有效期3分钟
            DiskStringCacheManager.get().putAsync(cacheKey, cacheStringValue);   //异步方式缓存, 缓存数据一直有效
            DiskStringCacheManager.get().putAsync(cacheKey, cacheStringValue, maxTime);  //异步方式缓存, 缓存数据一直有效期为3分钟
            DiskStringCacheManager.get().put(cacheKey, cacheStringValue);   //同步方式缓存
        } else if (view.getId() == R.id.getBtn) {
            //异步方式获取
            DiskStringCacheManager.get().getAsync(cacheKey, new WeakReference<DiskStringCacheManager.Callback>(new DiskStringCacheManager.Callback() {
                @Override
                public void actionDone(final String result) {
                    textView.setText(result);
                }
            }));
            //同步方式获取
            String result = DiskStringCacheManager.get().get(cacheKey);
        }
    }
}
