/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.fixture;

import org.jboss.logmanager.ExtLogRecord;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** The log records one class writes while an action runs, as the log handler receives them. */
public final class LogLines {

    private LogLines() {
    }

    /** Runs the action, ignoring what it throws, and answers what the class logged meanwhile. */
    public static List<LogRecord> of(Class<?> writer, Runnable action) {
        List<LogRecord> records = new CopyOnWriteArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord logged) {
                records.add(logged);
            }

            @Override
            public void flush() {
                // nothing buffered
            }

            @Override
            public void close() {
                // nothing held
            }
        };
        Logger logger = Logger.getLogger(writer.getName());
        logger.addHandler(capture);
        try {
            action.run();
        } catch (RuntimeException refused) {
            // the refusal is the action's; the records are the answer
        } finally {
            logger.removeHandler(capture);
        }
        return List.copyOf(records);
    }

    /** A record's text as the line prints it: its format with the arguments filled in. */
    public static String text(LogRecord line) {
        return line instanceof ExtLogRecord ext ? ext.getFormattedMessage() : line.getMessage();
    }
}
