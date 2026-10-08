package com.careerlens.core.automation;

import com.careerlens.core.auth.AppPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/one-stop-runs")
@RequiredArgsConstructor
public class OneStopController {
    private final OneStopService service;

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    OneStopService.RunView start(@AuthenticationPrincipal AppPrincipal user,@Valid @RequestBody StartRequest request){
        return service.start(user.id(),request.resumeVersionId(),request.platforms(),request.spec());
    }
    @GetMapping("/current") OneStopService.RunView current(@AuthenticationPrincipal AppPrincipal user){return service.current(user.id());}
    @PostMapping("/{id}/pause") OneStopService.RunView pause(@AuthenticationPrincipal AppPrincipal user,@PathVariable UUID id){return service.pause(user.id(),id);}
    @PostMapping("/{id}/stop") OneStopService.RunView stop(@AuthenticationPrincipal AppPrincipal user,@PathVariable UUID id){return service.stop(user.id(),id);}
    @PostMapping("/{id}/resume") OneStopService.RunView resume(@AuthenticationPrincipal AppPrincipal user,@PathVariable UUID id){return service.resume(user.id(),id);}

    record StartRequest(@NotNull UUID resumeVersionId,List<String> platforms,@NotNull Map<String,Object> spec){}
}
