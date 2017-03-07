package imageloader.zzf.com.imageloaderlib.loader;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.StatFs;
import android.support.v4.util.LruCache;
import android.util.Log;
import android.widget.ImageView;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import imageloader.zzf.com.imageloaderlib.R;
import imageloader.zzf.com.imageloaderlib.loader.utils.ImageLoaderUtils;

/**
 * Created by Heyha on 2017/3/6.
 */

public class ImageLoader {
    private static final String TAG = "ImageLoader";
    private static final long DISK_CACHE_SIZE = 1024 * 1024 * 50;
    private static final int DISK_CACHE_INDEX = 0;
    private static final int IO_BUFFER_SIZE = 1024 * 8;
    private static final int TAG_KEY_URI = R.id.imageloader_uri;
    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();
    private static final int CORE_POOL_SIZE = Math.max(2, Math.min(CPU_COUNT - 1, 4));
    private static final int MAXIMUM_POOL_SIZE = CPU_COUNT * 2 + 1;
    private static final long KEEP_ALIVE_SECONDS = 10L;
    private static final int MESSAGE_POST_RESULT = 1;
    private static ImageLoader mImageLoader = null;

    private boolean mIsDiskCacheCreated = false;
    private ImageResizer mImageResizer = new ImageResizer();
    private DiskLruCache mDiskLruCache;
    private static LruCache<String, Bitmap> mMemoryCache;
    private Context mContext;

    /**
     * use to configure threadpoolexecutor
     */
    private static final ThreadFactory mThreadFactory = new ThreadFactory() {
        private final AtomicInteger mCount = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable r) {
            return new Thread(r, "ImageLoader#" + mCount.getAndIncrement());
        }
    };

    /**
     * threadpoolexecutor:use to load bitmap from disk or network asynchronous
     */
    public static final Executor THREAD_POOL_EXECUTOR = new ThreadPoolExecutor(CORE_POOL_SIZE, MAXIMUM_POOL_SIZE, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(), mThreadFactory);

    /**
     * used to chang calculatorThread(load bitmap) to mainThread(UI)
     */
    private Handler mainHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            if (msg.what == MESSAGE_POST_RESULT) {
                LoadResult result = (LoadResult) msg.obj;
                ImageView imageView = result.imageView;
                Bitmap bitmap = result.bitmap;
                String uri = result.uri;
                if (imageView.getTag(TAG_KEY_URI).equals(uri)) {
                    imageView.setImageBitmap(bitmap);
                } else {
                    Log.w(TAG, "set image bitmap,but uri has changed,ignored!");
                }
            }
        }
    };

    private ImageLoader(Context context) {
        mContext = context.getApplicationContext();
        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        int cacheSize = maxMemory / 8;
        mMemoryCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                int res = bitmap.getByteCount() / 1024;
                return res;
            }
        };
        File diskCacheDir = getDiskCacheDir(mContext, "bitmap");
        if (!diskCacheDir.exists()) {
            diskCacheDir.mkdirs();
        }
        if (getUsableSpace(diskCacheDir) > DISK_CACHE_SIZE) {
            try {
                mDiskLruCache = DiskLruCache.open(diskCacheDir, 1, 1, DISK_CACHE_SIZE);
                mIsDiskCacheCreated = true;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private static ImageLoader getInstance(Context context) {
        if (mImageLoader == null) {
            synchronized (ImageLoader.class) {
                if (mImageLoader == null) {
                    mImageLoader = new ImageLoader(context);
                }
            }
        }
        return mImageLoader;
    }

    /**
     * build a new instance of ImageLoader
     */
    public static ImageLoader build(Context context) {
        return getInstance(context);
    }

    /**
     * load bitmap from mem or disk or network async,then bind imageview and bitmap
     */
    public void bindBitmap(final String url, final ImageView imageView) {
        bindBitmap(url, imageView, 0, 0);
    }

    /**
     * bindBitmap with url and imageview,can set needed width and height
     */
    public void bindBitmap(final String url, final ImageView imageView, final int width, final int height) {
        imageView.setTag(TAG_KEY_URI, url);
        Bitmap bitmap = loadBitmapFromMemory(url);
        if (bitmap != null) {
            imageView.setImageBitmap(bitmap);
        }
        final Runnable loadBitmapTask = new Runnable() {
            @Override
            public void run() {
                Bitmap bitmap = loadBitmap(url, width, height);
                if (bitmap != null) {
                    LoadResult loadResult = new LoadResult(url, bitmap, imageView);
                    mainHandler.obtainMessage(MESSAGE_POST_RESULT, loadResult).sendToTarget();
                    Log.i(TAG, "bindBitmap() run in thread:" + Thread.currentThread().getName());
                }
            }
        };
        THREAD_POOL_EXECUTOR.execute(loadBitmapTask);
    }

    public Bitmap loadBitmap(String uri){
        return loadBitmap(uri,0,0);
    }

    /**
     * note that: Must run in nonUI Thread,otherwise return null
     * @param uri
     * @param reqWidth resized width
     * @param reqHeight resized height
     * @return
     */
    public Bitmap loadBitmap(String uri, int reqWidth, int reqHeight) {
        Log.i(TAG, "loadBitmap() run in thread:" + Thread.currentThread().getName());
        Bitmap bitmap = loadBitmapFromMemory(uri);
        if (bitmap != null) {
            Log.i(TAG, "loadBitmapFromMemory,url:" + uri);
            return bitmap;
        }
        if (Looper.myLooper() == Looper.getMainLooper()){
            Log.i(TAG,"loadBitmap() can not run in UI thread");
            return null;
        }
        try {
            bitmap = loadBitmapFromDiskCache(uri, reqWidth, reqHeight);
            if (bitmap != null) {
                Log.i(TAG, "loadBitmapFromDiskCache,url:" + uri);
                return bitmap;
            }
            bitmap = loadBitmapFromHttp(uri, reqWidth, reqHeight);
            Log.i(TAG, "loadBitmapFromHttp,url:" + uri);
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (bitmap == null && !mIsDiskCacheCreated) {
            Log.w(TAG, "encounter error, DiskLruCache is not created.");
            bitmap = loadBitmapFromUrl(uri);
        }
        return bitmap;
    }

    /**
     * load bitmap from url
     *
     * @param urlString
     * @return
     */
    private Bitmap loadBitmapFromUrl(String urlString) {
        Bitmap bitmap = null;
        HttpURLConnection httpURLConnection = null;
        BufferedInputStream in = null;
        try {
            final URL url = new URL(urlString);
            httpURLConnection = (HttpURLConnection) url.openConnection();
            in = new BufferedInputStream(httpURLConnection.getInputStream(), IO_BUFFER_SIZE);
            bitmap = BitmapFactory.decodeStream(in);
        } catch (MalformedURLException e) {
            Log.i(TAG, "error in download bitmap from url");
        } catch (IOException e) {
            Log.i(TAG, "error in download bitmap from url");
            e.printStackTrace();
        } finally {
            if (httpURLConnection != null) {
                httpURLConnection.disconnect();
            }
            ImageLoaderUtils.close(in);
            in = null;
            httpURLConnection = null;
        }
        return bitmap;
    }

    /**
     * add bitmap to memoryCache
     */
    private void addBitmapToMemoryCache(String key, Bitmap bitmap) {
        if (mMemoryCache.get(key) == null) {
            mMemoryCache.put(key, bitmap);
        }
    }

    /**
     * get bitmap from memoryCache
     */
    private Bitmap getBitmapFromMemoryCache(String key) {
        return mMemoryCache.get(key);
    }

    /**
     * load bitmap from mem
     *
     * @param url
     * @return
     */
    private Bitmap loadBitmapFromMemory(String url) {
        final String key = hashKeyFormUrl(url);
        Bitmap bitmap = getBitmapFromMemoryCache(key);
        return bitmap;
    }

    /**
     * load bitmap from http;1、download to disk,2、load from disk
     * note:can not in UI thread
     */
    private Bitmap loadBitmapFromHttp(String url, int reqWidth, int reqHeight) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new RuntimeException("can not visit network from UI Thread");
        }
        if (mDiskLruCache == null) {
            return null;
        }
        String key = hashKeyFormUrl(url);
        DiskLruCache.Editor editor = mDiskLruCache.edit(key);
        if (editor != null) {
            OutputStream outputStream = editor.newOutputStream(DISK_CACHE_INDEX);
            if (downloadUrlToStream(url, outputStream)) {
                editor.commit();
            } else {
                editor.abort();
            }
            mDiskLruCache.flush();
        }
        return loadBitmapFromDiskCache(url, reqWidth, reqHeight);
    }

    /**
     * load bitmap from disk cache
     *
     * @param url
     * @param reqWidth
     * @param reqHeight
     * @return
     * @throws IOException
     */
    private Bitmap loadBitmapFromDiskCache(String url, final int reqWidth, final int reqHeight) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.w(TAG, "load bitmap from UI thread, it is not recommended!");
        }
        if (mDiskLruCache == null) {
            return null;
        }
        final String key = hashKeyFormUrl(url);
        Bitmap bitmap = null;
        DiskLruCache.Snapshot snapshot = mDiskLruCache.get(key);
        if (snapshot != null) {
            FileInputStream fileInputStream = (FileInputStream) snapshot.getInputStream(DISK_CACHE_INDEX);
            FileDescriptor fileDescriptor = fileInputStream.getFD();
            bitmap = mImageResizer.decodeBitmapFromFileDescriptor(fileDescriptor, reqWidth, reqHeight);
            if (bitmap != null) {
                addBitmapToMemoryCache(key, bitmap);
            }
        }
        return bitmap;
    }
    /**
     * use url download file to outputstream
     */
    private boolean downloadUrlToStream(String urlString, OutputStream outputStream) {
        HttpURLConnection urlConnection = null;
        BufferedOutputStream out = null;
        BufferedInputStream in = null;
        try {
            final URL url = new URL(urlString);
            urlConnection = (HttpURLConnection) url.openConnection();
            in = new BufferedInputStream(urlConnection.getInputStream(), IO_BUFFER_SIZE);
            out = new BufferedOutputStream(outputStream, IO_BUFFER_SIZE);
            int b;
            while ((b = in.read()) != -1) {
                out.write(b);
            }
            return true;
        } catch (MalformedURLException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
            ImageLoaderUtils.close(in);
            ImageLoaderUtils.close(out);
            in = null;
            out = null;
            urlConnection = null;
        }
        return false;
    }

    /**
     * url md5 incryption
     *
     * @param url
     * @return
     */
    private String hashKeyFormUrl(String url) {
        String cacheKey = null;
        try {
            final MessageDigest messageDigest = MessageDigest.getInstance("MD5");
            messageDigest.update(url.getBytes());
            cacheKey = bytesToHexString(messageDigest.digest());
        } catch (NoSuchAlgorithmException e) {
            cacheKey = String.valueOf(url.hashCode());
        }
        return cacheKey;
    }

    private String bytesToHexString(byte[] digest) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < digest.length; i++) {
            String hex = Integer.toHexString(0xFF & digest[i]);
            if (hex.length() == 1) {
                sb.append('0');
            }
            sb.append(hex);
        }
        return sb.toString();
    }


    /**
     * get usable space size in disk
     */
    private long getUsableSpace(File path) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD) {
            return path.getUsableSpace();
        }
        final StatFs statFs = new StatFs(path.getPath());
        return (long) statFs.getBlockSize() * (long) statFs.getAvailableBlocks();
    }

    /**
     * get disk cache dir
     */
    private File getDiskCacheDir(Context mContext, String uniquename) {
        boolean externalStorageAvailable = Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED);
        String diskCacheDir = null;
        if (externalStorageAvailable) {
            diskCacheDir = mContext.getExternalCacheDir().getPath();
        } else {
            diskCacheDir = mContext.getCacheDir().getPath();
        }
        return new File(diskCacheDir + File.separator + uniquename);
    }

    class LoadResult {
        String uri;
        Bitmap bitmap;
        ImageView imageView;

        public LoadResult(String uri, Bitmap bitmap, ImageView imageView) {
            this.uri = uri;
            this.bitmap = bitmap;
            this.imageView = imageView;
        }
    }
}
