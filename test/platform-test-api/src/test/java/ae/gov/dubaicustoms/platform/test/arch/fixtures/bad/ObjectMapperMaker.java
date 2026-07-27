package ae.gov.dubaicustoms.platform.test.arch.fixtures.bad;

import com.fasterxml.jackson.databind.ObjectMapper;

// Non-conformant: constructs its own ObjectMapper instead of injecting the platform-managed one.
public class ObjectMapperMaker {

    private final ObjectMapper mapper = new ObjectMapper();

    public ObjectMapper mapper() {
        return mapper;
    }
}
