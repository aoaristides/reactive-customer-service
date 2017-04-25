package customerservice;

import static customerservice.domain.enums.CustomerType.PERSON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.ACCEPT;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.CONTENT_TYPE;
import static org.springframework.http.HttpStatus.CREATED;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.NO_CONTENT;
import static org.springframework.http.HttpStatus.OK;
import static org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED;
import static org.springframework.http.MediaType.APPLICATION_JSON_UTF8;
import static org.springframework.http.MediaType.APPLICATION_JSON_UTF8_VALUE;
import static org.springframework.web.reactive.function.client.ExchangeFilterFunctions.basicAuthentication;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.Month;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.test.context.junit4.SpringRunner;
import static org.springframework.web.reactive.function.BodyInserters.*;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;

import customerservice.domain.Customer;

/**
 * Top to bottom integration test.
 * <p>
 * The application server (specified in pom.xml) is started and the service
 * deployed.
 * <p>
 * This class tests all CRUD operations in one big method.
 * 
 */
@RunWith(SpringRunner.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public class CustomerServiceTest {

	@LocalServerPort
    private int port;

	@Value("classpath:servicestore.pem")
	private Resource pemResource;

	private WebClient createSSLWebClient() throws IOException {

		/* Create a Reactor connector and tell it to trust our certificate */
		final File pemFile = pemResource.getFile();
		final ClientHttpConnector clientConnector = new ReactorClientHttpConnector(
				options -> options.sslSupport(builder -> builder.trustManager(pemFile)));

		/* Build a WebClient with the custom connector */
		return WebClient.builder()
				.baseUrl(String.format("https://127.0.0.1:%d", port))
				.clientConnector(clientConnector)
				.build();
	}

	@Test
	public void testCRUDOperationsAllTogether() throws IOException {

		final WebClient webClient = createSSLWebClient();
		
		final HttpHeaders headers = new HttpHeaders();
		headers.add(ACCEPT, APPLICATION_JSON_UTF8_VALUE);
		headers.add(CONTENT_TYPE, APPLICATION_JSON_UTF8_VALUE);
		headers.add(AUTHORIZATION, String.format("Bearer %s", requestToken(webClient)));

		final Customer newCustomer = Customer.ofType(PERSON).withBirthDate(LocalDate.of(1990, Month.AUGUST, 16)).build();

		// ---------- Create ----------
		ClientResponse resp = webClient.post().uri("/customers").headers(headers).body(fromObject(newCustomer)).exchange().block();

		assertThat(resp.statusCode()).isEqualTo(CREATED);
		final String newCustomerUrl = resp.headers().header("Location").get(0);
		assertThat(newCustomerUrl).contains("/customers/");

		// ---------- Read ----------
		resp = webClient.get().uri(newCustomerUrl).headers(headers).exchange().block();
		
		assertThat(resp.statusCode()).isEqualTo(OK);
		final Customer createdCustomer = resp.bodyToMono(Customer.class).block();
		assertThat(createdCustomer.getId()).isNotNull();

		// ---------- Update ----------
		final Customer customerToUpdate = Customer.from(createdCustomer).withFirstName("John").withLastName("Doe").build();
		resp = webClient.put().uri(newCustomerUrl).headers(headers).body(fromObject(customerToUpdate)).exchange().block();

		assertThat(resp.statusCode()).isEqualTo(NO_CONTENT);
		resp = webClient.get().uri(newCustomerUrl).headers(headers).exchange().block();
		assertThat(resp.statusCode()).isEqualTo(OK);
		final Customer updatedCustomer = resp.bodyToMono(Customer.class).block();
		assertThat(updatedCustomer.getId()).isEqualTo(updatedCustomer.getId());
		assertThat(updatedCustomer.getLastName()).isEqualTo("Doe");

		// ---------- Delete ----------
		resp = webClient.delete().uri(newCustomerUrl).headers(headers).exchange().block();

		assertThat(resp.statusCode()).isEqualTo(NO_CONTENT);
		resp = webClient.get().uri(newCustomerUrl).headers(headers).exchange().block();
		assertThat(resp.statusCode()).isEqualTo(NOT_FOUND);
	}

	/**
	 * Request a OAuth2 token from the Authentication Server
	 * 
	 * @param webClient The webClient to connect with
	 * 
	 * @return The token
	 */
	private String requestToken(WebClient webClient) {

		/* Add Basic Authentication to the WebClient */
		final WebClient webClientAuth = webClient.filter(basicAuthentication("clientId", "clientSecret"));
		
		final JsonNode tokenResp = webClientAuth.post().uri("/oauth/token")
			.contentType(APPLICATION_FORM_URLENCODED)
			.accept(APPLICATION_JSON_UTF8)
			.body(fromObject("grant_type=client_credentials"))
			.exchange()
			.flatMap(resp -> resp.bodyToMono(JsonNode.class))
			.block();
			
		return tokenResp.get("access_token").asText();
	}
}