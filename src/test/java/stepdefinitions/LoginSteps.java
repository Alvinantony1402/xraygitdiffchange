package stepdefinitions;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.When;
import io.cucumber.java.en.Then;
import io.restassured.RestAssured;
import io.restassured.response.Response;

public class LoginSteps {

    static {
        RestAssured.useRelaxedHTTPSValidation();
    }

    private Response loginResponse;

    @Given("the user is on the login page")
    public void the_user_is_on_the_login_page() {
        // For API tests, this can be a no-op or a health check; for UI, navigate via WebDriver.
        // Example API health check:
        // given().get("/health").then().statusCode(anyOf(is(200), is(204)));
    }

    @When("the user enters valid credentials")
    public void the_user_enters_valid_credentials() {
        // Replace with your real login endpoint and payload.
        loginResponse = given()
            .header("Content-Type", "application/json")
            .body("{\"username\":\"valid_user\",\"password\":\"pass12345\"}")
            .when()
            .post("https://httpbin.org/post"); // placeholder endpoint
    }
    
    @When("the user enters valid credentialss")
    public void the_user_enters_valid_credentialss() {
        // Replace with your real login endpoint and payload.
        loginResponse = given()
            .header("Content-Type", "application/json")
            .body("{\"username\":\"valid_user\",\"password\":\"pass12345\"}")
            .when()
            .post("https://httpbin.org/post"); // placeholder endpoint
    }

    @Then("the user should be logged in")
    public void the_user_should_be_logged_in() {
        // Adjust assertions to match your API's success contract (status, token, etc.).
        loginResponse.then()
            .statusCode(anyOf(is(200), is(201)))
            .body("json.username", equalTo("valid_user"));
        System.out.println("success");
    }
    
    @Then("the user should be logged inn")
    public void the_user_should_be_logged_inn() {
        // Adjust assertions to match your API's success contract (status, token, etc.).
        loginResponse.then()
            .statusCode(anyOf(is(200), is(201)))
            .body("json.username", equalTo("valid_user"));
        System.out.println("success");
    }
}
