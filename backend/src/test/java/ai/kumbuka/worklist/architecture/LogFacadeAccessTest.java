/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.architecture;

import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Only the two log facades hold or call a logger, and nothing writes to the
 * console (DEC-0051, C4; C13.1, C13.2).
 *
 * <p>A line is safe because of what the facade's parameters admit
 * ({@code LogFacadeTypeTest}). That is worth nothing if a class can still
 * reach a logger past the facade, so this holds the other half: in the
 * compiled main tree, no class but {@code surface.PlatformLog} and
 * {@code tenancy.TenancyLog} has a field of a logging type or makes any
 * access to one — a call, a static call, a method reference, in a lambda or
 * in a nested class alike. The console is the other door out: standard output
 * and standard error leave the container by the same road as the log, and a
 * printed stack trace carries every message of the chain.
 *
 * <p>And no logging call anywhere, the facades' own included, takes an
 * exception: the logger would print its message and its causes' messages,
 * which is text nobody in this service wrote.
 *
 * <p>Read off the compiled classes with ArchUnit, so that what is checked is
 * what runs, not a reading of the source.
 */
class LogFacadeAccessTest {

    /** The two classes that may hold a logger. */
    static final Set<String> FACADES = Set.of(
        "ai.kumbuka.worklist.surface.PlatformLog",
        "ai.kumbuka.worklist.tenancy.TenancyLog");

    /**
     * The types through which a line can be written. A class touching any of
     * them is logging, whatever it calls them.
     */
    static final Set<String> LOGGING_TYPES = Set.of(
        "org.jboss.logging.Logger",
        "org.jboss.logging.BasicLogger",
        "org.jboss.logging.DelegatingBasicLogger",
        "org.jboss.logmanager.Logger",
        "io.quarkus.logging.Log",
        "java.util.logging.Logger",
        "java.lang.System$Logger",
        "org.slf4j.Logger",
        "org.slf4j.LoggerFactory",
        "org.apache.logging.log4j.Logger",
        "org.apache.logging.log4j.LogManager");

    /** Below this the import is not reading the main tree it thinks it is. */
    private static final int MINIMUM_CLASSES = 30;

    @Test
    void no_class_but_the_two_facades_holds_or_calls_a_logger() {
        JavaClasses main = mainTree();
        assertThat(main.size())
            .as("the compiled main tree must have been imported. An import that found "
                + "nothing passes for every reason including the wrong ones")
            .isGreaterThanOrEqualTo(MINIMUM_CLASSES);
        assertThat(FACADES)
            .as("both facades must have been found writing through a logger, or the check "
                + "is not reading the classes it thinks it is")
            .allSatisfy(facade -> assertThat(loggingAccessesOf(main, facade)).isPositive());

        assertThat(loggerOffences(main))
            .as("a line is safe because of what a facade's parameters admit. A class that "
                + "reaches a logger past the facades writes whatever it holds: an item's "
                + "title, a caller's scope name, an exception's message. Add a method with "
                + "a constant message and typed parameters to PlatformLog or TenancyLog "
                + "and call that (DEC-0051, C4)")
            .isEmpty();
    }

    @Test
    void no_class_writes_to_the_console() {
        assertThat(consoleOffences(mainTree()))
            .as("nothing in the service writes to the console. A printed stack trace "
                + "carries the exception's message and its causes' messages, and standard "
                + "output and standard error leave the container by the same road as the log")
            .isEmpty();
    }

    @Test
    void no_logging_call_takes_an_exception() {
        assertThat(throwableOffences(mainTree()))
            .as("a log call never takes an exception, the facades' own included. An "
                + "exception's message is text nobody in the service wrote, and a database's "
                + "message names the row it rejected, which holds the content; the logger "
                + "prints it with every cause (C13.2)")
            .isEmpty();
    }

    /**
     * The red state, observed on every build: one planted class for each
     * offence, and each one reported by its name.
     */
    @Test
    void the_checks_report_each_offence_they_exist_for() {
        JavaClasses planted = new ClassFileImporter().importClasses(PlantedLoggerCaller.class,
            PlantedLoggerHolder.class, PlantedConsoleWriter.class, PlantedThrowableLogger.class);

        assertThat(loggerOffences(planted))
            .as("RED STATE, observed: a class outside the facades that calls the logger "
                + "is reported, and so is one that holds a logger in a field")
            .anySatisfy(offence -> assertThat(offence)
                .startsWith(PlantedLoggerCaller.class.getName() + " calls org.jboss.logging.Logger."))
            .anySatisfy(offence -> assertThat(offence)
                .startsWith(PlantedLoggerHolder.class.getName() + " holds a logger"));
        assertThat(consoleOffences(planted))
            .as("RED STATE, observed: a class that writes to System.out is reported")
            .containsExactly(PlantedConsoleWriter.class.getName() + " writes to System.out");
        assertThat(throwableOffences(planted))
            .as("RED STATE, observed: a logging call that takes an exception is reported")
            .singleElement().asString()
            .startsWith(PlantedThrowableLogger.class.getName() + " hands an exception to");
    }

    /** A class outside the facades that calls the logger. */
    static final class PlantedLoggerCaller {

        void log() {
            Logger.getLogger(PlantedLoggerCaller.class).info("planted");
        }
    }

    /** A class outside the facades that holds a logger. */
    static final class PlantedLoggerHolder {

        private final Logger held;

        PlantedLoggerHolder(Logger held) {
            this.held = held;
        }

        @Override
        public String toString() {
            return String.valueOf(held);
        }
    }

    /** A class that writes to the console: the offence this guard exists for, planted. */
    @SuppressWarnings("java:S106")
    static final class PlantedConsoleWriter {

        void print() {
            System.out.println("printed past the facades");
        }
    }

    /** A logging call that takes an exception. */
    static final class PlantedThrowableLogger {

        void log(RuntimeException failure) {
            Logger.getLogger(PlantedThrowableLogger.class).error("failed past the facades", failure);
        }
    }

    // ======================================================================

    private static JavaClasses mainTree() {
        return new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(SourceTree.BASE);
    }

    /** The class a nested class belongs to: {@code PlatformLog$Count} is {@code PlatformLog}. */
    private static String topLevelOf(JavaClass type) {
        String name = type.getName();
        int nested = name.indexOf('$');
        return nested < 0 ? name : name.substring(0, nested);
    }

    private static long loggingAccessesOf(JavaClasses classes, String facade) {
        return classes.stream()
            .filter(type -> topLevelOf(type).equals(facade))
            .flatMap(type -> type.getAccessesFromSelf().stream())
            .filter(LogFacadeAccessTest::isLogging)
            .count();
    }

    private static boolean isLogging(JavaAccess<?> access) {
        String owner = access.getTargetOwner().getName();
        return LOGGING_TYPES.contains(owner)
            || ("java.lang.System".equals(owner) && "getLogger".equals(access.getName()));
    }

    /** Every class outside the facades that holds a logger or accesses one. */
    static List<String> loggerOffences(JavaClasses classes) {
        List<String> offences = new ArrayList<>();
        for (JavaClass type : classes) {
            if (FACADES.contains(topLevelOf(type))) {
                continue;
            }
            for (JavaField field : type.getFields()) {
                if (LOGGING_TYPES.contains(field.getRawType().getName())) {
                    offences.add(type.getName() + " holds a logger in " + field.getName());
                }
            }
            for (JavaAccess<?> access : type.getAccessesFromSelf()) {
                if (isLogging(access)) {
                    offences.add(type.getName() + " calls " + access.getTargetOwner().getName()
                        + "." + access.getName());
                }
            }
        }
        return offences.stream().distinct().sorted().toList();
    }

    /** Every class that writes to the console or prints a stack trace. */
    static List<String> consoleOffences(JavaClasses classes) {
        List<String> offences = new ArrayList<>();
        for (JavaClass type : classes) {
            for (JavaAccess<?> access : type.getAccessesFromSelf()) {
                String owner = access.getTargetOwner().getName();
                if ("java.lang.System".equals(owner)
                        && Set.of("out", "err").contains(access.getName())) {
                    offences.add(type.getName() + " writes to System." + access.getName());
                } else if ("printStackTrace".equals(access.getName())) {
                    offences.add(type.getName() + " prints a stack trace");
                }
            }
        }
        return offences.stream().distinct().sorted().toList();
    }

    /** Every logging call, in any class, that takes a {@code Throwable}. */
    static List<String> throwableOffences(JavaClasses classes) {
        List<String> offences = new ArrayList<>();
        for (JavaClass type : classes) {
            for (JavaMethodCall call : type.getMethodCallsFromSelf()) {
                if (isLogging(call) && call.getTarget().getRawParameterTypes().stream()
                        .anyMatch(parameter -> "java.lang.Throwable".equals(parameter.getName()))) {
                    offences.add(type.getName() + " hands an exception to "
                        + call.getTarget().getFullName());
                }
            }
        }
        return offences.stream().distinct().sorted().toList();
    }
}
