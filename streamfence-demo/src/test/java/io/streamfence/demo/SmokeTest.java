package io.streamfence.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class SmokeTest {

    @Test
    void mainEntryPointExists() throws Exception {
        Class<?> mainClass = Class.forName("io.streamfence.demo.Main");
        Method mainMethod = mainClass.getMethod("main", String[].class);

        assertThat(mainMethod).isNotNull();
        assertThat(Class.forName("io.streamfence.demo.DemoLauncherMain")).isNotNull();
        assertThat(Class.forName("io.streamfence.demo.DemoServerMain")).isNotNull();
        assertThat(Class.forName("io.streamfence.demo.DemoClientMain")).isNotNull();
    }
}
