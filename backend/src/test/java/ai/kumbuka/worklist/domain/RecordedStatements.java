/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.domain;

import io.quarkus.hibernate.orm.PersistenceUnitExtension;
import jakarta.enterprise.context.ApplicationScoped;
import org.hibernate.resource.jdbc.spi.StatementInspector;

import java.util.ArrayList;
import java.util.List;

/**
 * Records the statements Hibernate prepares, unchanged, while a probe has
 * recording switched on, so the probe can ask the planner about the statement
 * the service actually issues rather than about one written to resemble it.
 *
 * <p>Test code only. It returns each statement as it received it, so it
 * changes nothing a test observes beyond the record it keeps, and it keeps
 * none while recording is off — the rest of the suite runs past it.
 */
@PersistenceUnitExtension
@ApplicationScoped
public class RecordedStatements implements StatementInspector {

    private static final long serialVersionUID = 1L;

    private static final List<String> RECORDED = new ArrayList<>();
    private static boolean recording;

    @Override
    public String inspect(String sql) {
        synchronized (RECORDED) {
            if (recording) {
                RECORDED.add(sql);
            }
        }
        return sql;
    }

    /** Forgets what was recorded so far and records from here on. */
    public static void start() {
        synchronized (RECORDED) {
            RECORDED.clear();
            recording = true;
        }
    }

    /** The statements recorded since {@link #start()}, in order; recording stops. */
    public static List<String> stop() {
        synchronized (RECORDED) {
            recording = false;
            return List.copyOf(RECORDED);
        }
    }
}
