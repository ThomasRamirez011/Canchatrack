package co.edu.unipamplona.canchatrack.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class PrivateNetworkAccessFilterTest {

    private final PrivateNetworkAccessFilter filter = new PrivateNetworkAccessFilter();

    private MockHttpServletResponse run(String method, String privateNetworkHeader) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/v1/dashboard/club");
        if (privateNetworkHeader != null) {
            request.addHeader("Access-Control-Request-Private-Network", privateNetworkHeader);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertThat(chain.getRequest()).isNotNull(); // siempre continúa la cadena
        return response;
    }

    @Test
    void preflightAskingForPrivateNetworkAccessGetsTheHeader() throws Exception {
        assertThat(run("OPTIONS", "true").getHeader("Access-Control-Allow-Private-Network")).isEqualTo("true");
    }

    @Test
    void otherRequestsAreLeftAlone() throws Exception {
        assertThat(run("OPTIONS", null).getHeader("Access-Control-Allow-Private-Network")).isNull();
        assertThat(run("OPTIONS", "false").getHeader("Access-Control-Allow-Private-Network")).isNull();
        assertThat(run("GET", "true").getHeader("Access-Control-Allow-Private-Network")).isNull();
    }
}
