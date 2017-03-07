package imageloader.zzf.com.imageloaderlib.loader.utils;

import java.io.Closeable;
import java.io.IOException;

/**
 * Created by Heyha on 2017/3/6.
 */

public class ImageLoaderUtils {

    public static void close(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
