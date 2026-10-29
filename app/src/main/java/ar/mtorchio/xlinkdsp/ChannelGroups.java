/*
 * XLink DSP
 * Copyright (C) 2026 Maximiliano Torchio
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package ar.mtorchio.xlinkdsp;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

/**
 * Canales vinculados (0-based): lo que se edita en uno se aplica a todo su grupo.
 * Un canal está en un solo grupo y un grupo tiene al menos dos canales. Se guarda
 * como texto, "0,1;2,3,4".
 */
final class ChannelGroups {

    private final List<TreeSet<Integer>> groups = new ArrayList<>();

    static ChannelGroups decode(String encoded) {
        ChannelGroups result = new ChannelGroups();
        if (encoded == null || encoded.trim().isEmpty()) {
            return result;
        }
        for (String part : encoded.split(";")) {
            TreeSet<Integer> group = new TreeSet<>();
            for (String channel : part.split(",")) {
                try {
                    int ch = Integer.parseInt(channel.trim());
                    if (ch >= 0 && result.indexOf(ch) < 0) {
                        group.add(ch);
                    }
                } catch (NumberFormatException ignored) {
                    // Valor roto en prefs: se descarta ese canal.
                }
            }
            if (group.size() > 1) {
                result.groups.add(group);
            }
        }
        return result;
    }

    String encode() {
        StringBuilder out = new StringBuilder();
        for (TreeSet<Integer> group : groups) {
            if (out.length() > 0) {
                out.append(';');
            }
            boolean first = true;
            for (int ch : group) {
                if (!first) {
                    out.append(',');
                }
                out.append(ch);
                first = false;
            }
        }
        return out.toString();
    }

    /** Canales del grupo de channel, de menor a mayor (solo él si no tiene grupo). */
    int[] members(int channel) {
        int index = indexOf(channel);
        if (index < 0) {
            return new int[]{channel};
        }
        int[] result = new int[groups.get(index).size()];
        int i = 0;
        for (int ch : groups.get(index)) {
            result[i++] = ch;
        }
        return result;
    }

    /** El canal que representa al grupo (el menor). */
    int leader(int channel) {
        int index = indexOf(channel);
        return index < 0 ? channel : groups.get(index).first();
    }

    /** Suma targets al grupo de source; cada uno deja el grupo en el que estaba. */
    void link(int source, Collection<Integer> targets) {
        for (int target : targets) {
            if (target != source) {
                remove(target);
            }
        }
        int index = indexOf(source);
        TreeSet<Integer> group;
        if (index < 0) {
            group = new TreeSet<>();
            group.add(source);
            groups.add(group);
        } else {
            group = groups.get(index);
        }
        group.addAll(targets);
        if (group.size() < 2) {
            groups.remove(group);
        }
    }

    /** Saca al canal de su grupo; un grupo que queda con uno solo se desarma. */
    void remove(int channel) {
        int index = indexOf(channel);
        if (index < 0) {
            return;
        }
        TreeSet<Integer> group = groups.get(index);
        group.remove(channel);
        if (group.size() < 2) {
            groups.remove(index);
        }
    }

    void dissolve(int channel) {
        int index = indexOf(channel);
        if (index >= 0) {
            groups.remove(index);
        }
    }

    private int indexOf(int channel) {
        for (int i = 0; i < groups.size(); i++) {
            if (groups.get(i).contains(channel)) {
                return i;
            }
        }
        return -1;
    }
}
