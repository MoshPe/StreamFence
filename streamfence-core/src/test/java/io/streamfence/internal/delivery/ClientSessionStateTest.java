package io.streamfence.internal.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import io.streamfence.DeliveryMode;
import io.streamfence.OverflowAction;
import io.streamfence.internal.config.TopicPolicy;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ClientSessionState}.
 *
 * {@code SocketIOClient} is not involved in any of the state-management paths
 * exercised here, so {@code null} is passed as the client argument.
 */
class ClientSessionStateTest {

    private static final TopicPolicy POLICY = new TopicPolicy(
            "/ns", "prices",
            DeliveryMode.BEST_EFFORT, OverflowAction.REJECT_NEW,
            8, 4096, 1000, 0, false, true, false, 1);

    @Test
    void subscribeAddsTopicAndIsSubscribedReturnsTrue() {
        ClientSessionState state = newState();

        state.subscribe("prices", POLICY);

        assertThat(state.isSubscribed("prices")).isTrue();
        assertThat(state.subscriptions()).containsExactly("prices");
    }

    @Test
    void subscribeWithSpillRootCreatesLane() {
        ClientSessionState state = newState();

        state.subscribe("prices", POLICY, null);

        assertThat(state.isSubscribed("prices")).isTrue();
        assertThat(state.lane("prices")).isNotNull();
    }

    @Test
    void unsubscribeRemovesTopicAndClosesLane() {
        ClientSessionState state = newState();
        state.subscribe("prices", POLICY);
        assertThat(state.lane("prices")).isNotNull();

        state.unsubscribe("prices");

        assertThat(state.isSubscribed("prices")).isFalse();
        assertThat(state.lane("prices")).isNull();
    }

    @Test
    void unsubscribeNonexistentTopicDoesNotThrow() {
        ClientSessionState state = newState();

        // Must not throw even when the topic was never subscribed
        state.unsubscribe("ghost-topic");

        assertThat(state.subscriptions()).isEmpty();
    }

    @Test
    void laneReturnsNullForUnsubscribedTopic() {
        ClientSessionState state = newState();

        assertThat(state.lane("unknown")).isNull();
    }

    @Test
    void laneWithPolicyCreatesLaneIfAbsent() {
        ClientSessionState state = newState();

        ClientLane lane = state.lane("prices", POLICY);

        assertThat(lane).isNotNull();
        assertThat(lane.topicPolicy()).isEqualTo(POLICY);
    }

    @Test
    void laneWithPolicyAndSpillRootCreatesLane() {
        ClientSessionState state = newState();

        ClientLane lane = state.lane("prices", POLICY, null);

        assertThat(lane).isNotNull();
    }

    @Test
    void closeAllLanesClosesEverySubscribedLane() {
        ClientSessionState state = newState();
        state.subscribe("prices", POLICY);
        state.subscribe("alerts", alertPolicy());

        state.closeAllLanes();

        // After closing, the lane map is cleared — lane() must return null
        assertThat(state.lane("prices")).isNull();
        assertThat(state.lane("alerts")).isNull();
    }

    @Test
    void startDrainReturnsTrueOnFirstCallAndFalseOnSecond() {
        ClientSessionState state = newState();
        state.subscribe("prices", POLICY);

        assertThat(state.startDrain("prices")).isTrue();
        assertThat(state.startDrain("prices")).isFalse();
    }

    @Test
    void finishDrainAllowsStartDrainAgain() {
        ClientSessionState state = newState();
        state.subscribe("prices", POLICY);

        state.startDrain("prices");
        state.finishDrain("prices");

        assertThat(state.startDrain("prices")).isTrue();
    }

    @Test
    void finishDrainOnRemovedTopicDoesNotThrow() {
        ClientSessionState state = newState();
        // Never started a drain for this topic — must not throw
        state.finishDrain("prices");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static ClientSessionState newState() {
        // SocketIOClient is not used by any method exercised in this test class
        return new ClientSessionState("test-client-id", "/ns", null);
    }

    private static TopicPolicy alertPolicy() {
        return new TopicPolicy(
                "/ns", "alerts",
                DeliveryMode.AT_LEAST_ONCE, OverflowAction.REJECT_NEW,
                16, 8192, 2000, 3, false, true, false, 1);
    }
}
