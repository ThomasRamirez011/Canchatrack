package co.edu.unipamplona.canchatrack.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void generatesAnIdWhenTheRequestHasNone() throws Exception {
        AtomicReference<String> seenInChain = new AtomicReference<>();
        MockHttpServletResponse response = run(null, seenInChain);

        String header = response.getHeader(CorrelationIds.HEADER);
        assertThat(header).isNotBlank();
        assertThat(seenInChain.get()).isEqualTo(header);
    }

    @Test
    void reusesAWellFormedIncomingId() throws Exception {
        AtomicReference<String> seenInChain = new AtomicReference<>();
        MockHttpServletResponse response = run("client-req-12345", seenInChain);

        assertThat(response.getHeader(CorrelationIds.HEADER)).isEqualTo("client-req-12345");
        assertThat(seenInChain.get()).isEqualTo("client-req-12345");
    }

    @Test
    void replacesAnUnsafeIncomingId() throws Exception {
        AtomicReference<String> seenInChain = new AtomicReference<>();
        MockHttpServletResponse response = run("abc\r\nSet-Cookie: x=1", seenInChain);

        assertThat(response.getHeader(CorrelationIds.HEADER)).doesNotContain("\n").doesNotContain("Set-Cookie");
        assertThat(seenInChain.get()).doesNotContain("\n");
    }

    @Test
    void clearsTheMdcAfterTheRequest() throws Exception {
        run("client-req-12345", new AtomicReference<>());

        assertThat(MDC.get(CorrelationIds.MDC_KEY)).isNull();
    }

    private MockHttpServletResponse run(String incomingId, AtomicReference<String> seenInChain) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (incomingId != null) {
            request.addHeader(CorrelationIds.HEADER, incomingId);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res)
                    throws java.io.IOException, jakarta.servlet.ServletException {
                seenInChain.set(CorrelationIds.current());
                super.doFilter(req, res);
            }
        };
        filter.doFilter(request, response, chain);
        return response;
    }
}
