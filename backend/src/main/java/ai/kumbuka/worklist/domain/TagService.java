/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.repository.ItemRepository;
import ai.kumbuka.worklist.repository.TagRepository;
import ai.kumbuka.worklist.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jspecify.annotations.Nullable;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The tags of an item: {@code tag_attach} and {@code tag_detach}.
 *
 * <p>The vocabulary is the platform's. A tag is declared in a scope there,
 * and this service never declares, renames or withdraws one; it reads the
 * scope's vocabulary through {@code platform.tag_access} and keeps which item
 * carries which tag. The same two verbs, with the same semantics, are carried
 * by the dispatch and the memory services for their own objects.
 *
 * <h2>A relation, not a field</h2>
 *
 * Neither verb presents a conflict token, and neither moves the item's
 * {@code changed_at} or its token: the assignment is a row of its own, and the
 * item's state is untouched by it. The item is locked for the length of the
 * call all the same, so that two calls attaching the same tag decide one after
 * the other rather than meet at the primary key.
 *
 * <h2>Repeating is answered, never refused</h2>
 *
 * Attaching a tag the item carries and detaching one it does not carry are
 * both successful calls that write nothing. A caller that is unsure whether
 * its first call landed sends it again.
 *
 * <p>Only items carry tags; the surface refuses a tag verb at an iteration, a
 * milestone or a workstream before it reaches here. Any status of an item
 * admits tags, intake and closed ones included.
 */
@ApplicationScoped
@TenantBound
public class TagService {

    /*
     * Nothing here logs (DEC-0051, C4). A write answers a Written carrying the
     * assignment it stored or removed, and the surface that called it writes
     * the line: never the tag's token, never the subject -- who attached a tag
     * is stored with the assignment, and a log line naming it would be a
     * second record of it.
     */

    /** The argument a refusal about the tag names. */
    static final String TAG_ARGUMENT = "tag";

    @Inject ItemRepository items;
    @Inject TagRepository tags;
    @Inject ItemService itemService;
    @Inject Clock clock;

    /**
     * Attaches one tag of the scope's vocabulary to an item.
     *
     * <p>The token is resolved in the item's own scope, through the view, so a
     * token of another scope -- or of a scope the subject cannot see -- is a
     * token this scope does not declare.
     *
     * @param subject the calling subject, recorded as who attached the tag
     * @return the item as a read answers it, its tags included
     * @throws WorklistException {@code VALUE_UNDECLARED} naming {@code tag} for a
     *         token the scope does not declare, {@code TAG_WITHDRAWN} for one the
     *         platform withdrew
     */
    @Transactional
    public Written<Map<String, Object>, ItemTag> tagAttach(UUID scopeId, UUID itemId,
                                                           String token, String subject) {
        Item item = requireLocked(scopeId, itemId);
        TagRepository.TagRow tag = requireDeclared(scopeId, token);
        if (tag.isWithdrawn()) {
            throw new WorklistException(WorklistException.Reason.TAG_WITHDRAWN,
                List.of(TAG_ARGUMENT));
        }

        if (tags.find(scopeId, item.number, tag.tagId()).isPresent()) {
            return Written.unchanged(itemService.read(scopeId, itemId));
        }
        ItemTag assignment = new ItemTag();
        assignment.scopeId = scopeId;
        assignment.itemNumber = item.number;
        assignment.tagId = tag.tagId();
        assignment.attachedBy = subject;
        assignment.attachedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        tags.insert(assignment);
        return new Written<>(itemService.read(scopeId, itemId), assignment);
    }

    /**
     * Detaches one tag from an item. A withdrawn tag detaches like any other.
     *
     * @return the item as a read answers it, its tags included
     * @throws WorklistException {@code VALUE_UNDECLARED} naming {@code tag} for a
     *         token the scope does not declare
     */
    @Transactional
    public Written<Map<String, Object>, ItemTag> tagDetach(UUID scopeId, UUID itemId,
                                                           String token) {
        Item item = requireLocked(scopeId, itemId);
        TagRepository.TagRow tag = requireDeclared(scopeId, token);

        Optional<ItemTag> carried = tags.find(scopeId, item.number, tag.tagId());
        if (carried.isEmpty()) {
            return Written.unchanged(itemService.read(scopeId, itemId));
        }
        tags.remove(carried.get());
        return new Written<>(itemService.read(scopeId, itemId), carried.get());
    }

    /** The tag of a token in the scope's vocabulary, or the not-found refusal of a value. */
    private TagRepository.TagRow requireDeclared(UUID scopeId, String token) {
        return tags.inVocabulary(scopeId, token)
            .orElseThrow(() -> new WorklistException(
                WorklistException.Reason.VALUE_UNDECLARED, List.of(TAG_ARGUMENT)));
    }

    /**
     * The item, locked for the call, or the refusal an item of another scope
     * gets. The lock writes nothing: the item's timestamp and token stay.
     */
    private Item requireLocked(UUID scopeId, UUID itemId) {
        @Nullable Item item = items.lockById(itemId).orElse(null);
        if (item == null || !item.scopeId.equals(scopeId)) {
            throw new WorklistException(WorklistException.Reason.ITEM_UNKNOWN, List.of());
        }
        return item;
    }
}
