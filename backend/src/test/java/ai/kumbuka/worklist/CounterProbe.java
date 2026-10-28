/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class of the test tree as a counter-probe: it holds an expectation
 * written by hand, or checks a production answer against one, and so imports
 * nothing from the production packages (C22.5, ADR-0039).
 *
 * <p>{@link CounterProbeIndependenceTest} holds every class so marked to that.
 * A probe whose expectation comes from the artefact it checks is consistent
 * with it by construction and proves nothing.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface CounterProbe {
}
