package com.squareup.leakcanary;

import android.app.Application;
import android.content.Context;

import java.util.concurrent.TimeUnit;

import static com.squareup.leakcanary.RefWatcher.DISABLED;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * A {@link RefWatcherBuilder} with appropriate Android defaults.
 */
public final class AndroidRefWatcherBuilder extends RefWatcherBuilder<AndroidRefWatcherBuilder> {

    private static final long DEFAULT_WATCH_DELAY_MILLIS = SECONDS.toMillis(5);

    private final Context context;

    AndroidRefWatcherBuilder(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Sets a custom {@link AbstractAnalysisResultService} to listen to analysis results. This
     * overrides any call to {@link #heapDumpListener(HeapDump.Listener)}.
     */
    public AndroidRefWatcherBuilder listenerServiceClass(
            Class<? extends AbstractAnalysisResultService> listenerServiceClass) {

        //dump出文件后的监听, 这个是分析泄露的主力, 记住是ServiceHeapDumpListener, 会将它传给refWatcher作为成员引用
        return heapDumpListener(new ServiceHeapDumpListener(context, listenerServiceClass));
    }

    /**
     * Sets a custom delay for how long the {@link RefWatcher} should wait until it checks if a
     * tracked object has been garbage collected. This overrides any call to {@link
     * #watchExecutor(WatchExecutor)}.
     */
    public AndroidRefWatcherBuilder watchDelay(long delay, TimeUnit unit) {
        return watchExecutor(new AndroidWatchExecutor(unit.toMillis(delay)));
    }

    /**
     * Sets the maximum number of heap dumps stored. This overrides any call to {@link
     * #heapDumper(HeapDumper)} as well as any call to
     * {@link LeakCanary#setDisplayLeakActivityDirectoryProvider(LeakDirectoryProvider)})}
     *
     * @throws IllegalArgumentException if maxStoredHeapDumps < 1.
     */
    public AndroidRefWatcherBuilder maxStoredHeapDumps(int maxStoredHeapDumps) {
        LeakDirectoryProvider leakDirectoryProvider =
                new DefaultLeakDirectoryProvider(context, maxStoredHeapDumps);
        LeakCanary.setDisplayLeakActivityDirectoryProvider(leakDirectoryProvider);
        return heapDumper(new AndroidHeapDumper(context, leakDirectoryProvider));
    }

    /**
     * Creates a {@link RefWatcher} instance and starts watching activity references (on ICS+).
     */
    public RefWatcher buildAndInstall() {
        //这里构造了最重要的对象, refWatcher!,
        RefWatcher refWatcher = build();

        //1.4.0之前的写法
        //if (isInAnalyzerProcess(application)) {
        //    return RefWatcher.DISABLED;
        //  }
        if (refWatcher != DISABLED) {
            //确保可以使用DisplayLeakActivity
            LeakCanary.enableDisplayLeakActivity(context);
           //这里是我们的正正要开始监听的方法
            ActivityRefWatcher.install((Application) context, refWatcher);
        }
        return refWatcher;
    }

    @Override
    protected boolean isDisabled() {
        return LeakCanary.isInAnalyzerProcess(context);
    }

    @Override
    protected HeapDumper defaultHeapDumper() {
        //LeakDirectoryProvider这个类主要用于初始化SD卡存放LeakCanary dump出来的文件存放目录, 判断是否重复等,
        LeakDirectoryProvider leakDirectoryProvider = new DefaultLeakDirectoryProvider(context);
        return new AndroidHeapDumper(context, leakDirectoryProvider);
    }

    @Override
    protected DebuggerControl defaultDebuggerControl() {
        return new AndroidDebuggerControl();
    }

    @Override
    protected HeapDump.Listener defaultHeapDumpListener() {
        //// TODO: 2017/7/15   返回一个DisplayLeakService
        return new ServiceHeapDumpListener(context, DisplayLeakService.class);
    }

    @Override
    protected ExcludedRefs defaultExcludedRefs() {
        return AndroidExcludedRefs.createAppDefaults().build();
    }

    @Override
    protected WatchExecutor defaultWatchExecutor() {
        //executor是等5秒钟之后才真正运行交给它的Runnable. 也就是说我们在关掉Activity或者Fragment之后5秒内完成内存资源的释放, LeakCanary都认为不是内存泄露.
        return new AndroidWatchExecutor(DEFAULT_WATCH_DELAY_MILLIS);
    }
}
