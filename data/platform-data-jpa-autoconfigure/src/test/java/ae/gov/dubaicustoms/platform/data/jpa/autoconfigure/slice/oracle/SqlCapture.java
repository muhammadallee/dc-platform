package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.slice.oracle;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.hibernate.resource.jdbc.spi.StatementInspector;

/**
 * Records every SQL string Hibernate prepares, so a test can assert the dialect's syntax (registered
 * with {@code hibernate.session_factory.statement_inspector}).
 */
public class SqlCapture implements StatementInspector {

    static final List<String> STATEMENTS = new CopyOnWriteArrayList<>();

    @Override
    public String inspect(String sql) {
        STATEMENTS.add(sql);
        return sql;
    }
}
