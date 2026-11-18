/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.surface;

import ai.kumbuka.worklist.CounterProbe;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The checks the gates of the target cut run over an answer, held apart from
 * the calls that produce the answers so that each check is observed reporting
 * its offences on every build ({@code AnswerChecksTest}) without a running
 * service.
 *
 * <p>Every expectation is written here or handed in by the gate, from the
 * service contract (C6.1, C8.7) and the target cut of the work surface; nothing
 * is read from the production packages. Each check answers a list of
 * offences, empty where the answer holds.
 */
@CounterProbe
public final class AnswerChecks {

    /**
     * The members a lease put on an answer: the receipt, the holder state, the
     * lease's two instants and its length. None of them may stand anywhere in
     * an answer of the target cut, at any depth.
     */
    public static final Set<String> LEASE_MEMBERS = Set.of(
        "receipt", "holder", "actor", "granted_at", "expires_at", "duration_seconds",
        "lease", "claimed_by");

    /** The members a step of {@code next} carries, and nothing else (C6.1). */
    private static final Set<String> STEP = Set.of("call", "does");

    private AnswerChecks() {
    }

    /**
     * Every member of a lease, wherever it stands in a parsed answer: a key of
     * an object at any depth, named by its path.
     *
     * @param where what the answer was, for the offence's text
     */
    public static List<String> leaseOffences(String where, Object answer) {
        List<String> offences = new ArrayList<>();
        walk(where + " $", answer, offences);
        return offences;
    }

    private static void walk(String path, Object node, List<String> offences) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (LEASE_MEMBERS.contains(key)) {
                    offences.add(path + "." + key + " is a member of a lease");
                }
                walk(path + "." + key, entry.getValue(), offences);
            }
        } else if (node instanceof Collection<?> list) {
            int index = 0;
            for (Object element : list) {
                walk(path + "[" + index++ + "]", element, offences);
            }
        }
    }

    /**
     * The calls an answer's {@code next} names, against the ones expected:
     * each step is {@code {call, does}} with a sentence under {@code does},
     * the calls are exactly the expected ones, each once, and the answer
     * carries no {@code waiting_for} (no object of this service waits for
     * another party).
     *
     * @param where    what the answer was, for the offence's text
     * @param answer   one object's answer, or one listing entry, as parsed
     * @param expected the calls, verb and complete address, in the names of the
     *                 surface the answer came over
     */
    public static List<String> nextOffences(String where, Map<String, Object> answer,
                                            Collection<String> expected) {
        List<String> offences = new ArrayList<>();
        if (answer.containsKey("waiting_for")) {
            offences.add(where + ": carries waiting_for");
        }
        Object next = answer.get("next");
        if (!(next instanceof List<?> steps)) {
            offences.add(where + ": next is no list");
            return offences;
        }
        offences.addAll(callOffences(where, steps, expected));
        return offences;
    }

    /**
     * A refusal by state (C8.7, DEC-0043): the code, the object's state, and
     * the calls open on it under {@code data.next}; where {@code children} is
     * expected, the complete addresses it names.
     *
     * @param children the addresses expected under {@code data.children};
     *                 empty where the refusal names none, and then none may
     *                 stand
     */
    public static List<String> stateRefusalOffences(String where, Map<String, Object> refusal,
                                                    String code, String state,
                                                    Collection<String> next,
                                                    Collection<String> children) {
        List<String> offences = new ArrayList<>();
        if (!code.equals(refusal.get("reason"))) {
            offences.add(where + ": the code is " + refusal.get("reason") + ", not " + code);
        }
        if (!(refusal.get("data") instanceof Map<?, ?> data)) {
            offences.add(where + ": the refusal carries no data");
            return offences;
        }
        if (!state.equals(data.get("state"))) {
            offences.add(where + ": data.state is " + data.get("state") + ", not " + state);
        }
        if (data.containsKey("waiting_for")) {
            offences.add(where + ": data carries waiting_for");
        }
        if (data.get("next") instanceof List<?> steps) {
            offences.addAll(callOffences(where + " data", steps, next));
        } else {
            offences.add(where + ": data.next is no list");
        }
        Object named = data.get("children");
        if (children.isEmpty()) {
            if (named != null) {
                offences.add(where + ": data carries children");
            }
        } else if (!(named instanceof List<?> list)
                || !new TreeSet<>(stringsOf(list)).equals(new TreeSet<>(children))) {
            offences.add(where + ": data.children is " + named + ", not " + children);
        }
        return offences;
    }

    /** The {@code call} of each step, in their order. */
    public static List<String> calls(Object next) {
        List<String> calls = new ArrayList<>();
        if (next instanceof List<?> steps) {
            for (Object step : steps) {
                if (step instanceof Map<?, ?> map) {
                    calls.add(String.valueOf(map.get("call")));
                }
            }
        }
        return calls;
    }

    private static List<String> callOffences(String where, List<?> steps,
                                             Collection<String> expected) {
        List<String> offences = new ArrayList<>();
        List<String> named = new ArrayList<>();
        for (Object step : steps) {
            if (!(step instanceof Map<?, ?> map) || !map.keySet().equals(STEP)) {
                offences.add(where + ": a step is no {call, does}: " + step);
                continue;
            }
            if (!(map.get("does") instanceof String does) || does.isBlank()) {
                offences.add(where + ": " + map.get("call") + " says nothing it does");
            }
            named.add(String.valueOf(map.get("call")));
        }
        Set<String> once = new TreeSet<>();
        for (String call : named) {
            if (!once.add(call)) {
                offences.add(where + ": names " + call + " twice");
            }
        }
        for (String call : new TreeSet<>(expected)) {
            if (!named.contains(call)) {
                offences.add(where + ": lacks " + call);
            }
        }
        for (String call : new TreeSet<>(named)) {
            if (!expected.contains(call)) {
                offences.add(where + ": names " + call + " beyond the open calls");
            }
        }
        return offences;
    }

    private static List<String> stringsOf(List<?> list) {
        return list.stream().map(String::valueOf).toList();
    }
}
