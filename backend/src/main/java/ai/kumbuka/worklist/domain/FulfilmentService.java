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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The fulfilment ledger of an item: {@code book}.
 *
 * <p>A booking records what one instance reported against the acceptance
 * criteria of an item: per criterion identifier a status -- {@code met},
 * {@code not_met} or {@code not_applicable}, the platform's three values and
 * no scope's -- and an evidence reference, with the instance's address, the
 * actor and the time. The read of an item answers the ledger
 * ({@link Field#FULFILMENT}).
 *
 * <h2>Beside the item, never its content</h2>
 *
 * {@code book} writes the ledger and nothing else. It presents no conflict
 * token and moves neither the item's change time nor its token; the item is
 * locked for the length of the call all the same, so that a booking and a
 * write of the criteria decide one after the other. Any status of an item
 * takes a booking, a closed one included: the ledger records the contract an
 * instance entered, and the contract outlives the item.
 *
 * <h2>Append only</h2>
 *
 * Nothing in the ledger is ever changed or removed: the store grants the
 * runtime role SELECT and INSERT on it and nothing else (V30). A second
 * booking of the same instance for the same criterion is a new entry, and the
 * latest holds. A booking is one act: every entry is written, or none.
 *
 * <h2>Who books</h2>
 *
 * The actor is the calling subject the surface derived from the token, in the
 * form an item's {@code creator} holds it; a caller never names it. Every
 * caller with the write right of the scope may book on every item. Narrowing
 * {@code book} on a closed item to the identity that concludes an instance
 * needs the roles model and the delivery contract of the dispatch side, and
 * comes with a later step.
 *
 * <p>The instance address and the evidence reference are checked for form
 * and never resolved: this service does not know the dispatch service.
 */
@ApplicationScoped
@TenantBound
public class FulfilmentService {

    /*
     * Nothing here logs (DEC-0051, C4). A booking answers a Written carrying
     * the entries it stored, and the surface that called it writes the line:
     * never the instance address, the evidence or the actor.
     */

    @Inject ItemRepository items;
    @Inject ItemService itemService;
    @Inject Clock clock;

    /**
     * Books the entries of one instance against the criteria of an item.
     *
     * <p>Every entry is checked before anything is written: the instance
     * address and each entry for form ({@link ItemFields#booking}), and each
     * identifier against the criteria the item ever issued, withdrawn ones
     * included. An identifier the item never issued is refused; one it
     * withdrew is taken.
     *
     * @param instance the address of the instance that reports; absent where
     *                 the call carried none, which the surface refuses first
     * @param entries  the entries as they arrived, a list of objects
     * @param actor    the calling subject, recorded as who booked
     * @return the item as a read answers it, the ledger included
     * @throws WorklistException {@code INVALID_VALUE} naming {@code instance}
     *         or {@code entries}; {@code ITEM_UNKNOWN} for an item of another
     *         scope
     */
    @Transactional
    public Written<Map<String, Object>, List<ItemFulfilment>> book(UUID scopeId, UUID itemId,
            @Nullable Object instance, @Nullable Object entries, String actor) {
        Item item = requireLocked(scopeId, itemId);
        String address = itemService.addressOf(item);
        String reporting = ItemFields.reference(ItemFields.INSTANCE, instance);
        List<ItemFields.BookingEntry> booked = ItemFields.booking(entries, address);
        refuseNeverIssued(item, booked);

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        List<ItemFulfilment> written = new ArrayList<>(booked.size());
        for (ItemFields.BookingEntry entry : booked) {
            ItemFulfilment row = new ItemFulfilment();
            row.scopeId = scopeId;
            row.itemNumber = item.number;
            row.criterionNumber = entry.criterion();
            row.instance = reporting;
            row.status = entry.status();
            row.evidence = entry.evidence();
            row.actor = actor;
            row.bookedAt = now;
            written.add(row);
        }
        items.insertFulfilment(written);
        return new Written<>(itemService.read(scopeId, itemId), List.copyOf(written));
    }

    /**
     * Refuses an entry naming an identifier the item never issued. Every
     * criterion the item holds counts, the withdrawn ones too: a number once
     * issued names that criterion for good, and an instance that took
     * its copy of the criteria before the withdrawal still reports on it.
     */
    private void refuseNeverIssued(Item item, List<ItemFields.BookingEntry> booked) {
        Set<Long> issued = new HashSet<>();
        items.criteriaOf(item).forEach(criterion -> issued.add(criterion.number));
        for (ItemFields.BookingEntry entry : booked) {
            if (!issued.contains(entry.criterion())) {
                throw new WorklistException(WorklistException.Reason.INVALID_VALUE,
                    List.of(ItemFields.ENTRIES));
            }
        }
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
