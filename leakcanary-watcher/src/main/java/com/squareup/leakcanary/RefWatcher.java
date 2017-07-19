/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.leakcanary;

import java.io.File;
import java.lang.ref.ReferenceQueue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;

import static com.squareup.leakcanary.HeapDumper.RETRY_LATER;
import static com.squareup.leakcanary.Preconditions.checkNotNull;
import static com.squareup.leakcanary.Retryable.Result.DONE;
import static com.squareup.leakcanary.Retryable.Result.RETRY;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

/**
 * Watches references that should become weakly reachable. When the {@link RefWatcher} detects that
 * a reference might not be weakly reachable when it should, it triggers the {@link HeapDumper}.
 * <p>
 * <p>This class is thread-safe: you can call {@link #watch(Object)} from any thread.
 */
public final class RefWatcher {

    public static final RefWatcher DISABLED = new RefWatcherBuilder<>().build();

    private final WatchExecutor watchExecutor;
    private final DebuggerControl debuggerControl;
    private final GcTrigger gcTrigger;
    private final HeapDumper heapDumper;
    private final Set<String> retainedKeys;
    private final ReferenceQueue<Object> queue;
    private final HeapDump.Listener heapdumpListener;
    private final ExcludedRefs excludedRefs;

    RefWatcher(WatchExecutor watchExecutor, DebuggerControl debuggerControl, GcTrigger gcTrigger,
               HeapDumper heapDumper, HeapDump.Listener heapdumpListener, ExcludedRefs excludedRefs) {
        this.watchExecutor = checkNotNull(watchExecutor, "watchExecutor");
        this.debuggerControl = checkNotNull(debuggerControl, "debuggerControl");
        this.gcTrigger = checkNotNull(gcTrigger, "gcTrigger");
        this.heapDumper = checkNotNull(heapDumper, "heapDumper");
        this.heapdumpListener = checkNotNull(heapdumpListener, "heapdumpListener");
        this.excludedRefs = checkNotNull(excludedRefs, "excludedRefs");
        //维护对象的一致性快照,操作都首先取得后台数组的副本,实现并发操作
        retainedKeys = new CopyOnWriteArraySet<>();
        queue = new ReferenceQueue<>();
    }

    /**
     * Identical to {@link #watch(Object, String)} with an empty string reference name.
     *
     * @see #watch(Object, String)
     */
    public void watch(Object watchedReference) {
        watch(watchedReference, "");
    }

    /**
     * Watches the provided references and checks if it can be GCed. This method is non blocking,
     * the check is done on the {@link WatchExecutor} this {@link RefWatcher} has been constructed
     * with.
     *
     * @param referenceName An logical identifier for the watched object.
     */
    public void watch(Object watchedReference, String referenceName) {
        if (this == DISABLED) {
            return;
        }
        checkNotNull(watchedReference, "watchedReference");
        checkNotNull(referenceName, "referenceName");
        //对象对应的UUID, 使用弱引用包起来
        final long watchStartNanoTime = System.nanoTime();
        String key = UUID.randomUUID().toString();
        retainedKeys.add(key);
        final KeyedWeakReference reference =
                new KeyedWeakReference(watchedReference, key, referenceName, queue);

        ensureGoneAsync(watchStartNanoTime, reference);
    }

    private void ensureGoneAsync(final long watchStartNanoTime, final KeyedWeakReference reference) {
        watchExecutor.execute(new Retryable() {
            @Override
            public Retryable.Result run() {
                return ensureGone(reference, watchStartNanoTime);
            }
        });
    }

    @SuppressWarnings("ReferenceEquality")
        // Explicitly checking for named null.
    Retryable.Result ensureGone(final KeyedWeakReference reference, final long watchStartNanoTime) {
        long gcStartNanoTime = System.nanoTime();
        long watchDurationMs = NANOSECONDS.toMillis(gcStartNanoTime - watchStartNanoTime);
        //删除已经只有弱引用的对象, 就是去掉肯定不会泄露的对象
        //// TODO: 2017/7/19  
        removeWeaklyReachableReferences();
        //正在Debug的程序直接通过检测, 上面提到的RefWatcher.DISABLED就是使用的这个来跳过的
        if (debuggerControl.isDebuggerAttached()) {
            // The debugger can create false leaks.
            return RETRY;
        }
        if (gone(reference)) {
            //如果已经确定不泄露, 直接返回
            return DONE;
        }
        //触发GC, 这里是触发, 而不是直接调用GC, 并不能保证立刻发生GC
        gcTrigger.runGc();
        //删除已经只有弱引用的对象, 就是去掉肯定不会泄露的对象
        removeWeaklyReachableReferences();
        //如果还是有强引用, 可能存在泄露
        if (!gone(reference)) {
            long startDumpHeap = System.nanoTime();
            long gcDurationMs = NANOSECONDS.toMillis(startDumpHeap - gcStartNanoTime);
            //创建堆内存镜像
            File heapDumpFile = heapDumper.dumpHeap();
            if (heapDumpFile == RETRY_LATER) {
                // Could not dump the heap.
                return RETRY;
            }
            long heapDumpDurationMs = NANOSECONDS.toMillis(System.nanoTime() - startDumpHeap);
            //最后交给dump的监听进行分析泄露
            heapdumpListener.analyze(
                    new HeapDump(heapDumpFile, reference.key, reference.name, excludedRefs, watchDurationMs,
                            gcDurationMs, heapDumpDurationMs));
        }
        return DONE;
    }

    private boolean gone(KeyedWeakReference reference) {
        return !retainedKeys.contains(reference.key);
    }

    private void removeWeaklyReachableReferences() {
        // WeakReferences are enqueued as soon as the object to which they point to becomes weakly
        // reachable. This is before finalization or garbage collection has actually happened.
        KeyedWeakReference ref;
        //remove 弱引用，在set 里面  重点是poll的方法 返回一个被回收的弱引用，记得是被回收的  才返回ref ,没有被回收的返回的是null
        while ((ref = (KeyedWeakReference) queue.poll()) != null) {
            retainedKeys.remove(ref.key);
        }
    }
}
