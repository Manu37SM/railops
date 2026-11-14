package in.railops.payment.contracts;

import tools.jackson.databind.ObjectMapper;
import in.railops.payment.service.PaymentService;
import in.railops.payment.web.PaymentControllers;
import in.railops.testsupport.HttpContract;
import in.railops.testsupport.ProductionObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** payment-service as PROVIDER: replays reservation's recorded request into the real controller + real JSON config. */
class PaymentHttpProviderContractTest {

    final ObjectMapper mapper = ProductionObjectMapper.load();
    final HttpContract contract = HttpContract.load("reservation-to-payment.create-payment");

    @Test
    void accepts_reservations_request_and_answers_in_the_agreed_shape() throws Exception {
        PaymentService service = mock(PaymentService.class);
        when(service.createOrGet(any())).thenReturn(new PaymentService.Intent(
                UUID.fromString("0b3f6a2e-58c1-4d7e-9a8b-2f1c3d4e5f60"), "http://gw/checkout/ord_1",
                new BigDecimal("2915.00"), "CREATED"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new PaymentControllers.InternalPayments(service))
                .setMessageConverters(new JacksonJsonHttpMessageConverter((JsonMapper) mapper)).build();

        MvcResult res = mvc.perform(post(contract.request().get("path").asString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(contract.request().get("body"))))
                .andReturn();

        assertThat(res.getResponse().getStatus()).isEqualTo(contract.response().get("status").asInt());
        HttpContract.assertSameShape(contract.response().get("body"),
                mapper.readTree(res.getResponse().getContentAsString()), "response");

        ArgumentCaptor<PaymentService.CreateRequest> got = ArgumentCaptor.forClass(PaymentService.CreateRequest.class);
        verify(service).createOrGet(got.capture());
        assertThat(got.getValue().pnr()).isEqualTo("2400000018");
        assertThat(got.getValue().amount()).isEqualByComparingTo("2915.00");   // MONEY: exact, not 2915.0000001
        assertThat(got.getValue().payBy()).isNotNull();
        assertThat(got.getValue().userId()).isNotNull();
    }
}
