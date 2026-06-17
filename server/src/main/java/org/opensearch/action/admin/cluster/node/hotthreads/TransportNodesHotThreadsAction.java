/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.action.admin.cluster.node.hotthreads;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.OpenSearchException;
import org.opensearch.action.FailedNodeException;
import org.opensearch.action.support.ActionFilters;
import org.opensearch.action.support.nodes.TransportNodesAction;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.inject.Inject;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.monitor.jvm.HotThreads;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.TransportRequest;
import org.opensearch.transport.TransportService;

import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;

/**
 * Transport action for OpenSearch Hot Threads
 *
 * @opensearch.internal
 */
public class TransportNodesHotThreadsAction extends TransportNodesAction<
    NodesHotThreadsRequest,
    NodesHotThreadsResponse,
    TransportNodesHotThreadsAction.NodeRequest,
    NodeHotThreads> {

    private static final Logger logger = LogManager.getLogger(TransportNodesHotThreadsAction.class);

    private volatile Supplier<String> nativeHotThreadsSupplier;

    @Inject
    public TransportNodesHotThreadsAction(
        ThreadPool threadPool,
        ClusterService clusterService,
        TransportService transportService,
        ActionFilters actionFilters
    ) {
        super(
            NodesHotThreadsAction.NAME,
            threadPool,
            clusterService,
            transportService,
            actionFilters,
            NodesHotThreadsRequest::new,
            NodeRequest::new,
            ThreadPool.Names.GENERIC,
            NodeHotThreads.class
        );
    }

    /**
     * Sets the native hot threads supplier from the analytics backend plugin.
     * Called during node initialization when the plugin registers its capabilities.
     */
    public void setNativeHotThreadsSupplier(Supplier<String> supplier) {
        this.nativeHotThreadsSupplier = supplier;
    }

    @Override
    protected NodesHotThreadsResponse newResponse(
        NodesHotThreadsRequest request,
        List<NodeHotThreads> responses,
        List<FailedNodeException> failures
    ) {
        return new NodesHotThreadsResponse(clusterService.getClusterName(), responses, failures);
    }

    @Override
    protected NodeRequest newNodeRequest(NodesHotThreadsRequest request) {
        return new NodeRequest(request);
    }

    @Override
    protected NodeHotThreads newNodeResponse(StreamInput in) throws IOException {
        return new NodeHotThreads(in);
    }

    @Override
    protected NodeHotThreads nodeOperation(NodeRequest request) {
        String type = request.request.type;
        StringBuilder result = new StringBuilder();

        // Capture JVM hot threads (unless type is "native" only)
        if (!"native".equals(type)) {
            HotThreads hotThreads = new HotThreads().busiestThreads(request.request.threads)
                .type(type.equals("all") ? "cpu" : type)
                .interval(request.request.interval)
                .threadElementsSnapshotCount(request.request.snapshots)
                .ignoreIdleThreads(request.request.ignoreIdleThreads);
            try {
                result.append(hotThreads.detect());
            } catch (Exception e) {
                throw new OpenSearchException("failed to detect hot threads", e);
            }
        }

        // Capture native hot threads (when type is "native" or "all")
        if ("native".equals(type) || "all".equals(type)) {
            Supplier<String> supplier = nativeHotThreadsSupplier;
            if (supplier != null) {
                try {
                    String nativeThreads = supplier.get();
                    if (nativeThreads != null && !nativeThreads.isEmpty()) {
                        if (result.length() > 0) {
                            result.append("\n\n");
                        }
                        result.append("--- Native Threads (Rust/Tokio/DataFusion) ---\n");
                        result.append(nativeThreads);
                    }
                } catch (Exception e) {
                    logger.warn("Failed to capture native hot threads", e);
                    if (result.length() > 0) {
                        result.append("\n\n");
                    }
                    result.append("--- Native Threads (error: ").append(e.getMessage()).append(") ---\n");
                }
            } else if ("native".equals(type)) {
                result.append("Native hot threads not available (no analytics backend plugin loaded)\n");
            }
        }

        return new NodeHotThreads(clusterService.localNode(), result.toString());
    }

    /**
     * Inner node request
     *
     * @opensearch.internal
     */
    public static class NodeRequest extends TransportRequest {

        NodesHotThreadsRequest request;

        public NodeRequest(StreamInput in) throws IOException {
            super(in);
            request = new NodesHotThreadsRequest(in);
        }

        NodeRequest(NodesHotThreadsRequest request) {
            this.request = request;
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            request.writeTo(out);
        }
    }
}
