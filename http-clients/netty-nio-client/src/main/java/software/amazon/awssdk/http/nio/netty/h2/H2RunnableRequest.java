/*
 * Copyright 2010-2017 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.awssdk.http.nio.netty.h2;

import static software.amazon.awssdk.http.nio.netty.internal.ChannelAttributeKeys.REQUEST_CONTEXT_KEY;
import static software.amazon.awssdk.http.nio.netty.internal.ChannelAttributeKeys.RESPONSE_COMPLETE_KEY;

import com.typesafe.netty.http.HttpStreamsClientHandler;
import com.typesafe.netty.http.StreamedHttpRequest;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import io.netty.util.concurrent.Future;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.function.Supplier;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.http.async.AbortableRunnable;
import software.amazon.awssdk.http.nio.netty.internal.ChannelAttributeKeys;
import software.amazon.awssdk.http.nio.netty.internal.RequestContext;
import software.amazon.awssdk.http.nio.netty.internal.ResponseHandler;
import software.amazon.awssdk.utils.FunctionalUtils.UnsafeRunnable;

public final class H2RunnableRequest implements AbortableRunnable {

    private static final Logger log = LoggerFactory.getLogger(H2RunnableRequest.class);
    private final RequestContext context;
    private volatile Channel channel;

    public H2RunnableRequest(RequestContext context) {
        this.context = context;
    }

    @Override
    public void run() {
        context.channelPool().acquire().addListener((Future<Channel> channelFuture) -> {
            if (channelFuture.isSuccess()) {
                try {
                    channel = channelFuture.getNow();
                    channel.attr(REQUEST_CONTEXT_KEY).set(context);
                    channel.attr(RESPONSE_COMPLETE_KEY).set(false);
                    // TODO don't need this anymore but need to port of Kyle's timeout handler changes
                    removePerRequestHandlers();
                    makeRequest(context.nettyRequest());
                } catch (Exception e) {
                    handleFailure(() -> "Failed to make request to " + endpoint(), e);
                }
            } else {
                handleFailure(() -> "Failed to create connection to " + endpoint(), channelFuture.cause());
            }
        });
    }

    @Override
    public void abort() {
        if (channel != null) {
            closeAndRelease(channel);
        }
    }

    private void removePerRequestHandlers() {
        // Remove any existing handlers from the pipeline from the previous request.
        removeIfExists(HttpStreamsClientHandler.class, ResponseHandler.class,
                       ReadTimeoutHandler.class, WriteTimeoutHandler.class);
    }

    /**
     * Removes the handler from the pipeline if present.
     *
     * @param handlers Handlers to remove, identified by class.
     */
    @SafeVarargs
    private final void removeIfExists(Class<? extends ChannelHandler>... handlers) {
        for (Class<? extends ChannelHandler> handler : handlers) {
            if (channel.pipeline().get(handler) != null) {
                channel.pipeline().remove(handler);
            }
        }
    }

    private void makeRequest(HttpRequest request) {
        log.debug("Writing request: {}", request);

        // TODO SSL
        channel.attr(ChannelAttributeKeys.PROTOCOL_FUTURE).get()
               .thenAccept(protocol ->
                               runOrFail(() -> {
                                             configurePipeline(protocol);
                                             writeRequest(request);
                                         },
                                         () -> "Failed to make request to " + endpoint()));
    }

    private void configurePipeline(String protocol) {
        // TODO configurable timeouts
        if (ApplicationProtocolNames.HTTP_2.equals(protocol)) {
            channel.pipeline().addFirst(new WriteTimeoutHandler(50));
            channel.pipeline().addFirst(new ReadTimeoutHandler(50));
            channel.pipeline().addLast(new HttpStreamsClientHandler());
        } else if (ApplicationProtocolNames.HTTP_1_1.equals(protocol)) {
            channel.pipeline().addFirst(new WriteTimeoutHandler(50));
            channel.pipeline().addFirst(new ReadTimeoutHandler(50));
            channel.pipeline().addLast(new HttpStreamsClientHandler());
            channel.pipeline().addLast(new ResponseHandler());
        } else {
            throw new RuntimeException("Unknown protocol: " + protocol);
        }
    }

    private void writeRequest(HttpRequest request) {
        channel.writeAndFlush(new StreamedRequest(request, context.sdkRequestProvider(), channel))
               .addListener(wireCall -> {
                   if (wireCall.isSuccess()) {
                       // Auto-read is turned off so trigger an explicit read to give control to HttpStreamsClientHandler
                       // TODO is this appropriate for H2?
                       channel.read();
                   } else {
                       handleFailure(() -> "Failed to make request to " + endpoint(), wireCall.cause());
                   }
               });
    }

    private URI endpoint() {
        return context.sdkRequest().getUri();
    }

    private void runOrFail(Runnable runnable, Supplier<String> errorMsgSupplier) {
        try {
            runnable.run();
        } catch (Exception e) {
            handleFailure(errorMsgSupplier, e);
        }
    }

    private void handleFailure(Supplier<String> msg, Throwable cause) {
        log.error(msg.get(), cause);
        runAndLogError("Exception thrown from AsyncResponseHandler",
                       () -> context.handler().exceptionOccurred(cause));
        if (channel != null) {
            runAndLogError("Unable to release channel back to the pool.",
                           () -> closeAndRelease(channel));
        }
    }

    private static void closeAndRelease(Channel channel) {
        RequestContext requestCtx = channel.attr(REQUEST_CONTEXT_KEY).get();
        channel.close().addListener(ignored -> requestCtx.channelPool().release(channel));
    }

    /**
     * Runs a given {@link UnsafeRunnable} and logs an error without throwing.
     *
     * @param errorMsg Message to log with exception thrown.
     * @param runnable Action to perform.
     */
    private static void runAndLogError(String errorMsg, UnsafeRunnable runnable) {
        try {
            runnable.run();
        } catch (Exception e) {
            log.error(errorMsg, e);
        }
    }

    /**
     * Just delegates to {@link HttpRequest} for all methods.
     */
    static class DelegateHttpRequest implements HttpRequest {
        protected final HttpRequest request;

        DelegateHttpRequest(HttpRequest request) {
            this.request = request;
        }

        public HttpRequest setMethod(HttpMethod method) {
            this.request.setMethod(method);
            return this;
        }

        public HttpRequest setUri(String uri) {
            this.request.setUri(uri);
            return this;
        }

        public HttpMethod getMethod() {
            return this.request.getMethod();
        }

        @Override
        public HttpMethod method() {
            return request.method();
        }

        public String getUri() {
            return this.request.getUri();
        }

        @Override
        public String uri() {
            return request.uri();
        }


        public HttpVersion getProtocolVersion() {
            return this.request.getProtocolVersion();
        }

        @Override
        public HttpVersion protocolVersion() {
            return request.protocolVersion();
        }

        public HttpRequest setProtocolVersion(HttpVersion version) {
            this.request.setProtocolVersion(version);
            return this;
        }

        public HttpHeaders headers() {
            return this.request.headers();
        }

        public DecoderResult getDecoderResult() {
            return this.request.getDecoderResult();
        }

        @Override
        public DecoderResult decoderResult() {
            return request.decoderResult();
        }

        public void setDecoderResult(DecoderResult result) {
            this.request.setDecoderResult(result);
        }

        public String toString() {
            return this.getClass().getName() + "(" + this.request.toString() + ")";
        }
    }

    /**
     * Decorator around {@link StreamedHttpRequest} to adapt a publisher of {@link ByteBuffer} (i.e. {@link
     * software.amazon.awssdk.http.async.SdkHttpRequestProvider}) to a publisher of {@link HttpContent}.
     */
    private static class StreamedRequest extends DelegateHttpRequest implements StreamedHttpRequest {

        private final Publisher<ByteBuffer> publisher;
        private final Channel channel;

        StreamedRequest(HttpRequest request, Publisher<ByteBuffer> publisher, Channel channel) {
            super(request);
            this.publisher = publisher;
            this.channel = channel;
        }

        @Override
        public void subscribe(Subscriber<? super HttpContent> subscriber) {
            publisher.subscribe(new Subscriber<ByteBuffer>() {
                @Override
                public void onSubscribe(Subscription subscription) {
                    subscriber.onSubscribe(subscription);
                }

                @Override
                public void onNext(ByteBuffer byteBuffer) {
                    ByteBuf buffer = channel.alloc().buffer(byteBuffer.remaining());
                    buffer.writeBytes(byteBuffer);
                    HttpContent content = new DefaultHttpContent(buffer);
                    subscriber.onNext(content);
                }

                @Override
                public void onError(Throwable t) {
                    subscriber.onError(t);
                }

                @Override
                public void onComplete() {
                    subscriber.onComplete();
                }
            });
        }
    }
}
