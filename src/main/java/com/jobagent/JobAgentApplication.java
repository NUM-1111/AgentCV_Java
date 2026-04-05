package com.jobagent;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
public class JobAgentApplication {

    public static void main(String[] args) {
        loadDotEnv();
        SpringApplication.run(JobAgentApplication.class, args);
    }

    /** 加载项目根目录 .env 到系统属性，供 application.yml 占位符解析。 */
    private static void loadDotEnv() {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        dotenv.entries().forEach(e -> System.setProperty(e.getKey(), e.getValue()));
    }

    @RestController
    public static class PingController {

        @GetMapping("/ping")
        public String ping() {
            return "pong";
        }
    }
}
