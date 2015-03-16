/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.examples8.computegrid;

import org.apache.ignite.*;
import org.apache.ignite.cluster.*;
import org.apache.ignite.compute.*;
import org.apache.ignite.lang.*;
import org.apache.ignite.resources.*;
import org.jetbrains.annotations.*;

import java.math.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * This example demonstrates how to use continuation feature of Ignite by
 * performing the distributed recursive calculation of {@code 'Fibonacci'}
 * numbers on the cluster. Continuations
 * functionality is exposed via {@link org.apache.ignite.compute.ComputeJobContext#holdcc()} and
 * {@link org.apache.ignite.compute.ComputeJobContext#callcc()} method calls in {@link FibonacciClosure} class.
 * <p>
 * Remote nodes should always be started with special configuration file which
 * enables P2P class loading: {@code 'ignite.{sh|bat} examples/config/example-compute.xml'}.
 * <p>
 * Alternatively you can run {@link org.apache.ignite.examples8.ComputeNodeStartup} in another JVM which will start node
 * with {@code examples/config/example-compute.xml} configuration.
 */
public final class ComputeFibonacciContinuationExample {
    /**
     * Executes example.
     *
     * @param args Command line arguments, none required.
     * @throws org.apache.ignite.IgniteException If example execution failed.
     */
    public static void main(String[] args) throws IgniteException {
        try (Ignite ignite = Ignition.start("examples/config/example-compute.xml")) {
            System.out.println();
            System.out.println("Compute Fibonacci continuation example started.");

            long N = 100;

            final UUID exampleNodeId = ignite.cluster().localNode().id();

            // Filter to exclude this node from execution.
            final IgnitePredicate<ClusterNode> nodeFilter = n -> {
                // Give preference to remote nodes.
                return ignite.cluster().forRemotes().nodes().isEmpty() || !n.id().equals(exampleNodeId);
            };

            long start = System.currentTimeMillis();

            BigInteger fib = ignite.compute(ignite.cluster().forPredicate(nodeFilter)).apply(new FibonacciClosure(nodeFilter), N);

            long duration = System.currentTimeMillis() - start;

            System.out.println();
            System.out.println(">>> Finished executing Fibonacci for '" + N + "' in " + duration + " ms.");
            System.out.println(">>> Fibonacci sequence for input number '" + N + "' is '" + fib + "'.");
            System.out.println(">>> If you re-run this example w/o stopping remote nodes - the performance will");
            System.out.println(">>> increase since intermediate results are pre-cache on remote nodes.");
            System.out.println(">>> You should see prints out every recursive Fibonacci execution on cluster nodes.");
            System.out.println(">>> Check remote nodes for output.");
        }
    }

    /**
     * Closure to execute.
     */
    private static class FibonacciClosure implements IgniteClosure<Long, BigInteger> {
        /** Future for spawned task. */
        private IgniteFuture<BigInteger> fut1;

        /** Future for spawned task. */
        private IgniteFuture<BigInteger> fut2;

        /** Auto-inject job context. */
        @JobContextResource
        private ComputeJobContext jobCtx;

        /** Auto-inject ignite instance. */
        @IgniteInstanceResource
        private Ignite ignite;

        /** Predicate. */
        private final IgnitePredicate<ClusterNode> nodeFilter;

        /**
         * @param nodeFilter Predicate to filter nodes.
         */
        FibonacciClosure(IgnitePredicate<ClusterNode> nodeFilter) {
            this.nodeFilter = nodeFilter;
        }

        /** {@inheritDoc} */
        @Nullable @Override public BigInteger apply(Long n) {
            if (fut1 == null || fut2 == null) {
                System.out.println();
                System.out.println(">>> Starting fibonacci execution for number: " + n);

                // Make sure n is not negative.
                n = Math.abs(n);

                if (n <= 2)
                    return n == 0 ? BigInteger.ZERO : BigInteger.ONE;

                // Node-local storage.
                ConcurrentMap<Long, IgniteFuture<BigInteger>> locMap = ignite.cluster().nodeLocalMap();

                // Check if value is cached in node-local-map first.
                fut1 = locMap.get(n - 1);
                fut2 = locMap.get(n - 2);

                ClusterGroup p = ignite.cluster().forPredicate(nodeFilter);

                IgniteCompute compute = ignite.compute(p).withAsync();

                // If future is not cached in node-local-map, cache it.
                if (fut1 == null) {
                    compute.apply(new FibonacciClosure(nodeFilter), n - 1);

                    ComputeTaskFuture<BigInteger> futVal = compute.future();

                    fut1 = locMap.putIfAbsent(n - 1, futVal);

                    if (fut1 == null)
                        fut1 = futVal;
                }

                // If future is not cached in node-local-map, cache it.
                if (fut2 == null) {
                    compute.apply(new FibonacciClosure(nodeFilter), n - 2);

                    ComputeTaskFuture<BigInteger> futVal = compute.<BigInteger>future();

                    fut2 = locMap.putIfAbsent(n - 2, futVal);

                    if (fut2 == null)
                        fut2 = futVal;
                }

                // If futures are not done, then wait asynchronously for the result
                if (!fut1.isDone() || !fut2.isDone()) {
                    IgniteInClosure<IgniteFuture<BigInteger>> lsnr = f -> {
                        // If both futures are done, resume the continuation.
                        if (fut1.isDone() && fut2.isDone())
                            // CONTINUATION:
                            // =============
                            // Resume suspended job execution.
                            jobCtx.callcc();
                    };

                    // CONTINUATION:
                    // =============
                    // Hold (suspend) job execution.
                    // It will be resumed in listener above via 'callcc()' call
                    // once both futures are done.
                    jobCtx.holdcc();

                    // Attach the same listener to both futures.
                    fut1.listen(lsnr);
                    fut2.listen(lsnr);

                    return null;
                }
            }

            assert fut1.isDone() && fut2.isDone();

            // Return cached results.
            return fut1.get().add(fut2.get());
        }
    }
}
