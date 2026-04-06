package io.streamfence;

public interface TokenValidator {

    AuthDecision validate(String token, String namespace, String topic);
}
