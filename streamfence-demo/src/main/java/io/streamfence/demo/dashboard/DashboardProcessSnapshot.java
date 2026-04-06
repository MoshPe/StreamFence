package io.streamfence.demo.dashboard;

public record DashboardProcessSnapshot(
        String name,
        String role,
        boolean ready,
        boolean alive
) {
}
