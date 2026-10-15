/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.surface;

import ai.kumbuka.worklist.domain.Notice;
import io.quarkus.qute.i18n.MessageBundles;

import java.lang.reflect.InvocationTargetException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Words the notices of an answer where the answer leaves the service.
 *
 * <p>The domain puts a {@link Notice} into an answer -- a warning of a planning
 * write, a finding of {@code validate} -- and never a sentence. This class
 * replaces each one with the text of the {@link Notices} bundle method named
 * after the constant in lower camel case, so the words are the bundle's and the
 * answer carries nothing a call sent (DEC-0043 R18).
 */
public final class NoticeTexts {

    private NoticeTexts() {
    }

    /** The answer with every notice in it worded, at any depth. */
    @SuppressWarnings("unchecked")
    public static Object worded(Object value) {
        if (value instanceof Notice notice) {
            return text(notice);
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> worded = new LinkedHashMap<>();
            ((Map<Object, Object>) map).forEach((k, v) -> worded.put(k, worded(v)));
            return worded;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(NoticeTexts::worded).toList();
        }
        return value;
    }

    /** The text of one notice. */
    public static String text(Notice notice) {
        String[] words = notice.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder name = new StringBuilder(words[0]);
        for (int i = 1; i < words.length; i++) {
            name.append(Character.toUpperCase(words[i].charAt(0))).append(words[i].substring(1));
        }
        try {
            return (String) Notices.class.getMethod(name.toString())
                .invoke(MessageBundles.get(Notices.class));
        } catch (NoSuchMethodException | IllegalAccessException
                 | InvocationTargetException unworded) {
            throw new IllegalStateException("a notice without a text", unworded);
        }
    }
}
