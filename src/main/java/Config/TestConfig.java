package Config;

import org.aeonbits.owner.Config;

@Config.LoadPolicy(Config.LoadType.MERGE)
@Config.Sources({
        "classpath:develop.properties",
        "system:properties",
        "system:env"
})
public interface TestConfig extends Config {

    @Key("BASE_URL")
    @DefaultValue("")
    String baseUrl();
    
    @Key("USER_MAIL")
    @DefaultValue("")
    String mail();
    
    @Key("API_KEY")
    @DefaultValue("")
    String ApiKey();
    
    @Key("POST_PRECON_URL")
    @DefaultValue("")
    String postpreconditionUrl();
    
    @Key("POST_URL")
    @DefaultValue("")
    String postUrl();
    
    @Key("XRAY_CLIENT_ID")
    String xrayClientId();

    @Key("XRAY_CLIENT_SECRET")
    String xrayClientSecret();



   

}