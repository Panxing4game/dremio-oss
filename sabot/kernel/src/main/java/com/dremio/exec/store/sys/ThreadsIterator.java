/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.exec.store.sys;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Iterator;

import com.dremio.common.VM;
import com.dremio.exec.proto.CoordinationProtos.NodeEndpoint;
import com.dremio.exec.server.SabotContext;
import com.dremio.exec.work.WorkStats;
import com.dremio.sabot.exec.context.OperatorContext;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Predicates;
import com.google.common.collect.Iterators;
import com.google.common.primitives.Longs;

/**
 * Iterator that returns a {@link ThreadSummary} for every thread in this JVM
 */
public class ThreadsIterator implements Iterator<Object> {
  private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(ThreadsIterator.class);

  private final SabotContext dbContext;
  private final Iterator<ThreadInfo> threadInfoIterator;
  private final WorkStats stats;
  private final ThreadMXBean threadMXBean;

  public ThreadsIterator(final SabotContext dbContext, final OperatorContext context) {
    this.dbContext = dbContext;
    threadMXBean = ManagementFactory.getThreadMXBean();
    final long[] ids = threadMXBean.getAllThreadIds();

    final Iterator<Long> threadIdIterator = Longs.asList(ids).iterator();

    this.threadInfoIterator = Iterators.filter(
      Iterators.transform(threadIdIterator, new Function<Long, ThreadInfo>() {

        @Override
        public ThreadInfo apply(Long input) {
          return threadMXBean.getThreadInfo(input, 100);
        }
      }),
      Predicates.notNull());

    logger.debug("number of threads = {}, number of cores = {}", ids.length, VM.availableProcessors());

    this.stats = dbContext.getWorkStatsProvider().get();
  }

  @Override
  public boolean hasNext() {
    return threadInfoIterator.hasNext();
  }

  @Override
  public Object next() {
    ThreadInfo currentThread = threadInfoIterator.next();
    final NodeEndpoint endpoint = dbContext.getEndpoint();
    final long id = currentThread.getThreadId();
    return new ThreadSummary(endpoint.getAddress(),
            endpoint.getFabricPort(),
            currentThread.getThreadName(),
            currentThread.getThreadId(),
            currentThread.isInNative(),
            currentThread.isSuspended(),
            currentThread.getThreadState().name(),
            stats.getCpuTrailingAverage(id, 1),
            stats.getUserTrailingAverage(id, 1),
            VM.availableProcessors(),
            getStackTrace(currentThread));
  }

  private String getStackTrace(ThreadInfo currentThread) {
    StackTraceElement[] stackTrace = currentThread.getStackTrace();
    return Joiner.on("\n").join(stackTrace);
  }

  @Override
  public void remove() {
    throw new UnsupportedOperationException();
  }

  public static class ThreadSummary {
    /**
     * The SabotNode hostname
     */
    public final String hostname;

    /**
     * The SabotNode user port
     */
    public final long fabric_port;
    public final String threadName;
    public final long threadId;
    public final boolean inNative;
    public final boolean suspended;
    public final String threadState;
    /**
     * Thread cpu time during last second. Between 0 and 100
     */
    public final Integer cpuTime;
    /**
     * Thread user cpu time during last second. Between 0 and 100
     */
    public final Integer userTime;
    public final Integer cores;
    public final String stackTrace;

    public ThreadSummary(String hostname, long fabric_port, String threadName, long threadId, boolean inNative, boolean suspended, String threadState, Integer cpuTime, Integer userTime, Integer cores, String stackTrace) {
      this.hostname = hostname;
      this.fabric_port = fabric_port;
      this.threadName = threadName;
      this.threadId = threadId;
      this.inNative = inNative;
      this.suspended = suspended;
      this.threadState = threadState;
      this.cpuTime = cpuTime;
      this.userTime = userTime;
      this.cores = cores;
      this.stackTrace = stackTrace;
    }
  }
}
