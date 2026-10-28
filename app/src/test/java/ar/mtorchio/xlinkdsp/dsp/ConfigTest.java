package ar.mtorchio.xlinkdsp.dsp;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** El parser contra el volcado real capturado del PX 201. */
public class ConfigTest {

    private final Config config = Config.parse(MockTransport.CONFIG_DUMP);

    @Test
    public void completeOnlyWithClosingDollar() {
        assertTrue(Config.isComplete(MockTransport.CONFIG_DUMP));
        String partial = MockTransport.CONFIG_DUMP.substring(0, MockTransport.CONFIG_DUMP.length() / 2);
        assertFalse(Config.isComplete(partial));
    }

    @Test
    public void levels() {
        assertEquals(-20f, config.mainGain, 0f);
        assertArrayEquals(new float[]{0, 0, 5, -1, -4, -4}, config.channelGains, 0f);
        assertArrayEquals(new boolean[]{false, false, true, true, false, false}, config.channelMutes);
    }

    @Test
    public void channels() {
        assertArrayEquals(new int[]{2, 1, 2, 1, 0, 0}, config.routing);
        assertArrayEquals(new int[]{1, 1, 1, 1, 0, 0}, config.phase);
        assertArrayEquals(new int[]{0, 0, 0, 0, 0, 0}, config.delays);
    }

    @Test
    public void crossover() {
        assertArrayEquals(new int[]{80, 80, 90, 90, 35, 35}, config.hpfFreq);
        assertArrayEquals(new int[]{20000, 20000, 5000, 5000, 125, 125}, config.lpfFreq);
        assertEquals("12DBBTW", config.hpfSlope[0]);
        assertArrayEquals(new String[]{"OFF", "OFF", "12DBBES", "12DBBES", "24DBBTW", "24DBBTW"}, config.lpfSlope);
    }

    @Test
    public void limiter() {
        assertArrayEquals(new int[]{6, 6, -5, -5, -6, -6}, config.limThreshold);
        assertArrayEquals(new int[]{4, 6, 10, 10, 14, 14}, config.limAttack);
        assertArrayEquals(new int[]{60, 90, 160, 160, 220, 220}, config.limDecay);
    }

    @Test
    public void inputEq() {
        assertEquals(28, config.inputEq.size());
        assertEquals(31f, config.inputEq.get(0).frequency, 0f);
        assertEquals(2f, config.inputEq.get(1).gainDb, 0f);
        assertEquals(16000f, config.inputEq.get(27).frequency, 0f);
    }

    @Test
    public void channelEqWithCommaDecimals() {
        assertEquals(6, config.eqChannels.size());
        Config.Band band = config.eqChannels.get(2).get(0);
        assertEquals(100f, band.frequency, 0f);
        assertEquals(3.4f, band.q, 0.001f);
        assertEquals(-5f, band.gainDb, 0f);
        assertEquals(-12f, config.eqChannels.get(3).get(0).gainDb, 0f);
    }
}
