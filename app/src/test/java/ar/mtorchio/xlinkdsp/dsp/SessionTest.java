/*
 * XLink DSP
 * Copyright (C) 2026 Maximiliano Torchio
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package ar.mtorchio.xlinkdsp.dsp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** Session completa sobre MockTransport, con un reloj falso que avanza a mano. */
public class SessionTest {

    /** Scheduler falso: las tareas corren al avanzar el tiempo con advance(). */
    static final class FakeClock implements Session.Scheduler {
        private static final class Task {
            final Runnable runnable;
            final long at;

            Task(Runnable runnable, long at) {
                this.runnable = runnable;
                this.at = at;
            }
        }

        private final List<Task> tasks = new ArrayList<>();
        private long now;

        @Override
        public void schedule(Runnable task, long delayMs) {
            tasks.add(new Task(task, now + delayMs));
        }

        @Override
        public void cancel(Runnable task) {
            tasks.removeIf(t -> t.runnable == task);
        }

        void advance(long ms) {
            long end = now + ms;
            while (true) {
                Task next = null;
                for (Task task : tasks) {
                    if (task.at <= end && (next == null || task.at < next.at)) {
                        next = task;
                    }
                }
                if (next == null) {
                    break;
                }
                tasks.remove(next);
                now = next.at;
                next.runnable.run();
            }
            now = end;
        }
    }

    /** MockTransport que además anota todo lo que se escribe. */
    static final class RecordingTransport implements Transport {
        final MockTransport mock;
        final List<String> written = new ArrayList<>();
        boolean replyOk = true;

        RecordingTransport(Session.Scheduler scheduler) {
            mock = new MockTransport(scheduler);
        }

        @Override
        public void open(Listener listener) {
            mock.open(new Listener() {
                @Override
                public void onOpen() {
                    listener.onOpen();
                }

                @Override
                public void onWritten() {
                    listener.onWritten();
                }

                @Override
                public void onData(String chunk) {
                    if (replyOk || !chunk.endsWith("OK")) {
                        listener.onData(chunk);
                    }
                }

                @Override
                public void onClosed(String reason) {
                    listener.onClosed(reason);
                }
            });
        }

        @Override
        public boolean write(String line) {
            written.add(line);
            return mock.write(line);
        }

        @Override
        public void close() {
            mock.close();
        }
    }

    private FakeClock clock;
    private RecordingTransport transport;
    private Session session;
    private String model;
    private Config config;
    private Integer flushedMissed;
    private String closedReason;
    private MonitorReading monitor;

    @Before
    public void setUp() {
        clock = new FakeClock();
        transport = new RecordingTransport(clock);
        session = new Session(transport, "1234", clock, new Session.Listener() {
            @Override
            public void onReady(String m, String preset) {
                model = m;
            }

            @Override
            public void onConfig(Config c) {
                config = c;
            }

            @Override
            public void onClosed(String reason, boolean wasReady) {
                closedReason = reason;
            }

            @Override
            public void onFlushed(int missedAcks) {
                flushedMissed = missedAcks;
            }

            @Override
            public void onMonitor(MonitorReading reading) {
                monitor = reading;
            }
        });
    }

    private void connect() {
        session.connect();
        clock.advance(200);
    }

    @Test
    public void loginSequence() {
        connect();
        assertEquals("AT+LESEND=0\r\n", transport.written.get(0));
        assertEquals("[0][1234]\r\n", transport.written.get(1));
        assertEquals("4", model);
        assertTrue(session.isReady());
    }

    @Test
    public void readsConfig() {
        connect();
        session.readConfig();
        clock.advance(1000);
        assertEquals("a;\r\n", transport.written.get(2));
        assertNotNull(config);
        assertEquals(-20f, config.mainGain, 0f);
    }

    @Test
    public void sendsOneCommandPerOk() {
        connect();
        transport.written.clear();
        session.queue(Commands.channelGain(0, -3));
        session.queue(Commands.channelGain(1, -4));
        session.flush();
        assertEquals(1, transport.written.size());
        clock.advance(1000);
        assertEquals(2, transport.written.size());
        assertEquals(Integer.valueOf(0), flushedMissed);
        assertFalse(session.isSending());
    }

    @Test
    public void sameKeyKeepsOnlyTheLastValue() {
        connect();
        transport.written.clear();
        session.queue(Commands.channelGain(0, -3));
        session.queue(Commands.channelGain(0, -8));
        session.flush();
        clock.advance(1000);
        assertEquals(1, transport.written.size());
        assertEquals("[100][0][-8]\r\n", transport.written.get(0));
    }

    @Test
    public void heldCommandsWaitForRelease() {
        connect();
        transport.written.clear();
        session.hold(Commands.hpfFrequency(0, 100));
        session.flush();
        clock.advance(1000);
        assertTrue(transport.written.isEmpty());
        assertEquals(1, session.heldCount());
        session.releaseHeld();
        session.flush();
        clock.advance(1000);
        assertEquals("[2][0][0][100]\r\n", transport.written.get(0));
    }

    @Test
    public void sendNowSkipsPendingAndReplacesStaleValues() {
        connect();
        transport.written.clear();
        session.queue(Commands.channelGain(0, -3));
        session.queue(Commands.masterGain(-10));
        session.sendNow(Commands.masterGain(-20));
        session.sendNow(Commands.masterGain(-30));
        clock.advance(1000);
        // Sale el primero y el segundo pisa a los que esperaban; lo pendiente no sale.
        assertEquals(2, transport.written.size());
        assertEquals("[100][-1][-20]\r\n", transport.written.get(0));
        assertEquals("[100][-1][-30]\r\n", transport.written.get(1));
        assertEquals(1, session.pendingCount());
    }

    @Test
    public void monitorReadsVoltageAndLimiters() {
        connect();
        transport.written.clear();
        session.requestMonitor();
        clock.advance(1000);
        assertEquals("V;\r\n", transport.written.get(0));
        assertNotNull(monitor);
        assertEquals(12.6f, monitor.voltage, 0.001f);
        assertEquals(6, monitor.limiting.length);
    }

    @Test
    public void monitorParsesCommaDecimalsAndMissingList() {
        MonitorReading reading = MonitorReading.parse("AT+LESEND=182,[11,9][0!1!0!]");
        assertEquals(11.9f, reading.voltage, 0.001f);
        assertFalse(reading.limiting[0]);
        assertTrue(reading.limiting[1]);
        MonitorReading noList = MonitorReading.parse("[12,0][-]");
        assertEquals(0, noList.limiting.length);
        assertNull(MonitorReading.parse("AT+LESEND=182,[12,"));
    }

    @Test
    public void discardDropsQueuedAndHeld() {
        connect();
        transport.written.clear();
        session.queue(Commands.channelGain(0, -3));
        session.hold(Commands.hpfFrequency(0, 100));
        session.discard();
        assertEquals(0, session.pendingCount());
        assertEquals(0, session.heldCount());
        session.releaseHeld();
        session.flush();
        clock.advance(1000);
        assertTrue(transport.written.isEmpty());
    }

    @Test
    public void missingOkMovesOnAfterTimeout() {
        connect();
        transport.replyOk = false;
        transport.written.clear();
        session.queue(Commands.mute(0, true));
        session.queue(Commands.mute(1, true));
        session.flush();
        clock.advance(Session.ACK_TIMEOUT_MS - 1);
        assertEquals(1, transport.written.size());
        clock.advance(2 * Session.ACK_TIMEOUT_MS);
        assertEquals(2, transport.written.size());
        assertEquals(Integer.valueOf(2), flushedMissed);
    }

    @Test
    public void wrongPasswordCloses() {
        Session bad = new Session(new Transport() {
            Listener listener;

            @Override
            public void open(Listener l) {
                listener = l;
                l.onOpen();
            }

            @Override
            public boolean write(String line) {
                clock.schedule(listener::onWritten, 10);
                if (line.startsWith("[0]")) {
                    clock.schedule(() -> listener.onData("AT+LESEND=182,[24]ER"), 20);
                }
                return true;
            }

            @Override
            public void close() {
            }
        }, "0000", clock, new Session.Listener() {
            @Override
            public void onReady(String m, String preset) {
            }

            @Override
            public void onConfig(Config c) {
            }

            @Override
            public void onClosed(String reason, boolean wasReady) {
                closedReason = reason;
                assertFalse(wasReady);
            }
        });
        bad.connect();
        clock.advance(100);
        assertEquals("Password inválida", closedReason);
        assertFalse(bad.isReady());
    }

    @Test
    public void closeIsSilent() {
        connect();
        session.close();
        clock.advance(1000);
        assertNull(closedReason);
        assertFalse(session.isReady());
    }

}
