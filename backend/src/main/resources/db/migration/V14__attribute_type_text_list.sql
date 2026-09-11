-- ---------------------------------------------------------------------------
-- The eighth attribute type: an ordered list of free-text entries.
--
-- The type set is closed at the platform level (concept §3.7): a scope may
-- pick any one for a declared attribute, and the platform asks no question
-- about which. Widening the set is a schema change here and a matching
-- line in AttributeDefinition.TYPES on the Java side; the two must agree
-- or a value the check accepts would be refused by the constant, and the
-- other way round.
--
-- WHY A LIST CANNOT ALSO BE SORTABLE
-- Ordering by a declared attribute is a capability a scope declares, and
-- what it declares is an expression index the read path picks up. A list
-- has no total order — is [a, b, c] before or after [a, c]? — so an
-- expression index over one would be a promise the value cannot keep.
-- The refusal is a check constraint below rather than a comment in the
-- Java side: the concept says the schema is what still holds when nothing
-- above it does, and the read-path index that would be built on top would
-- pick whichever total order the underlying operator class had, silently.
--
-- N-1 COMPATIBILITY
-- Both changes are additive. The widened `type IN (...)` admits more values
-- than the previous one and refuses none the previous one admitted; the
-- new `sortable`-versus-list clause is a refusal no existing row can trip,
-- because no row of type 'text_list' existed before this migration. A
-- predecessor image reads a text_list column back unmodified (its
-- storedValue passes unknown types through), so a rollback during the
-- window is a rollback that reads what it stored — nothing corrupts.
--
-- WRITE-PATH CAPS ARE NOT HERE
-- The domain refuses a list longer than 50 or an entry longer than 1500
-- and it says which key was refused (INVALID_VALUE, offender = the key).
-- Neither cap lives in the schema: caps that bound the write path bind
-- what a fresh write may produce and never what a read may return
-- (concept §6.1), and a check constraint would seal the store against its
-- own content. The migration widens the type set and adds the one refusal
-- the schema is the only place for; the rest is the domain's.
-- ---------------------------------------------------------------------------

ALTER TABLE worklist.attribute_definition
    DROP CONSTRAINT ck_attribute_definition_type;

ALTER TABLE worklist.attribute_definition
    ADD CONSTRAINT ck_attribute_definition_type CHECK (type IN (
        'text', 'number', 'date', 'boolean', 'choice', 'multi_choice',
        'item_reference', 'text_list'));

ALTER TABLE worklist.attribute_definition
    ADD CONSTRAINT ck_attribute_definition_text_list_not_sortable
        CHECK (NOT (type = 'text_list' AND sortable));
