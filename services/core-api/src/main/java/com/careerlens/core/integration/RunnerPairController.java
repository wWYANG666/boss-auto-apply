package com.careerlens.core.integration;

import com.careerlens.core.auth.AppPrincipal;
import com.careerlens.core.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class RunnerPairController {
    private final RunnerClient runner;
    private final RunnerCredentials credentials;
    @PostMapping("/api/v1/runner/pair")
    Map<String,Object> pair(@AuthenticationPrincipal AppPrincipal user,@Valid @RequestBody PairRequest request) {
        credentials.requireConfigured();
        var response=runner.pair(user.id(),request.code());
        Object token=runner.data(response).get("token");
        if(!(token instanceof String value) || value.length()<32)
            throw ApiException.conflict("RUNNER_PAIR_FAILED","设备配对失败，请检查本机配对码和有效期");
        credentials.save(user.id(),value);
        return Map.of("paired",true);
    }
    @GetMapping("/api/v1/runner/platform-resume")
    Map<String,Object> platformResume(){return runner.platformResume();}
    record PairRequest(@NotBlank @Size(min=20,max=100) String code) {}
    @PostMapping("/api/v1/runner/rotate")
    Map<String,Object> rotate(@AuthenticationPrincipal AppPrincipal user) {
        String token=credentials.stageRotation(user.id());
        if(runner.unavailable(runner.rotate(token)))throw ApiException.conflict("ROTATION_PENDING","轮换未完成，可重试同一待轮换凭据");
        credentials.save(user.id(),token);
        if(runner.unavailable(runner.commitRotation()))throw ApiException.conflict("ROTATION_PENDING","新凭据已保存，提交确认待重试");
        credentials.completeRotation(user.id());
        return Map.of("rotated",true);
    }
    @PostMapping("/api/v1/runner/revoke")
    Map<String,Object> revoke(@AuthenticationPrincipal AppPrincipal user) {
        if(runner.unavailable(runner.revoke()))throw ApiException.conflict("REVOCATION_PENDING","撤销未确认，请检查运行任务后重试");
        credentials.forget(user.id());
        return Map.of("revoked",true);
    }
}
