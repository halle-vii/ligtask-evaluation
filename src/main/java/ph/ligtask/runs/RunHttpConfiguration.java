package ph.ligtask.runs;

import org.springframework.context.annotation.*;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

@Configuration
public class RunHttpConfiguration {
    @Bean RestClientCustomizer evaluationTimeouts(){return builder->{
        var factory=new SimpleClientHttpRequestFactory();factory.setConnectTimeout(15000);factory.setReadTimeout(120000);
        builder.requestFactory(factory);
    };}
}
