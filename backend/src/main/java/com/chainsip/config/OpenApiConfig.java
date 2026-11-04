package com.chainsip.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 接口文档（springdoc-openapi）：启动后访问 /swagger-ui/index.html。
 * 右上角 Authorize 里填登录接口拿到的 JWT（不需要写 Bearer 前缀，Swagger 会补）。
 */
@Configuration
public class OpenApiConfig {

    private static final String SCHEME_NAME = "bearerAuth";

    @Bean
    public OpenAPI chainSipOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("ChainSIP 区块链养老保险平台 · 后端接口")
                        .version("1.0.0")
                        .description("认证在链下（账号 / 密码 / 地址绑定 / 会话），授权在链上"
                                + "（合约按 msg.sender 判定 ADMIN / BUREAU / SPONSOR / REGULATOR）。")
                        .contact(new Contact().name("ChainSIP")))
                .components(new Components().addSecuritySchemes(SCHEME_NAME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(SCHEME_NAME));
    }
}
