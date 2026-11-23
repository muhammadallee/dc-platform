package javax.example;

/**
 * An application number in a JDK-looking package (Codex phase 4 round 1 verification): the masker must not trust it as
 * the JDK's own. Its text renders what it holds.
 */
public final class LookalikeAmount extends Number {

    private final transient Object entity;

    public LookalikeAmount(Object entity) {
        this.entity = entity;
    }

    public Object getEntity() {
        return entity;
    }

    @Override
    public int intValue() {
        return 1;
    }

    @Override
    public long longValue() {
        return 1;
    }

    @Override
    public float floatValue() {
        return 1;
    }

    @Override
    public double doubleValue() {
        return 1;
    }

    @Override
    public String toString() {
        return String.valueOf(entity);
    }
}
