/*
 * Copyright (c) 2017-2020 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.graphalgo.betweenness;

import com.carrotsearch.hppc.BitSet;
import com.carrotsearch.hppc.BitSetIterator;
import org.neo4j.graphalgo.api.Graph;
import org.neo4j.graphalgo.core.concurrency.ParallelUtil;
import org.neo4j.graphalgo.core.utils.partition.Partition;
import org.neo4j.graphalgo.core.utils.partition.PartitionUtils;

import java.util.Collection;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static com.carrotsearch.hppc.BitSetIterator.NO_MORE;

public interface SelectionStrategy {

    SelectionStrategy ALL = new SelectionStrategy() {
        @Override
        public void init(Graph graph, ExecutorService executorService, int concurrency) { }

        @Override
        public boolean select(long nodeId) {
            return true;
        }
    };

    void init(Graph graph, ExecutorService executorService, int concurrency);

    boolean select(long nodeId);

    class RandomDegree implements SelectionStrategy {

        private final long samplingSize;
        private final Optional<Long> maybeRandomSeed;

        private BitSet bitSet;

        public RandomDegree(long samplingSize) {
            this(samplingSize, Optional.empty());
        }

        public RandomDegree(long samplingSize, Optional<Long> maybeRandomSeed) {
            this.samplingSize = samplingSize;
            this.maybeRandomSeed = maybeRandomSeed;
        }

        @Override
        public void init(Graph graph, ExecutorService executorService, int concurrency) {
            assert samplingSize <= graph.nodeCount();
            this.bitSet = new BitSet(graph.nodeCount());
            var partitions = PartitionUtils.numberAlignedPartitioning(concurrency, graph.nodeCount(), Long.SIZE);
            var maxDegree = maxDegree(graph, partitions, executorService, concurrency);
            selectNodes(graph, partitions, maxDegree, executorService, concurrency);
        }

        @Override
        public boolean select(long nodeId) {
            return bitSet.get(nodeId);
        }

        private long maxDegree(
            Graph graph,
            Collection<Partition> partitions,
            ExecutorService executorService,
            int concurrency
        ) {
            AtomicInteger maxDegree = new AtomicInteger(0);

            var tasks = partitions.stream()
                .map(partition -> (Runnable) () -> {
                    var fromNode = partition.startNode;
                    var toNode = partition.startNode + partition.nodeCount;

                    for (long nodeId = fromNode; nodeId < toNode; nodeId++) {
                        int degree = graph.degree(nodeId);
                        int current = maxDegree.get();
                        while (degree > current) {
                            int newCurrent = maxDegree.compareAndExchange(current, degree);
                            if (newCurrent == current) {
                                break;
                            }
                            current = newCurrent;
                        }
                    }
                }).collect(Collectors.toList());

            ParallelUtil.runWithConcurrency(concurrency, tasks, executorService);

            return maxDegree.get();
        }

        private void selectNodes(
            Graph graph,
            Collection<Partition> partitions,
            double maxDegree,
            ExecutorService executorService,
            int concurrency
        ) {
            var random = maybeRandomSeed.map(Random::new).orElseGet(Random::new);
            var selectionSize = new AtomicLong(0);
            var tasks = partitions.stream()
                .map(partition -> (Runnable) () -> {
                    var fromNode = partition.startNode;
                    var toNode = partition.startNode + partition.nodeCount;

                    for (long nodeId = fromNode; nodeId < toNode; nodeId++) {
                        var currentSelectionSize = selectionSize.get();
                        if (currentSelectionSize >= samplingSize) {
                            break;
                        }
                        if (random.nextDouble() <= graph.degree(nodeId) / maxDegree) {
                            if (currentSelectionSize == selectionSize.compareAndExchange(
                                currentSelectionSize,
                                currentSelectionSize + 1
                            )) {
                                bitSet.set(nodeId);
                            }
                        }
                    }
                }).collect(Collectors.toList());

            ParallelUtil.runWithConcurrency(concurrency, tasks, executorService);

            long actualSelectedNodes = selectionSize.get();

            if (actualSelectedNodes < samplingSize) {
                // Flip bitset to be able to iterate unset bits.
                // The upper range is Graph#nodeCount() since
                // BitSet#size() returns a multiple of 64.
                // We need to make sure to stay within bounds.
                bitSet.flip(0, graph.nodeCount());
                // Potentially iterate the bitset multiple times
                // until we have exactly numSeedNodes nodes.
                BitSetIterator iterator;
                while (actualSelectedNodes < samplingSize) {
                    iterator = bitSet.iterator();
                    var unselectedNode = iterator.nextSetBit();
                    while (unselectedNode != NO_MORE && actualSelectedNodes < samplingSize) {
                        if (random.nextDouble() >= 0.5) {
                            bitSet.flip(unselectedNode);
                            actualSelectedNodes++;
                        }
                        unselectedNode = iterator.nextSetBit();
                    }
                }
                bitSet.flip(0, graph.nodeCount());
            }
        }
    }
}
