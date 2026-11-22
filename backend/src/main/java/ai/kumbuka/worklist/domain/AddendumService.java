/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.repository.ItemRepository;
import ai.kumbuka.worklist.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jspecify.annotations.Nullable;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The addenda of an item: {@code addendum} (REA-0011 D1, D13).
 *
 * <p>Once an item is submitted its content is frozen ({@link FieldClass}).
 * An addendum is how it is corrected or commented while it lives: an
 * append-only text with its author, the delegated identity the author acted
 * through, and the time. The read of an item answers its addenda in the order
 * they were written ({@link Field#ADDENDA}). It is the first stage of the
 * comment system, which extends it without changing it; this service holds
 * nothing beyond that stage.
 *
 * <h2>Beside the item, never its content</h2>
 *
 * {@code addendum} writes one row and nothing else. It presents no conflict
 * token and moves neither the item's change time nor its token, as the tag
 * calls move neither (REA-0011 D1); the item is locked for the length of the
 * call all the same, so that an addendum and a transition decide one after
 * the other. An item in the phase open or in progress takes one
 * ({@link FieldClass#addendumOpen}); a draft is edited instead, and a closed
 * item takes nothing; both are refused by state.
 *
 * <h2>Append only</h2>
 *
 * Nothing written is ever changed or removed: the store grants the runtime
 * role SELECT and INSERT on the table and nothing else (V34). A second
 * addendum is a new row and leaves the first as it is.
 *
 * <h2>Who writes</h2>
 *
 * The author is the calling subject the surface derived from the token, in
 * the form an item's {@code creator} holds it; a caller never names it. The
 * call context of this service carries no delegated identity today, so the
 * row's delegated identity stays empty until the context carries one. Every
 * caller with the write right of the scope may write an addendum.
 */
@ApplicationScoped
@TenantBound
public class AddendumService {

    /*
     * Nothing here logs (DEC-0051, C4). An addendum answers a Written carrying
     * the row it stored, and the surface that called it writes the line:
     * never the text, the author or the delegated identity.
     */

    @Inject ItemRepository items;
    @Inject ItemService itemService;
    @Inject Clock clock;

    /**
     * Writes one addendum on an item.
     *
     * @param text   what the addendum says; absent where the call carried
     *               none, which the surface refuses first
     * @param author the calling subject, recorded as the author
     * @return the item as a read answers it, its addenda included
     * @throws WorklistException {@code INVALID_VALUE} naming {@code text}
     *         where it carries no text; {@code ITEM_UNKNOWN} for an item of
     *         another scope; {@code TRANSITION_REFUSED} for a draft and a
     *         closed item
     */
    @Transactional
    public Written<Map<String, Object>, ItemAddendum> addendum(UUID scopeId, UUID itemId,
            @Nullable Object text, String author) {
        Item item = requireLocked(scopeId, itemId);
        if (!FieldClass.addendumOpen(item.phase())) {
            throw ItemService.transitionRefused();
        }
        String written = text instanceof CharSequence given ? given.toString().trim() : "";
        if (written.isEmpty()) {
            throw new WorklistException(WorklistException.Reason.INVALID_VALUE,
                List.of(ItemFields.ADDENDUM_TEXT));
        }

        ItemAddendum row = new ItemAddendum();
        row.scopeId = scopeId;
        row.itemNumber = item.number;
        row.text = written;
        row.author = author;
        row.writtenAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        items.insertAddendum(row);
        return new Written<>(itemService.read(scopeId, itemId), row);
    }

    /**
     * The item, locked for the call, or the refusal an item of another scope
     * gets. The lock writes nothing: the item's change time and token stay.
     */
    private Item requireLocked(UUID scopeId, UUID itemId) {
        @Nullable Item item = items.lockById(itemId).orElse(null);
        if (item == null || !item.scopeId.equals(scopeId)) {
            throw new WorklistException(WorklistException.Reason.ITEM_UNKNOWN, List.of());
        }
        return item;
    }
}
