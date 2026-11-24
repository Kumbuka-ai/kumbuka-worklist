/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.domain;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * The classes of an item's fields, and the phases in which each may be
 * written (REA-0011 D13, Annex A2; TAR-0002 B1, B20).
 *
 * <p>An item's content is its title, its description and its acceptance
 * criteria; its classification is its type -- the declared attribute
 * {@code type} -- and its body of work, the workstream; everything else on
 * it is planning, its status within the phase included. What of it may
 * change depends on the phase alone:
 *
 * <table>
 *   <caption>Annex A2</caption>
 *   <tr><th>class</th><th>draft</th><th>open</th><th>in_progress</th><th>closed</th></tr>
 *   <tr><td>content</td><td>yes</td><td>no</td><td>no</td><td>no</td></tr>
 *   <tr><td>classification</td><td>yes</td><td>yes</td><td>no</td><td>no</td></tr>
 *   <tr><td>planning</td><td>yes</td><td>yes</td><td>yes</td><td>no</td></tr>
 * </table>
 *
 * <p>This is the one place the table stands. {@code update} reads
 * {@link #writableIn} to refuse a write to a frozen class by state, and the
 * open calls of an item read {@link #addendumOpen}, which is derived from the
 * same table: a call an answer names is a call its object takes.
 *
 * <p>The references and the relations of an item belong to no class: they
 * are not changed in place but appended and cancelled (REA-0011 D15), and
 * their row of the table is TAR-0002 Annex A4's, which this class holds
 * beside the three:
 *
 * <table>
 *   <caption>Annex A4, references and relations</caption>
 *   <tr><th>act</th><th>draft</th><th>open</th><th>in_progress</th><th>closed</th></tr>
 *   <tr><td>append</td><td>yes</td><td>yes</td><td>yes</td><td>no</td></tr>
 *   <tr><td>cancel</td><td>yes</td><td>yes</td><td>no</td><td>no</td></tr>
 * </table>
 *
 * <p>{@code update}, {@code relate} and {@code unrelate} read
 * {@link #edgeAppendOpen} and {@link #edgeCancelOpen} to refuse by state, and
 * the open calls of an item read them to name {@code relate} and
 * {@code unrelate}: whoever needs to cancel on an item in progress returns
 * it first.
 */
public enum FieldClass {

    /** Title, description and criteria: frozen at submit. */
    CONTENT(EnumSet.of(Phase.DRAFT)),

    /** The type and the workstream: frozen at start. */
    CLASSIFICATION(EnumSet.of(Phase.DRAFT, Phase.OPEN)),

    /** Everything else declared on the item: frozen at close. */
    PLANNING(EnumSet.of(Phase.DRAFT, Phase.OPEN, Phase.IN_PROGRESS));

    private final Set<Phase> writable;

    FieldClass(Set<Phase> writable) {
        this.writable = writable;
    }

    /** Whether a field of this class may be changed on an item in this phase. */
    public boolean writableIn(Phase phase) {
        return writable.contains(phase);
    }

    /**
     * The class of a field of the item, where it has one. The attributes are
     * planning as a whole; the one attribute that is classification, the
     * type, is told apart by its key ({@link #ofAttribute}).
     */
    public static Optional<FieldClass> of(Field field) {
        return switch (field) {
            case TITLE, DESCRIPTION, CRITERIA -> Optional.of(CONTENT);
            case WORKSTREAM_ID -> Optional.of(CLASSIFICATION);
            case STATUS, MILESTONE_ID, ATTRIBUTES, CONTRIBUTOR, PARENT, PRIORITY, SEVERITY ->
                Optional.of(PLANNING);
            default -> Optional.empty();
        };
    }

    /**
     * The class of one declared attribute, by its key: the type is
     * classification, every other attribute planning.
     */
    public static FieldClass ofAttribute(String key) {
        return ItemService.TYPE_KEY.equals(key) ? CLASSIFICATION : PLANNING;
    }

    /**
     * Whether an item in this phase takes an addendum: its content is frozen
     * and it still takes writes, which is the phase open and the phase in
     * progress. A draft is corrected by editing it; a closed item takes
     * nothing, and a correction to it is a new item that refers to it.
     */
    public static boolean addendumOpen(Phase phase) {
        return !CONTENT.writableIn(phase) && PLANNING.writableIn(phase);
    }

    /** The phases in which an item takes a new reference or relation (Annex A4). */
    private static final Set<Phase> EDGE_APPEND =
        EnumSet.of(Phase.DRAFT, Phase.OPEN, Phase.IN_PROGRESS);

    /** The phases in which an item's reference or relation is cancelled (Annex A4). */
    private static final Set<Phase> EDGE_CANCEL = EnumSet.of(Phase.DRAFT, Phase.OPEN);

    /**
     * Whether an item in this phase takes a new reference or relation, or an
     * edge asserted again: every phase but closed.
     */
    public static boolean edgeAppendOpen(Phase phase) {
        return EDGE_APPEND.contains(phase);
    }

    /**
     * Whether a reference or relation of an item in this phase may be
     * cancelled, or an active reference moved in the list: a draft and an
     * open item. In progress only appending is open, and a closed item takes
     * nothing.
     */
    public static boolean edgeCancelOpen(Phase phase) {
        return EDGE_CANCEL.contains(phase);
    }
}
