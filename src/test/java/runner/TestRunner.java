package runner;

import org.junit.runner.RunWith;
import io.cucumber.junit.Cucumber;
import io.cucumber.junit.CucumberOptions;

@RunWith(Cucumber.class)
@CucumberOptions(
    features = "src/test/java/features/login.feature",          
    glue = { "stepdefinitions", "hooks" },            
    plugin = {
        "pretty",
        "html:target/cucumber-report/junit/html",
        "json:target/cucumber-report/junit/cucumber.json",
        "junit:target/cucumber-report/junit/cucumber.xml"
    },
    monochrome = true,
    publish = false
)

public class TestRunner {
}
