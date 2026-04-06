package io.streamfence.demo.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.streamfence.ServerEventListener;
import io.streamfence.demo.runtime.DemoEvent;
import io.streamfence.demo.runtime.DemoEventParser;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class DemoServerMainTest {

    @Test
    void serverStartedEventIsWrittenAsStructuredJson() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        DemoServerListener listener = new DemoServerListener(new PrintStream(output, true));

        listener.onServerStarted(new ServerEventListener.ServerStartedEvent("0.0.0.0", 9092, 9093));

        DemoEvent event = DemoEventParser.parse(output.toString().trim());

        assertThat(event.category()).isEqualTo("server_started");
        assertThat(event.processType()).isEqualTo("server");
        assertThat(event.summary()).contains("started");
    }

    @Test
    void invalidOverridePortsAreRejected() throws Exception {
        Method resolveServerPort = DemoServerMain.class.getDeclaredMethod("resolveServerPort", String[].class);
        resolveServerPort.setAccessible(true);
        Method resolveManagementPort = DemoServerMain.class.getDeclaredMethod("resolveManagementPort", String[].class);
        resolveManagementPort.setAccessible(true);

        assertThatThrownBy(() -> invokeInt(resolveServerPort, new String[]{"--server-port=70000"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("serverPort");

        assertThatThrownBy(() -> invokeInt(resolveManagementPort, new String[]{"--management-port=70000"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("managementPort");
    }

    @Test
    void presetOverrideIsResolvedAndValidated() throws Exception {
        Method resolvePresetId = DemoServerMain.class.getDeclaredMethod("resolvePresetId", String[].class);
        resolvePresetId.setAccessible(true);

        assertThat(invokeString(resolvePresetId, new String[]{"--preset=pressure"})).isEqualTo("pressure");
        assertThat(invokeString(resolvePresetId, new String[]{})).isEqualTo("throughput");
        assertThatThrownBy(() -> invokeString(resolvePresetId, new String[]{"--preset=unknown"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown demo preset");
    }

    private static int invokeInt(Method method, String[] args) throws InvocationTargetException, IllegalAccessException {
        try {
            return (int) method.invoke(null, (Object) args);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw exception;
        }
    }

    private static String invokeString(Method method, String[] args) throws InvocationTargetException, IllegalAccessException {
        try {
            return (String) method.invoke(null, (Object) args);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw exception;
        }
    }
}
