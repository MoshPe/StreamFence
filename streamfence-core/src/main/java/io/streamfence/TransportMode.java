package io.streamfence;

/**
 * Network transport and security mode for the Socket.IO server.
 */
public enum TransportMode {

    /** Plain WebSocket (and HTTP long-polling) with no TLS. */
    WS,

    /**
     * WebSocket Secure: TLS is required. A {@link TLSConfig} must be provided via
     * {@link SocketIoServerBuilder#tls(TLSConfig)}.
     */
    WSS
}
