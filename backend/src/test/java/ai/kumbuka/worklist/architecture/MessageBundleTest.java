/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.architecture;

import ai.kumbuka.worklist.fixture.ProseBundleFixture;
import io.quarkus.qute.i18n.Message;
import io.quarkus.qute.i18n.MessageBundle;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No text addressed to a caller lives in Java code
 * ({@code code-conventions/service-construction.md}, texts addressed to a
 * caller, rules 1 and 3).
 *
 * <p>Four checks. Every {@code @MessageBundle} interface carries
 * {@code @Message} without a value on each method, so the words are in the
 * properties file and the build fails when one is missing. Every bundle has
 * exactly one properties file under {@code src/main/resources/messages/}, whose
 * keys are the bundle's methods. Nothing is localized: no {@code @Localized}
 * and no file with a locale suffix -- one set of texts per component. And no
 * string constant of prose sits in the production code where it could reach
 * the surface.
 *
 * <p><strong>What counts as prose, and what is exempt.</strong> A string
 * literal of four or more words. Exempt are the statements that cannot reach a
 * caller: a log line, an exception of the JVM's own that only the unexpected
 * failure mapper ever sees (and that mapper answers with the catalogue's
 * text), an annotation, and the persistence and tenancy layers, whose literals
 * are SQL and configuration. The exemption is by kind of statement, never by
 * file or by text.
 */
class MessageBundleTest {

    private static final Pattern INTERNAL = Pattern.compile(
        "\\bLOG\\s*\\.\\s*\\w+\\s*\\(|new\\s+(IllegalStateException|IllegalArgumentException"
            + "|UnsupportedOperationException|AssertionError|UncheckedIOException)\\s*\\(|@\\w+\\s*\\(");

    private static final Set<String> NO_CALLER_TEXT = Set.of("repository", "tenancy");

    private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    private static final Pattern WORD = Pattern.compile("[A-Za-z]+");

    // ======================================================================
    // The bundles
    // ======================================================================

    @Test
    void every_message_of_every_bundle_takes_its_text_from_a_file() {
        List<Class<?>> bundles = bundles();
        assertThat(bundles)
            .as("this service has message bundles; finding none would mean the check looks "
                + "in the wrong place and passes because of it")
            .isNotEmpty();
        List<String> offenders = new ArrayList<>();
        bundles.forEach(bundle -> offenders.addAll(valuedMessages(bundle)));
        assertThat(offenders)
            .as("@Message carries no value: the text lives in the properties file, so a "
                + "missing text fails the build instead of shipping a default")
            .isEmpty();
    }

    @Test
    void the_check_reports_a_message_that_carries_its_text() {
        assertThat(valuedMessages(ProseBundleFixture.class))
            .as("RED STATE, observed: a @Message with a value must be reported, or the "
                + "check above would pass whatever the bundles say")
            .isNotEmpty();
    }

    @Test
    void every_bundle_has_one_file_whose_keys_are_its_methods() {
        for (Class<?> bundle : bundles()) {
            String name = bundle.getAnnotation(MessageBundle.class).value();
            Properties texts = texts(name);
            Set<String> methods = new TreeSet<>();
            Arrays.stream(bundle.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Message.class))
                .forEach(m -> methods.add(m.getName()));
            assertThat(new TreeSet<>(texts.stringPropertyNames()))
                .as("messages/%s.properties holds exactly the texts of %s", name,
                    bundle.getSimpleName())
                .isEqualTo(methods);
            texts.stringPropertyNames().forEach(key -> assertThat(texts.getProperty(key))
                .as("%s.%s carries a text", name, key).isNotBlank());
        }
    }

    @Test
    void one_set_of_texts_and_nothing_localized() {
        List<String> localized = new ArrayList<>();
        for (Path file : SourceTree.files(SourceTree.root("main"))) {
            if (strip(SourceTree.text(file)).contains("@Localized")) {
                localized.add(file.getFileName().toString());
            }
        }
        try (Stream<Path> files = Files.list(messages())) {
            files.map(f -> f.getFileName().toString())
                .filter(f -> f.matches(".*_[a-z]{2}([_-][A-Za-z]{2})?\\.properties"))
                .forEach(localized::add);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
        assertThat(localized)
            .as("one set of texts per component: no @Localized bundle and no file with a "
                + "locale suffix. A different wording is tried on a branch")
            .isEmpty();
    }

    // ======================================================================
    // Prose in the code
    // ======================================================================

    @Test
    void no_string_constant_of_prose_reaches_the_surface() {
        Path root = SourceTree.root("main");
        List<String> offenders = new ArrayList<>();
        for (Path file : SourceTree.files(root)) {
            if (NO_CALLER_TEXT.contains(SourceTree.layerOf(root, file))) {
                continue;
            }
            for (String prose : prose(SourceTree.text(file))) {
                offenders.add(file.getFileName() + ": " + prose);
            }
        }
        assertThat(offenders)
            .as("a text addressed to a caller lives in a message bundle under "
                + "src/main/resources/messages/, and its placeholders take the platform's "
                + "vocabulary only. Move the text there and render it through the bundle")
            .isEmpty();
    }

    @Test
    void the_check_reports_prose_in_a_refusal() {
        String source = """
            class Planted {
                void refuse(String scope) {
                    throw new SurfaceException(RefusalCode.NOT_FOUND,
                        "the scope " + scope + " is not visible to you");
                }
                void log() {
                    LOG.infof("a log line may say what it likes about %s", "this");
                }
            }
            """;
        assertThat(prose(source))
            .as("RED STATE, observed: prose in a refusal must be reported, and the log line "
                + "beside it must not be")
            .hasSize(1);
    }

    // ======================================================================
    // Reading
    // ======================================================================

    static List<Class<?>> bundles() {
        Path root = SourceTree.root("main");
        List<Class<?>> bundles = new ArrayList<>();
        for (Path file : SourceTree.files(root)) {
            if (strip(SourceTree.text(file)).contains("@MessageBundle")) {
                try {
                    bundles.add(Class.forName(SourceTree.fqcn(root, file)));
                } catch (ClassNotFoundException unbuilt) {
                    throw new IllegalStateException(unbuilt);
                }
            }
        }
        return bundles;
    }

    private static List<String> valuedMessages(Class<?> bundle) {
        List<String> offenders = new ArrayList<>();
        for (Method method : bundle.getDeclaredMethods()) {
            Message message = method.getAnnotation(Message.class);
            if (message == null || !Message.DEFAULT_VALUE.equals(message.value())) {
                offenders.add(bundle.getSimpleName() + "." + method.getName());
            }
        }
        return offenders;
    }

    static Path messages() {
        Path direct = Paths.get("src", "main", "resources", "messages");
        return Files.isDirectory(direct) ? direct
            : Paths.get("backend", "src", "main", "resources", "messages");
    }

    static Properties texts(String bundle) {
        Path file = messages().resolve(bundle + ".properties");
        Properties texts = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            texts.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
        return texts;
    }

    /** The prose literals of a source text, outside the exempt kinds of statement. */
    static List<String> prose(String source) {
        List<String> found = new ArrayList<>();
        for (String statement : statements(strip(source))) {
            if (INTERNAL.matcher(statement).find()) {
                continue;
            }
            Matcher literal = LITERAL.matcher(statement);
            while (literal.find()) {
                String text = literal.group(1);
                long words = WORD.matcher(text).results().count();
                if (text.trim().split("\\s+").length >= 4 && words >= 4) {
                    found.add(text);
                }
            }
        }
        return found;
    }

    /** The source with its comments blanked, string and character literals kept. */
    private static String strip(String source) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < source.length()) {
            if (source.startsWith("//", i)) {
                int end = source.indexOf('\n', i);
                i = end < 0 ? source.length() : end;
            } else if (source.startsWith("/*", i)) {
                i = source.indexOf("*/", i) + 2;
                out.append(' ');
            } else if (source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3) + 3;
                out.append(source, i, end);
                i = end;
            } else if (source.charAt(i) == '"' || source.charAt(i) == '\'') {
                int end = closing(source, i);
                out.append(source, i, end);
                i = end;
            } else {
                out.append(source.charAt(i));
                i++;
            }
        }
        return out.toString();
    }

    private static int closing(String source, int open) {
        char quote = source.charAt(open);
        int i = open + 1;
        while (source.charAt(i) != quote) {
            i += source.charAt(i) == '\\' ? 2 : 1;
        }
        return i + 1;
    }

    /** The statements of a source, split at {@code ;}, {@code {} and {@code }} outside literals. */
    private static List<String> statements(String source) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '"' || c == '\'') {
                int end = closing(source, i);
                current.append(source, i, end);
                i = end;
                continue;
            }
            if (c == ';' || c == '{' || c == '}') {
                statements.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
            i++;
        }
        statements.add(current.toString());
        return statements;
    }
}
