/*
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
package io.trino.plugin.pinot.client;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.apache.pinot.common.config.GrpcConfig;
import org.apache.pinot.common.proto.PinotQueryServerGrpc;
import org.apache.pinot.common.proto.Server;

import java.util.Iterator;
import java.util.concurrent.TimeUnit;

public class PinotGrpcQueryClientImpl
        implements AutoCloseable
{
    private final ManagedChannel channel;
    private final PinotQueryServerGrpc.PinotQueryServerBlockingStub blockingStub;

    public PinotGrpcQueryClientImpl(String host, int port, GrpcConfig config)
    {
        ManagedChannelBuilder<?> builder = ManagedChannelBuilder.forAddress(host, port)
                .maxInboundMessageSize((int) config.getMaxInboundMessageSizeBytes());

        // Use plaintext if configured (adjust based on your GrpcConfig API)
        if (config.isUsePlainText()) {
            builder = builder.usePlaintext();
        }

        this.channel = builder.build();
        this.blockingStub = PinotQueryServerGrpc.newBlockingStub(channel);
    }

    public Iterator<Server.ServerResponse> submit(Server.ServerRequest request)
    {
        // server-streaming RPC -> blocking stub returns an Iterator
        return blockingStub.submit(request);
    }

    @Override
    public void close()
    {
        channel.shutdown();
        try {
            channel.awaitTermination(5, TimeUnit.SECONDS);
        }
        catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
