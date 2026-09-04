package ai.kumbuka.worklist.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.hibernate.orm.JsonFormat;
import io.quarkus.hibernate.orm.PersistenceUnitExtension;
import jakarta.inject.Singleton;
import org.hibernate.type.descriptor.WrapperOptions;
import org.hibernate.type.descriptor.java.JavaType;
import org.hibernate.type.format.FormatMapper;
import org.hibernate.type.format.jackson.JacksonJsonFormatMapper;

/**
 * How a document column is written and read back.
 *
 * <h2>Why this class exists rather than a configuration line</h2>
 *
 * This service holds one {@code jsonb} column — the declared attributes of an
 * item — and Hibernate needs to be told how to turn a Java value into that
 * column and back. Quarkus offers its OWN serialisation facilities for the
 * job and then refuses to use them without being asked, which is the right
 * refusal and worth recording: those facilities are configured for REST
 * endpoints, and a setting made for a payload would silently change what the
 * DATABASE holds. The framework names the failure mode itself — up to and
 * including data loss.
 *
 * <p>Concretely, the setting that would have travelled is
 * {@code write-dates-as-timestamps}. A date rendered one way in an endpoint
 * and another way in a column is a difference nobody would notice until a
 * value read back did not match the one written.
 *
 * <p>So the mapper is defined here, with its own {@link ObjectMapper}, and it
 * shares nothing with the endpoint side. The two can be configured
 * independently because they answer to different readers: one to a caller
 * over the wire, one to a column that has to give back exactly what it took.
 *
 * <h2>Why the default configuration is deliberately plain</h2>
 *
 * A stored document is not a presentation. Nothing here pretty-prints,
 * renames a field or drops a null, because every one of those is a decision
 * about how a value LOOKS and this column is not looked at — it is compared.
 * The comparison behind "a write that changes nothing writes nothing" reads
 * the column back and holds it against what a caller sent, so a mapper that
 * reformatted on the way through would report a change on every round trip.
 *
 * <p>It sits in the repository package because that is where persistence
 * lives: it is the one place in this service that knows how a value becomes a
 * column.
 */
@JsonFormat
@PersistenceUnitExtension
@Singleton
public class DocumentColumnFormat implements FormatMapper {

    /**
     * Hibernate's own Jackson mapper, over an ObjectMapper this class owns.
     *
     * <p>Delegated rather than reimplemented: the conversion between a Java
     * type and JSON is Hibernate's problem and it has solved it. What this
     * class decides is WHICH object mapper does it, and that decision is the
     * whole reason the class is here.
     */
    private final FormatMapper delegate = new JacksonJsonFormatMapper(new ObjectMapper());

    @Override
    public <T> T fromString(CharSequence charSequence, JavaType<T> javaType,
            WrapperOptions wrapperOptions) {
        return delegate.fromString(charSequence, javaType, wrapperOptions);
    }

    @Override
    public <T> String toString(T value, JavaType<T> javaType, WrapperOptions wrapperOptions) {
        return delegate.toString(value, javaType, wrapperOptions);
    }
}
