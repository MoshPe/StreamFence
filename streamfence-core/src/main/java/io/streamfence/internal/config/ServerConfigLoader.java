package io.streamfence.internal.config;

import io.streamfence.AuthMode;
import io.streamfence.DeliveryMode;
import io.streamfence.OverflowAction;
import io.streamfence.SocketIoServerSpec;
import io.streamfence.TransportMode;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ServerConfigLoader {

    public static final int MIN_SUPPORTED_PAYLOAD_BYTES = 4_718_592;
    private static final List<String> REQUIRED_NAMESPACES = List.of("/reliable", "/non-reliable", "/bulk");

    private ServerConfigLoader() {
    }

    public static ServerConfig load(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        ObjectMapper mapper = objectMapperFor(path);
        // Parse to a tree so we can expand multi-topic policy definitions
        // into per-topic TopicPolicy instances before binding to ServerConfig.
        // The runtime representation stays single-topic; only the YAML input
        // shape is polymorphic.
        JsonNode root = mapper.readTree(Files.readAllBytes(path));
        if (root instanceof ObjectNode rootObject) {
            expandTopicPolicies(mapper, rootObject);
        }
        ServerConfig config = mapper.treeToValue(root, ServerConfig.class);
        validate(config);
        return config;
    }

    public static SocketIoServerSpec loadSpec(Path path) throws IOException {
        return SocketIoServerSpecMapper.fromServerConfig(load(path));
    }

    private static void expandTopicPolicies(ObjectMapper mapper, ObjectNode root) {
        JsonNode definitionsNode = root.get("topicPolicies");
        if (definitionsNode == null || !definitionsNode.isArray()) {
            return;
        }
        List<TopicPolicyDefinition> definitions = mapper.convertValue(
                definitionsNode,
                mapper.getTypeFactory().constructCollectionType(List.class, TopicPolicyDefinition.class));
        List<TopicPolicy> expanded = definitions.stream()
                .flatMap(TopicPolicyDefinition::expand)
                .toList();
        root.set("topicPolicies", mapper.valueToTree(expanded));
    }

    private static ObjectMapper objectMapperFor(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        ObjectMapper mapper = name.endsWith(".json") ? new ObjectMapper() : new ObjectMapper(new YAMLFactory());
        mapper.registerModule(new JavaTimeModule());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }

    private static void validate(ServerConfig config) {
        require(config != null, "Server config is required");
        require(notBlank(config.host()), "Host is required");
        require(config.port() > 0, "Port must be positive");
        require(config.transportMode() != null, "Transport mode is required");
        require(config.pingIntervalMs() > 0, "Ping interval must be positive");
        require(config.pingTimeoutMs() > 0, "Ping timeout must be positive");
        require(config.maxFramePayloadLength() >= MIN_SUPPORTED_PAYLOAD_BYTES,
                "Frame payload limit must support at least 4.5 MB payloads");
        require(config.maxHttpContentLength() >= MIN_SUPPORTED_PAYLOAD_BYTES,
                "HTTP content limit must support at least 4.5 MB payloads");
        require(config.authMode() != null, "Auth mode is required");
        require(config.origin() == null || notBlank(config.origin()), "Origin must not be blank when provided");
        require(config.namespaces() != null && !config.namespaces().isEmpty(), "Namespaces are required");
        require(config.topicPolicies() != null && !config.topicPolicies().isEmpty(), "At least one topic policy is required");

        require(config.managementPort() >= 0 && config.managementPort() <= 65535,
                "managementPort must be between 0 and 65535");
        require(config.senderThreads() >= 0, "senderThreads must be zero or positive");
        require(config.shutdownDrainMs() > 0, "shutdownDrainMs must be positive");

        validateTransport(config);
        validateCors(config);

        for (String namespace : REQUIRED_NAMESPACES) {
            require(config.namespaces().containsKey(namespace), "Missing required namespace: " + namespace);
        }

        if (config.authMode() == AuthMode.NONE) {
            boolean authConfigured = config.namespaces().values().stream().anyMatch(NamespaceConfig::authRequired)
                    || config.topicPolicies().stream().anyMatch(TopicPolicy::authRequired);
            require(!authConfigured, "Auth cannot be required when authMode is NONE");
        } else {
            require(config.staticTokens() != null && !config.staticTokens().isEmpty(),
                    "Static tokens are required when authMode is TOKEN");
            require(config.authRejectWindowMs() > 0, "authRejectWindowMs must be positive when auth is enabled");
            require(config.authRejectMaxPerWindow() > 0, "authRejectMaxPerWindow must be positive when auth is enabled");
        }

        validateNamespaces(config.namespaces());
        validateTopics(config.namespaces(), config.topicPolicies());
    }

    private static void validateCors(ServerConfig config) {
        if (!config.enableCors()) {
            return;
        }
        require(notBlank(config.origin()), "origin is required when enableCors is true");
    }

    private static void validateTransport(ServerConfig config) {
        if (config.transportMode() == TransportMode.WS) {
            return;
        }

        require(config.tls() != null, "PEM TLS config is required when transportMode is WSS");
        require(notBlank(config.tls().certChainPemPath()), "PEM certChainPemPath is required for WSS");
        require(notBlank(config.tls().privateKeyPemPath()), "PEM privateKeyPemPath is required for WSS");
        require(notBlank(config.tls().keyStorePassword()), "TLS keyStorePassword is required for WSS");
        require(Files.exists(Path.of(config.tls().certChainPemPath())), "PEM certificate file does not exist");
        require(Files.exists(Path.of(config.tls().privateKeyPemPath())), "PEM private key file does not exist");
        String protocol = config.tls().protocol();
        require(protocol.equals("TLSv1.2") || protocol.equals("TLSv1.3") || protocol.equals("TLS"),
                "TLS protocol must be one of TLSv1.2, TLSv1.3, TLS");
    }

    private static void validateNamespaces(Map<String, NamespaceConfig> namespaces) {
        namespaces.forEach((name, namespaceConfig) -> {
            require(name.startsWith("/"), "Namespace must start with '/': " + name);
            require(namespaceConfig != null, "Namespace config is required for " + name);
        });
    }

    private static void validateTopics(Map<String, NamespaceConfig> namespaces, List<TopicPolicy> topicPolicies) {
        Set<String> uniqueKeys = new HashSet<>();
        for (TopicPolicy topicPolicy : topicPolicies) {
            require(topicPolicy != null, "Topic policy entry is required");
            require(notBlank(topicPolicy.namespace()), "Topic policy namespace is required");
            require(notBlank(topicPolicy.topic()), "Topic name is required");
            require(topicPolicy.deliveryMode() != null, "Delivery mode is required for topic " + topicPolicy.topic());
            require(topicPolicy.overflowAction() != null, "Overflow action is required for topic " + topicPolicy.topic());
            require(topicPolicy.maxQueuedMessagesPerClient() > 0,
                    "maxQueuedMessagesPerClient must be positive for topic " + topicPolicy.topic());
            require(topicPolicy.maxQueuedBytesPerClient() > 0,
                    "maxQueuedBytesPerClient must be positive for topic " + topicPolicy.topic());
            require(topicPolicy.ackTimeoutMs() > 0, "ackTimeoutMs must be positive for topic " + topicPolicy.topic());
            require(topicPolicy.maxRetries() >= 0, "maxRetries must be zero or positive for topic " + topicPolicy.topic());
            require(namespaces.containsKey(topicPolicy.namespace()),
                    "Unknown namespace for topic policy: " + topicPolicy.namespace());

            if (topicPolicy.deliveryMode() == DeliveryMode.AT_LEAST_ONCE) {
                require(topicPolicy.overflowAction() == OverflowAction.REJECT_NEW,
                        "AT_LEAST_ONCE topics must use REJECT_NEW overflowAction");
                require(!topicPolicy.coalesce(), "AT_LEAST_ONCE topics cannot enable coalescing");
                require(topicPolicy.maxRetries() > 0, "AT_LEAST_ONCE topics must allow at least one retry");
                // The in-flight window cannot exceed the queue capacity or the
                // drain loop will never reach maxInFlight, silently capping
                // reliable throughput at 1 message per ackTimeout.
                require(topicPolicy.maxInFlight() <= topicPolicy.maxQueuedMessagesPerClient(),
                        "maxInFlight must not exceed maxQueuedMessagesPerClient for topic " + topicPolicy.topic());
            }

            String uniqueKey = topicPolicy.namespace() + "|" + topicPolicy.topic();
            require(uniqueKeys.add(uniqueKey), "Duplicate topic policy for " + uniqueKey);
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static void require(boolean expression, String message) {
        if (!expression) {
            throw new IllegalArgumentException(message);
        }
    }
}


