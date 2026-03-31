package engine.app.pipeline;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.BusySpinWaitStrategy;
import com.lmax.disruptor.PhasedBackoffWaitStrategy;
import com.lmax.disruptor.WaitStrategy;
import com.lmax.disruptor.YieldingWaitStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DisruptorWaitStrategyTest {

    private static final String PROP = "disruptor.wait.strategy";

    @AfterEach
    void restoreProperty() {
        System.clearProperty(PROP);
    }

    @Test
    void phasedExplicit() {
        System.setProperty(PROP, "phased");
        assertInstanceOf(PhasedBackoffWaitStrategy.class, DisruptorPipeline.createWaitStrategy());
    }

    @Test
    void blocking() {
        System.setProperty(PROP, "blocking");
        assertInstanceOf(BlockingWaitStrategy.class, DisruptorPipeline.createWaitStrategy());
    }

    @Test
    void yielding() {
        System.setProperty(PROP, "yielding");
        assertInstanceOf(YieldingWaitStrategy.class, DisruptorPipeline.createWaitStrategy());
    }

    @Test
    void busySpinAliases() {
        System.setProperty(PROP, "busy-spin");
        assertInstanceOf(BusySpinWaitStrategy.class, DisruptorPipeline.createWaitStrategy());
    }

    @Test
    void unknownThrows() {
        System.setProperty(PROP, "nope");
        assertThrows(IllegalArgumentException.class, DisruptorPipeline::createWaitStrategy);
    }

    /**
     * Default uses env only when the property is unset; skip if env would override.
     */
    @Test
    @DisabledIfEnvironmentVariable(named = "DISRUPTOR_WAIT_STRATEGY", matches = ".+")
    void defaultIsPhasedWhenEnvUnset() {
        WaitStrategy s = DisruptorPipeline.createWaitStrategy();
        assertInstanceOf(PhasedBackoffWaitStrategy.class, s);
    }
}

