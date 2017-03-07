# ImageLoader
#自定义图片加载库，自带内存缓存和磁盘缓存
#通过BitmapFactory.Options实现了优化加载图片接口，即主动设置加载图片的宽高
#通过v4包提供的LruCache实现内存缓存机制
#通过开源DiskLruCache实现磁盘缓存
#同步加载接口ImageLoader.loadBitmap()
#异步加载接口ImageLoader.bindBitmap()，通过线程池和Handler实现异步加载机制
