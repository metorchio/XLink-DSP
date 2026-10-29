/*
 * XLink DSP
 * Copyright (C) 2026 Maximiliano Torchio
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package ar.mtorchio.xlinkdsp;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Collections;

public class ChannelGroupsTest {

    @Test
    public void loneChannelIsItsOwnGroup() {
        ChannelGroups groups = new ChannelGroups();
        assertArrayEquals(new int[]{3}, groups.members(3));
        assertEquals(3, groups.leader(3));
    }

    @Test
    public void linkJoinsAndSorts() {
        ChannelGroups groups = new ChannelGroups();
        groups.link(2, Collections.singletonList(0));
        assertArrayEquals(new int[]{0, 2}, groups.members(2));
        assertEquals(0, groups.leader(2));
    }

    @Test
    public void linkMovesTargetOutOfItsOldGroup() {
        ChannelGroups groups = ChannelGroups.decode("0,1;2,3");
        groups.link(0, Collections.singletonList(3));
        assertArrayEquals(new int[]{0, 1, 3}, groups.members(0));
        // El 2 quedó solo: ya no es un grupo.
        assertArrayEquals(new int[]{2}, groups.members(2));
        assertEquals("0,1,3", groups.encode());
    }

    @Test
    public void removeDissolvesPairs() {
        ChannelGroups groups = ChannelGroups.decode("0,1;2,3,4");
        groups.remove(1);
        groups.remove(4);
        assertEquals("2,3", groups.encode());
    }

    @Test
    public void dissolveRemovesWholeGroup() {
        ChannelGroups groups = ChannelGroups.decode("0,1;2,3,4");
        groups.dissolve(3);
        assertEquals("0,1", groups.encode());
    }

    @Test
    public void encodeDecodeRoundTripAndIgnoresJunk() {
        assertEquals("0,1;2,3,4", ChannelGroups.decode("0,1;2,3,4").encode());
        assertEquals("0,1", ChannelGroups.decode("0,x,1;5;1,2").encode());
        assertEquals("", ChannelGroups.decode(null).encode());
        assertArrayEquals(new int[]{4, 5}, ChannelGroups.decode(" 5 , 4 ").members(4));
    }
}
