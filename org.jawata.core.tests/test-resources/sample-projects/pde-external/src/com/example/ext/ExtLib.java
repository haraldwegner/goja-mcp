package com.example.ext;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sprint 23 (D7) fixture — compiles ONLY when the external bundle pool
 * resolves jackson-databind (Require-Bundle) and org.slf4j + jackson-core
 * (Import-Package) from a pool directory; nothing in the workspace provides
 * them.
 */
public class ExtLib {

    private static final Logger log = LoggerFactory.getLogger(ExtLib.class);

    public int magic() {
        return 7;
    }

    /** Round-trips a JSON snippet — exercises jackson at RUNTIME too. */
    public String jsonEcho(String json) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String echoed = mapper.writeValueAsString(mapper.readTree(json));
        log.debug("echoed {}", echoed);
        return echoed;
    }
}
