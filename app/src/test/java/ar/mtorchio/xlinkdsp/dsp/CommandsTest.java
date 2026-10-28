package ar.mtorchio.xlinkdsp.dsp;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Strings de referencia: lo que mandaba la app antes de mover el protocolo a
 * Commands. Si alguno cambia, el DSP recibe otra cosa: no tocar sin probar con el equipo.
 */
public class CommandsTest {

    @Test
    public void levels() {
        assertEquals("[100][-1][-20]", Commands.masterGain(-20).text);
        assertEquals("[100][2][-4]", Commands.channelGain(2, -4).text);
        assertEquals("[100][0][9]", Commands.channelGain(0, 9).text);
        assertEquals("[999][3][1]", Commands.mute(3, true).text);
        assertEquals("[999][3][0]", Commands.mute(3, false).text);
    }

    @Test
    public void channels() {
        assertEquals("[3][0][2]", Commands.routing(0, Commands.ROUTE_L).text);
        assertEquals("[3][1][1]", Commands.routing(1, Commands.ROUTE_R).text);
        assertEquals("[3][4][0]", Commands.routing(4, Commands.ROUTE_LR).text);
        assertEquals("[6][2][1]", Commands.phase(2, true).text);
        assertEquals("[6][2][0]", Commands.phase(2, false).text);
        assertEquals("[7][5][1250]", Commands.delay(5, 1250).text);
    }

    @Test
    public void crossover() {
        assertEquals("[2][0][0][80]", Commands.hpfFrequency(0, 80).text);
        assertEquals("[2][0][1][12DBBTW]", Commands.hpfSlope(0, Slope.BW12).text);
        assertEquals("[2][4][2][125]", Commands.lpfFrequency(4, 125).text);
        assertEquals("[2][4][3][24DBLKR]", Commands.lpfSlope(4, Slope.LR24).text);
        assertEquals("[2][1][1][OFF]", Commands.hpfSlope(1, Slope.OFF).text);
    }

    @Test
    public void limiter() {
        assertEquals("[113][4][-6]", Commands.limiterThreshold(4, -6, true).text);
        assertEquals("[103][4][-6]", Commands.limiterThreshold(4, -6, false).text);
        assertEquals("[102][1][14]", Commands.limiterAttack(1, 14).text);
        assertEquals("[104][1][220]", Commands.limiterDecay(1, 220).text);
    }

    @Test
    public void inputEqCountsBandsFromZero() {
        assertEquals("[202][-1][F0][3]", Commands.inputEqGain(0, 3f).text);
        assertEquals("[202][-1][F27][-12]", Commands.inputEqGain(27, -12f).text);
    }

    @Test
    public void channelEqCountsBandsFromOne() {
        assertEquals("[202][2][F1][-5]", Commands.eqChannelGain(2, 0, -5f).text);
        assertEquals("[203][2][F2][250]", Commands.eqChannelFrequency(2, 1, 250f).text);
        assertEquals("[201][2][F3][5.0]", Commands.eqChannelQ(2, 2, 5f).text);
        assertEquals("[201][0][F1][3.4]", Commands.eqChannelQ(0, 0, 3.4f).text);
    }

    @Test
    public void rounding() {
        assertEquals("[202][-1][F5][3]", Commands.inputEqGain(5, 2.6f).text);
        assertEquals("[203][0][F1][1000]", Commands.eqChannelFrequency(0, 0, 999.7f).text);
    }

    @Test
    public void clampsToXairRanges() {
        assertEquals("[100][-1][0]", Commands.masterGain(5).text);
        assertEquals("[100][0][-33]", Commands.channelGain(0, -40).text);
        assertEquals("[202][-1][F0][12]", Commands.inputEqGain(0, 15f).text);
        assertEquals("[202][0][F1][18]", Commands.eqChannelGain(0, 0, 25f).text);
        assertEquals("[201][0][F1][9.9]", Commands.eqChannelQ(0, 0, 20f).text);
        assertEquals("[7][0][6500]", Commands.delay(0, 9000).text);
    }

    @Test
    public void keysMatchTheSetting() {
        assertEquals("GAIN-1", Commands.masterGain(0).key);
        assertEquals("GAIN2", Commands.channelGain(2, 0).key);
        assertEquals("G-1:5", Commands.inputEqGain(5, 0).key);
        assertEquals("F2:1", Commands.eqChannelFrequency(2, 1, 100).key);
        assertEquals("XO3:1", Commands.hpfSlope(3, Slope.OFF).key);
        assertEquals("LIMT0", Commands.limiterThreshold(0, 0, true).key);
    }

    @Test
    public void slopeCodes() {
        assertEquals(Slope.BS12, Slope.fromCode("12DBBES"));
        assertEquals(null, Slope.fromCode("99XYZ"));
    }
}
