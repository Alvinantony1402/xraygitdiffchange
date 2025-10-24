package stepdefinitions;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.When;
import io.cucumber.java.en.Then;
import io.restassured.RestAssured;
import io.restassured.response.Response;

public class SearchSteps {

    static {
        RestAssured.useRelaxedHTTPSValidation();
        RestAssured.baseURI = "https://dummyjson.com";
    }

    private String keyword;
    private Response resp;

    @Given("the user is on the search page")
    public void the_user_is_on_the_search_page() {
    }

    @When("the user searches for {string}")
    public void the_user_searches_for(String kw) {
        this.keyword = kw;
        resp = given()
            .when()
            .get("/products/search?q=" + java.net.URLEncoder.encode(kw, java.nio.charset.StandardCharsets.UTF_8));
    }

    @Then("results related to {string} should be shown")
    public void results_related_to_should_be_shown(String expected) {
        resp.then()
            .statusCode(200)
            .body("total", greaterThanOrEqualTo(0));
        var json = resp.jsonPath();
        var titles = json.getList("products.title", String.class);
        boolean anyMatch = titles != null && titles.stream()
            .filter(t -> t != null)
            .anyMatch(t -> t.toLowerCase().contains(expected.toLowerCase()));
        org.junit.Assert.assertTrue("No product title contained keyword: " + expected, anyMatch);
    }
}
