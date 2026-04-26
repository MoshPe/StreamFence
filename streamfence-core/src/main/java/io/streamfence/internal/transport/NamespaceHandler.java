package io.streamfence.internal.transport;

import com.corundumstudio.socketio.AckRequest;
import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIONamespace;
import com.corundumstudio.socketio.Transport;
import io.streamfence.AuthDecision;
import io.streamfence.internal.security.AuthRateLimiter;
import io.streamfence.TokenValidator;
import io.streamfence.AuthMode;
import io.streamfence.internal.config.NamespaceConfig;
import io.streamfence.internal.config.ServerConfig;
import io.streamfence.internal.config.TopicPolicy;
import io.streamfence.internal.delivery.ClientSessionRegistry;
import io.streamfence.internal.delivery.ClientSessionState;
import io.streamfence.internal.delivery.TopicDispatcher;
import io.streamfence.internal.observability.ServerEventPublisher;
import io.streamfence.ServerMetrics;
import io.streamfence.internal.protocol.AckPayload;
import io.streamfence.internal.protocol.ErrorPayload;
import io.streamfence.internal.protocol.PublishRequest;
import io.streamfence.internal.protocol.SubscriptionRequest;
import io.streamfence.internal.delivery.TopicRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class NamespaceHandler {

    private static final Logger LOG = LoggerFactory.getLogger(NamespaceHandler.class);

    private final String namespace;
    private final NamespaceConfig namespaceConfig;
    private final ServerConfig serverConfig;
    private final TopicRegistry topicRegistry;
    private final ClientSessionRegistry sessionRegistry;
    private final TopicDispatcher topicDispatcher;
    private final TokenValidator tokenValidator;
    private final ServerMetrics metrics;
    private final AuthRateLimiter authRateLimiter;
    private final ServerEventPublisher eventPublisher;

    public NamespaceHandler(
            String namespace,
            NamespaceConfig namespaceConfig,
            ServerConfig serverConfig,
            TopicRegistry topicRegistry,
            ClientSessionRegistry sessionRegistry,
            TopicDispatcher topicDispatcher,
            TokenValidator tokenValidator,
            ServerMetrics metrics,
            AuthRateLimiter authRateLimiter
    ) {
        this(namespace, namespaceConfig, serverConfig, topicRegistry, sessionRegistry, topicDispatcher,
                tokenValidator, metrics, authRateLimiter, ServerEventPublisher.noOp());
    }

    public NamespaceHandler(
            String namespace,
            NamespaceConfig namespaceConfig,
            ServerConfig serverConfig,
            TopicRegistry topicRegistry,
            ClientSessionRegistry sessionRegistry,
            TopicDispatcher topicDispatcher,
            TokenValidator tokenValidator,
            ServerMetrics metrics,
            AuthRateLimiter authRateLimiter,
            ServerEventPublisher eventPublisher
    ) {
        this.namespace = namespace;
        this.namespaceConfig = namespaceConfig;
        this.serverConfig = serverConfig;
        this.topicRegistry = topicRegistry;
        this.sessionRegistry = sessionRegistry;
        this.topicDispatcher = topicDispatcher;
        this.tokenValidator = tokenValidator;
        this.metrics = metrics;
        this.authRateLimiter = authRateLimiter;
        this.eventPublisher = eventPublisher == null ? ServerEventPublisher.noOp() : eventPublisher;
    }

    public void register(SocketIONamespace socketNamespace) {
        socketNamespace.addConnectListener(this::onConnect);
        socketNamespace.addDisconnectListener(this::onDisconnect);
        socketNamespace.addEventListener("subscribe", SubscriptionRequest.class, this::onSubscribe);
        socketNamespace.addEventListener("unsubscribe", SubscriptionRequest.class, this::onUnsubscribe);
        socketNamespace.addEventListener("publish", PublishRequest.class, this::onPublish);
        socketNamespace.addEventListener("ack", AckPayload.class, this::onAck);
    }

    private void onConnect(SocketIOClient client) {
        String remote = remoteAddressOf(client);
        long now = System.currentTimeMillis();
        if (authRateLimiter != null && !authRateLimiter.allow(remote, now)) {
            metrics.recordAuthRateLimited(namespace);
            eventPublisher.authRejected(namespace, client.getSessionId().toString(), remote, "RATE_LIMITED");
            client.disconnect();
            return;
        }

        AuthDecision decision = authorize(client, null, namespaceConfig.authRequired());
        if (!decision.accepted()) {
            if (authRateLimiter != null && authRateLimiter.recordReject(remote, now)) {
                LOG.warn("event=auth_reject namespace={} clientId={} remote={} reason={}",
                        namespace, client.getSessionId(), remote, decision.reason());
            }
            metrics.recordAuthRejected(namespace);
            eventPublisher.authRejected(namespace, client.getSessionId().toString(), remote, decision.reason());
            client.sendEvent("error", new ErrorPayload("AUTH_REJECTED", decision.reason()));
            client.disconnect();
            return;
        }

        ClientSessionState sessionState = new ClientSessionState(
                client.getSessionId().toString(),
                namespace,
                client
        );
        sessionRegistry.register(sessionState);
        metrics.recordConnect(namespace);
        eventPublisher.clientConnected(namespace, client.getSessionId().toString(), String.valueOf(client.getTransport()), decision.principal());
        LOG.info("event=connect namespace={} clientId={} transport={}",
                namespace, client.getSessionId(), client.getTransport());
    }

    private void onDisconnect(SocketIOClient client) {
        String clientId = client.getSessionId().toString();
        // Fast: remove from ACK tracker synchronously. Async: lane/spill cleanup off Netty thread.
        topicDispatcher.onClientDisconnectedAsync(clientId);
        metrics.recordDisconnect(namespace);
        eventPublisher.clientDisconnected(namespace, clientId);
        LOG.info("event=disconnect namespace={} clientId={}", namespace, client.getSessionId());
    }

    private void onSubscribe(SocketIOClient client, SubscriptionRequest request, AckRequest ackRequest) {
        ClientSessionState sessionState = requireSession(client);
        Optional<TopicPolicy> topicPolicy = topicRegistry.find(namespace, request.topic());
        if (topicPolicy.isEmpty()) {
            client.sendEvent("error", new ErrorPayload("UNKNOWN_TOPIC", "Unknown topic: " + request.topic()));
            return;
        }

        AuthDecision decision = authorize(client, request.token(), topicPolicy.get().authRequired());
        if (!decision.accepted()) {
            eventPublisher.authRejected(namespace, client.getSessionId().toString(), remoteAddressOf(client), decision.reason());
            client.sendEvent("error", new ErrorPayload("AUTH_REJECTED", decision.reason()));
            return;
        }
        if (!topicPolicy.get().allowPolling() && client.getTransport() == Transport.POLLING) {
            client.sendEvent("error", new ErrorPayload("TRANSPORT_REJECTED", "Topic requires websocket-capable transport"));
            return;
        }

        Path spillRoot = serverConfig.spillRootPath() != null
                ? Path.of(serverConfig.spillRootPath())
                : null;
        sessionRegistry.subscribe(sessionState, request.topic(), topicPolicy.get(), spillRoot);
        eventPublisher.subscribed(namespace, client.getSessionId().toString(), request.topic());
        client.sendEvent("subscribed", request);
    }

    private void onUnsubscribe(SocketIOClient client, SubscriptionRequest request, AckRequest ackRequest) {
        requireSession(client);
        topicDispatcher.onClientUnsubscribed(client.getSessionId().toString(), namespace, request.topic());
        eventPublisher.unsubscribed(namespace, client.getSessionId().toString(), request.topic());
        client.sendEvent("unsubscribed", request);
    }

    private void onPublish(SocketIOClient client, PublishRequest request, AckRequest ackRequest) {
        Optional<TopicPolicy> topicPolicy = topicRegistry.find(namespace, request.topic());
        if (topicPolicy.isEmpty()) {
            rejectPublish(client, request.topic(), "UNKNOWN_TOPIC", "Unknown topic: " + request.topic());
            return;
        }

        AuthDecision decision = authorize(client, request.token(), topicPolicy.get().authRequired());
        if (!decision.accepted()) {
            eventPublisher.authRejected(namespace, client.getSessionId().toString(), remoteAddressOf(client), decision.reason());
            rejectPublish(client, request.topic(), "AUTH_REJECTED", decision.reason());
            return;
        }

        eventPublisher.publishAccepted(namespace, client.getSessionId().toString(), request.topic());
        metrics.recordReceived(namespace, request.topic(), estimateBytes(request.payload()));
        topicDispatcher.publish(namespace, request.topic(), request.payload());
    }

    private void onAck(SocketIOClient client, AckPayload ackPayload, AckRequest ackRequest) {
        topicDispatcher.acknowledge(client.getSessionId().toString(), namespace, ackPayload.topic(), ackPayload.messageId());
    }

    private void rejectPublish(SocketIOClient client, String topic, String code, String reason) {
        eventPublisher.publishRejected(namespace, client.getSessionId().toString(), topic, code, reason);
        client.sendEvent("error", new ErrorPayload(code, reason));
    }

    private static String remoteAddressOf(SocketIOClient client) {
        Object remote = client.getRemoteAddress();
        return remote == null ? "unknown" : remote.toString();
    }

    private static long estimateBytes(Object payload) {
        if (payload == null) {
            return 1L;
        }
        return Math.max(1L, String.valueOf(payload).getBytes(StandardCharsets.UTF_8).length);
    }

    private ClientSessionState requireSession(SocketIOClient client) {
        ClientSessionState sessionState = sessionRegistry.get(client.getSessionId().toString());
        if (sessionState == null) {
            throw new IllegalStateException("Missing session for client " + client.getSessionId());
        }
        return sessionState;
    }

    private AuthDecision authorize(SocketIOClient client, String suppliedToken, boolean authRequired) {
        if (!authRequired) {
            return AuthDecision.accept("anonymous");
        }
        if (serverConfig.authMode() != AuthMode.TOKEN || tokenValidator == null) {
            return AuthDecision.reject("Token auth is disabled");
        }
        String token = suppliedToken;
        if (token == null || token.isBlank()) {
            token = client.getHandshakeData().getSingleUrlParam("token");
        }
        return tokenValidator.validate(token, namespace, null);
    }
}
